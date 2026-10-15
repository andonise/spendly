"""Kafka worker with a mocked producer/consumer: routing, DLQ, retries, commits and temp-file deletion (AC-SD-3)."""

from __future__ import annotations

import json
import logging
import shutil
from pathlib import Path
from typing import Any

import pytest

from spendly_pdf_parser.kafka_worker import StatementWorker
from spendly_pdf_parser.log import JsonFormatter, quiet_content_loggers
from spendly_pdf_parser.settings import Settings

from .conftest import ACCOUNT_ID, SAMPLE_PDF, STATEMENT_ID
from .helpers import assert_no_pii, recompute_tx_hashes, statement_uploaded, to_bytes


class FakeMsg:
    def __init__(self, value: bytes, key: bytes | None = ACCOUNT_ID.encode(), headers=None, offset: int = 7) -> None:
        self._value, self._key, self._headers, self._offset = value, key, headers or [], offset

    def value(self): return self._value
    def key(self): return self._key
    def headers(self): return self._headers
    def topic(self): return "spendly.statement-uploaded"
    def partition(self): return 0
    def offset(self): return self._offset
    def error(self): return None


class FakeProducer:
    def __init__(self, fail_times: int = 0) -> None:
        self.sent: list[dict[str, Any]] = []
        self._pending: list[tuple[Any, dict[str, Any]]] = []
        self.fail_times = fail_times

    def produce(self, topic, value, key=None, headers=None, on_delivery=None):
        record = {"topic": topic, "value": value, "key": key, "headers": dict(headers or [])}
        self._pending.append((on_delivery, record))

    def flush(self, timeout: float = 0) -> int:
        for cb, record in self._pending:
            if self.fail_times > 0:
                self.fail_times -= 1
                cb("BROKER_DOWN", None)
            else:
                self.sent.append(record)
                cb(None, None)
        self._pending.clear()
        return 0

    def poll(self, timeout: float = 0) -> int:
        return 0


class FakeConsumer:
    def __init__(self) -> None:
        self.committed: list[int] = []

    def commit(self, message=None, asynchronous=True):
        self.committed.append(message.offset())


@pytest.fixture
def upload_dir(tmp_path: Path) -> Path:
    d = tmp_path / "uploads"
    d.mkdir()
    return d


def make_worker(upload_dir: Path, contracts, sanitizer, producer, retention: str = "PT0S") -> StatementWorker:
    settings = Settings(_env_file=None, SPENDLY_UPLOAD_DIR=str(upload_dir), SPENDLY_UPLOAD_RETENTION=retention)
    worker = StatementWorker(settings, contracts, sanitizer, producer, backoff=(0.0, 0.0, 0.0))
    worker._consumer = FakeConsumer()
    return worker


def put_sample(upload_dir: Path, name: str = f"{STATEMENT_ID}.pdf") -> Path:
    target = upload_dir / name
    shutil.copyfile(SAMPLE_PDF, target)
    return target


def test_success_publishes_then_deletes_and_commits(upload_dir, contracts, sanitizer, caplog) -> None:
    pdf = put_sample(upload_dir)
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    incoming = statement_uploaded(STATEMENT_ID, ACCOUNT_ID, pdf.name, correlation_id="upl-corr-42")
    quiet_content_loggers()  # what configure_logging() does at startup, even with LOG_LEVEL=DEBUG
    with caplog.at_level(logging.DEBUG):
        assert worker.handle_message(FakeMsg(to_bytes(incoming))) == "TransactionsImported"

    assert not pdf.exists(), "temp PDF must be deleted after a successful publish (PT0S)"
    assert worker._consumer.committed == [7]
    [sent] = producer.sent
    assert sent["topic"] == "spendly.transactions-imported"
    assert sent["key"] == ACCOUNT_ID.encode()
    event = json.loads(sent["value"])
    assert contracts.validate(event) == []
    assert event["correlationId"] == "upl-corr-42"
    assert event["payload"]["statementId"] == STATEMENT_ID
    assert event["payload"]["transactionCount"] == len(event["payload"]["transactions"]) == 5
    assert [t["txHash"] for t in event["payload"]["transactions"]] == recompute_tx_hashes(event["payload"])
    assert sent["headers"] == {
        "eventId": event["eventId"].encode(),
        "eventType": b"TransactionsImported",
        "schemaVersion": b"1",
        "correlationId": b"upl-corr-42",
    }
    assert_no_pii(sent["value"].decode("utf-8"))
    # logs carry IDs only
    formatter = JsonFormatter()
    log_text = "\n".join(formatter.format(r) for r in caplog.records)
    assert_no_pii(log_text)
    for t in event["payload"]["transactions"]:
        assert t["description"] not in log_text and t["amount"] not in log_text
    assert STATEMENT_ID in log_text


def test_retention_keeps_file_until_due(upload_dir, contracts, sanitizer) -> None:
    pdf = put_sample(upload_dir)
    worker = make_worker(upload_dir, contracts, sanitizer, FakeProducer(), retention="PT1H")
    worker.handle_message(FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, pdf.name))))
    assert pdf.exists()
    worker._run_due_deletions()
    assert pdf.exists()
    worker._run_due_deletions(force=True)  # what shutdown does
    assert not pdf.exists()


