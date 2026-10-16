"""End-to-end through the compose Redpanda (localhost:19092) and a running pdf-parser (container or uv run).

Skipped when Kafka or the pdf-parser /health endpoint is not reachable. Uses a fresh random statementId,
a synthetic accountId, and a unique consumer group for its reader. The test PDF is removed afterwards
even if the service did not delete it.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import socket
import time
import urllib.request
import uuid
from datetime import timedelta
from decimal import Decimal

import pytest

from spendly_pdf_parser.events import TOPIC_STATEMENT_PARSE_FAILED, TOPIC_STATEMENT_UPLOADED, TOPIC_TRANSACTIONS_IMPORTED
from spendly_pdf_parser.settings import Settings

from .conftest import SAMPLE_PDF
from .helpers import assert_no_pii, recompute_tx_hashes, statement_uploaded

BOOTSTRAP = "localhost:19092"
SERVICE_HEALTH = "http://localhost:8090/health"

pytestmark = pytest.mark.integration


def _kafka_reachable() -> bool:
    host, port = BOOTSTRAP.split(":")
    try:
        with socket.create_connection((host, int(port)), timeout=2):
            return True
    except OSError:
        return False


def _service_consuming() -> bool:
    try:
        with urllib.request.urlopen(SERVICE_HEALTH, timeout=3) as r:
            body = json.loads(r.read())
        return body["kafka"]["consumerRunning"] is True and body["kafka"]["state"] == "UP"
    except Exception:  # noqa: BLE001
        return False


@pytest.fixture(scope="module")
def kafka_ready() -> None:
    if not _kafka_reachable():
        pytest.skip(f"Kafka not reachable on {BOOTSTRAP}")
    if not _service_consuming():
        pytest.skip(f"pdf-parser not running/consuming ({SERVICE_HEALTH})")


def test_statement_uploaded_to_transactions_imported(kafka_ready, contracts) -> None:
    from confluent_kafka import Consumer, Producer

    settings = Settings()  # reads ../.env for SPENDLY_UPLOAD_DIR / retention (values are never printed)
    upload_dir = settings.spendly_upload_dir
    assert upload_dir.is_dir(), "SPENDLY_UPLOAD_DIR must exist on the host"

    statement_id, account_id = str(uuid.uuid4()), str(uuid.uuid4())
    correlation_id = f"it-{uuid.uuid4().hex[:12]}"
    target = upload_dir / f"{statement_id}.pdf"

    reader = Consumer({
        "bootstrap.servers": BOOTSTRAP,
        "group.id": f"pdf-parser-it-{uuid.uuid4().hex}",
        "auto.offset.reset": "latest",
        "enable.auto.commit": False,
    })
    try:
        reader.subscribe([TOPIC_TRANSACTIONS_IMPORTED, TOPIC_STATEMENT_PARSE_FAILED])
        deadline = time.monotonic() + 30
        while not reader.assignment() and time.monotonic() < deadline:
            reader.poll(0.5)
        assert reader.assignment(), "reader got no partition assignment"
        # pin current end offsets so 'latest' is resolved before we produce
        for tp in reader.assignment():
            reader.position([tp])

        shutil.copyfile(SAMPLE_PDF, target)
        incoming = statement_uploaded(
            statement_id, account_id, target.name,
            file_sha256=hashlib.sha256(target.read_bytes()).hexdigest(), correlation_id=correlation_id,
        )
        assert contracts.validate(incoming) == []
        producer = Producer({"bootstrap.servers": BOOTSTRAP})
        producer.produce(TOPIC_STATEMENT_UPLOADED, key=account_id.encode(), value=json.dumps(incoming).encode(),
                         headers=[("eventId", incoming["eventId"].encode()), ("eventType", b"StatementUploaded"),
                                  ("schemaVersion", b"1"), ("correlationId", correlation_id.encode())])
        assert producer.flush(10) == 0

        found = None
        deadline = time.monotonic() + 60
        while found is None and time.monotonic() < deadline:
            msg = reader.poll(1.0)
            if msg is None or msg.error():
                continue
            event = json.loads(msg.value())
            if event.get("payload", {}).get("statementId") == statement_id:
                found = (msg, event)
        assert found is not None, "no event for the test statementId within 60 s"
        msg, event = found
        assert msg.topic() == TOPIC_TRANSACTIONS_IMPORTED, f"got {event['eventType']}: {event['payload'].get('reason')}"
        assert contracts.validate(event) == []
        assert msg.key() == account_id.encode()
        headers = {k: v.decode() for k, v in msg.headers()}
        assert headers == {"eventId": event["eventId"], "eventType": "TransactionsImported",
                           "schemaVersion": "1", "correlationId": correlation_id}
        payload = event["payload"]
        assert payload["transactionCount"] == len(payload["transactions"]) == 5
        amounts = [Decimal(t["amount"]) for t in payload["transactions"]]
        assert sum(-a for a in amounts if a < 0) == Decimal("951.00")
        assert sum(a for a in amounts if a > 0) == Decimal("800.00")
        assert [t["txHash"] for t in payload["transactions"]] == recompute_tx_hashes(payload)
        assert_no_pii(msg.value().decode("utf-8"))

        if settings.upload_retention == timedelta(0):  # AC-SD-3 end to end
            deadline = time.monotonic() + 10
            while target.exists() and time.monotonic() < deadline:
                time.sleep(0.2)
            assert not target.exists(), "temp PDF should be deleted after a successful import"
    finally:
        reader.close()
        target.unlink(missing_ok=True)