def test_balance_mismatch_keeps_file(upload_dir, contracts, sanitizer, monkeypatch) -> None:
    pdf = put_sample(upload_dir)
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    from spendly_pdf_parser import pipeline
    from spendly_pdf_parser.extraction import RawRow

    real = pipeline.extract_statement

    def tampered(source, layouts=None):
        raw = real(source, layouts)
        raw.rows.append(RawRow("30.08.2026", "EXTRA", "1,00", ""))
        return raw

    monkeypatch.setattr(pipeline, "extract_statement", tampered)
    assert worker.handle_message(FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, pdf.name)))) == "StatementParseFailed"
    assert pdf.exists(), "temp PDF must be kept when parsing fails"
    [sent] = producer.sent
    assert sent["topic"] == "spendly.statement-parse-failed"
    event = json.loads(sent["value"])
    assert contracts.validate(event) == []
    assert event["payload"]["reason"] == "BALANCE_MISMATCH" and event["payload"]["retryable"] is False
    assert worker._consumer.committed == [7]


def test_publish_failure_retries_then_dlq_and_keeps_file(upload_dir, contracts, sanitizer) -> None:
    pdf = put_sample(upload_dir)
    producer = FakeProducer(fail_times=4)  # initial attempt + 3 retries all fail
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    raw = to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, pdf.name))
    assert worker.handle_message(FakeMsg(raw, headers=[("eventId", b"x")])) == "DLQ"
    assert pdf.exists()
    [dlq] = producer.sent
    assert dlq["topic"] == "spendly.statement-uploaded.dlq"
    assert dlq["value"] == raw and dlq["key"] == ACCOUNT_ID.encode()
    assert dlq["headers"]["dlqReason"] == b"RETRIES_EXHAUSTED"
    assert dlq["headers"]["dlqSource"] == b"spendly-pdf-parser"
    assert dlq["headers"]["eventId"] == b"x"
    assert b"dlqFailedAt" in [k.encode() for k in dlq["headers"]]
    assert worker._consumer.committed == [7]


def test_transient_failure_recovers(upload_dir, contracts, sanitizer) -> None:
    pdf = put_sample(upload_dir)
    producer = FakeProducer(fail_times=2)
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    assert worker.handle_message(FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, pdf.name)))) == "TransactionsImported"
    assert [s["topic"] for s in producer.sent] == ["spendly.transactions-imported"]
    assert not pdf.exists()


@pytest.mark.parametrize("payload", [b"not json", b"[]", b'{"eventType":"StatementUploaded"}'])
def test_invalid_message_goes_to_dlq(upload_dir, contracts, sanitizer, payload) -> None:
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    assert worker.handle_message(FakeMsg(payload)) == "DLQ"
    [dlq] = producer.sent
    assert dlq["topic"] == "spendly.statement-uploaded.dlq"
    assert dlq["headers"]["dlqReason"] in (b"INVALID_JSON", b"SCHEMA_VALIDATION")
    assert worker._consumer.committed == [7]


@pytest.mark.parametrize("bad_path", ["../outside.pdf", "a/../../outside.pdf", "/etc/passwd"])
def test_path_traversal_message_is_rejected(upload_dir, contracts, sanitizer, bad_path) -> None:
    outside = upload_dir.parent / "outside.pdf"
    shutil.copyfile(SAMPLE_PDF, outside)
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    assert worker.handle_message(FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, bad_path)))) == "DLQ"
    assert [s["topic"] for s in producer.sent] == ["spendly.statement-uploaded.dlq"]
    assert outside.exists()


def test_unsafe_path_passing_schema_is_rejected(upload_dir, contracts, sanitizer, monkeypatch) -> None:
    # defence in depth: even if the schema let it through, resolution rejects escapes
    from spendly_pdf_parser import kafka_worker
    from spendly_pdf_parser.paths import UnsafePathError

    def boom(*_a):
        raise UnsafePathError("escapes")

    monkeypatch.setattr(kafka_worker, "resolve_upload_path", boom)
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    assert worker.handle_message(FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, "x.pdf")))) == "DLQ"
    assert producer.sent[0]["headers"]["dlqReason"] == b"UNSAFE_TEMP_FILE_PATH"


def test_non_pdf_is_skipped_and_committed(upload_dir, contracts, sanitizer) -> None:
    csv = upload_dir / f"{STATEMENT_ID}.csv"
    csv.write_text("a;b\n", encoding="utf-8")
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    msg = FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, csv.name, file_type="CSV")))
    assert worker.handle_message(msg) == "SKIPPED"
    assert producer.sent == []
    assert worker._consumer.committed == [7]
    assert csv.exists()


def test_missing_file_publishes_file_not_found(upload_dir, contracts, sanitizer) -> None:
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    assert worker.handle_message(FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, "missing.pdf")))) == "StatementParseFailed"
    event = json.loads(producer.sent[0]["value"])
    assert event["payload"]["reason"] == "FILE_NOT_FOUND"
    assert contracts.validate(event) == []


def test_unexpected_parser_error_is_internal_error(upload_dir, contracts, sanitizer, monkeypatch) -> None:
    pdf = put_sample(upload_dir)
    from spendly_pdf_parser import kafka_worker

    def crash(*_a, **_k):
        raise ZeroDivisionError

    monkeypatch.setattr(kafka_worker, "parse_pdf", crash)
    producer = FakeProducer()
    worker = make_worker(upload_dir, contracts, sanitizer, producer)
    assert worker.handle_message(FakeMsg(to_bytes(statement_uploaded(STATEMENT_ID, ACCOUNT_ID, pdf.name)))) == "StatementParseFailed"
    assert json.loads(producer.sent[0]["value"])["payload"]["reason"] == "INTERNAL_ERROR"
    assert pdf.exists()
