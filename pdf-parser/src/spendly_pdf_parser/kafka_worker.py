"""Kafka consumer for spendly.statement-uploaded (group spendly-pdf-parser).

Per message: validate -> skip non-PDF -> resolve tempFilePath safely -> parse -> publish
TransactionsImported or StatementParseFailed (key accountId, contract headers) -> wait for the delivery
report -> delete the temp PDF (success only, honoring retention) -> commit the offset.
Invalid messages go straight to <topic>.dlq; transient errors retry with 1 s, 2 s, 4 s backoff, then DLQ.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any, Protocol

from confluent_kafka import Consumer, KafkaError, KafkaException, Producer, TopicPartition

from . import events
from .contracts import Contracts
from .log import ids
from .paths import UnsafePathError, resolve_upload_path
from .pipeline import ParseOutcome, StatementContext, failure, parse_pdf
from .sanitizer import Sanitizer
from .settings import Settings

log = logging.getLogger("spendly.pdf_parser.worker")

RETRY_BACKOFF_SECONDS: tuple[float, ...] = (1.0, 2.0, 4.0)
FLUSH_TIMEOUT_SECONDS = 15.0


class TransientError(Exception):
    """Worth retrying (Kafka delivery failure, temporarily unreadable file)."""


class ProducerLike(Protocol):
    def produce(self, topic: str, value: bytes, key: bytes | None = ..., headers: Any = ..., on_delivery: Any = ...) -> None: ...
    def flush(self, timeout: float = ...) -> int: ...
    def poll(self, timeout: float = ...) -> int: ...


def producer_config(settings: Settings) -> dict[str, Any]:
    return {
        "bootstrap.servers": settings.kafka_bootstrap_servers,
        "client.id": "spendly-pdf-parser",
        "enable.idempotence": True,
        "acks": "all",
        "linger.ms": 5,
        "message.timeout.ms": 10000,
    }


def consumer_config(settings: Settings) -> dict[str, Any]:
    return {
        "bootstrap.servers": settings.kafka_bootstrap_servers,
        "group.id": events.CONSUMER_GROUP,
        "client.id": "spendly-pdf-parser",
        "enable.auto.commit": False,
        "enable.auto.offset.store": False,
        "auto.offset.reset": "earliest",
        "max.poll.interval.ms": 300000,
    }


def _header(headers: Sequence[tuple[str, bytes | None]] | None, name: str) -> str | None:
    for key, value in headers or []:
        if key == name and value is not None:
            return value.decode("utf-8", errors="replace")
    return None


class StatementWorker:
    def __init__(
        self,
        settings: Settings,
        contracts: Contracts,
        sanitizer: Sanitizer,
        producer: ProducerLike,
        consumer_factory: Callable[[], Any] | None = None,
        backoff: Sequence[float] = RETRY_BACKOFF_SECONDS,
    ) -> None:
        self.settings = settings
        self.contracts = contracts
        self.sanitizer = sanitizer
        self.producer = producer
        self._consumer_factory = consumer_factory or (lambda: Consumer(consumer_config(settings)))
        self._consumer: Any = None
        self._backoff = tuple(backoff)
        self._stop = threading.Event()
        self._pending_deletions: list[tuple[float, Path]] = []
        self.running = False

    # ------------------------------------------------------------------ lifecycle

    def stop(self) -> None:
        self._stop.set()

    @property
    def stopping(self) -> bool:
        return self._stop.is_set()

    def run(self) -> None:
        self._consumer = self._consumer_factory()
        self._consumer.subscribe([events.TOPIC_STATEMENT_UPLOADED])
        self.running = True
        log.info("consumer started", extra=ids(topic=events.TOPIC_STATEMENT_UPLOADED))
        try:
            while not self._stop.is_set():
                self._run_due_deletions()
                msg = self._consumer.poll(1.0)
                if msg is None:
                    continue
                if msg.error():
                    if msg.error().code() != KafkaError._PARTITION_EOF:
                        log.warning("consumer error", extra=ids(errorType=msg.error().name()))
                    continue
                try:
                    self.handle_message(msg)
                except Exception as exc:  # never let one message kill the loop; re-read it after a pause
                    log.error("unexpected error handling message", extra=ids(
                        errorType=type(exc).__name__, topic=msg.topic(), partition=msg.partition(), offset=msg.offset()))
                    try:
                        self._consumer.seek(TopicPartition(msg.topic(), msg.partition(), msg.offset()))
                    except KafkaException:
                        pass
                    self._stop.wait(self._backoff[0] if self._backoff else 1.0)
        finally:
            self.running = False
            self._run_due_deletions(force=True)  # privacy first: don't leave originals behind on shutdown
            try:
                self._consumer.close()
            finally:
                self.producer.flush(5.0)
            log.info("consumer stopped")

    # ------------------------------------------------------------------ message handling

    def handle_message(self, msg: Any) -> str:
        """Process one StatementUploaded message. Returns an outcome label (for tests and logs)."""
        base = {"topic": msg.topic(), "partition": msg.partition(), "offset": msg.offset()}
        try:
            event = json.loads(msg.value().decode("utf-8"))
        except (ValueError, AttributeError):
            return self._dead_letter(msg, "INVALID_JSON")
        if not isinstance(event, dict) or self.contracts.validate_as("StatementUploaded", event):
            return self._dead_letter(msg, "SCHEMA_VALIDATION")

        payload = event["payload"]
        log_ids = {**base, "eventId": event["eventId"], "statementId": payload["statementId"],
                   "accountId": payload["accountId"], "correlationId": event["correlationId"]}
        if payload["fileType"] != "PDF":
            self._commit(msg)
            log.info("skipped non-PDF statement", extra=ids(outcome="SKIPPED", **log_ids))
            return "SKIPPED"
        try:
            path = resolve_upload_path(self.settings.spendly_upload_dir, payload["tempFilePath"])
        except UnsafePathError:
            return self._dead_letter(msg, "UNSAFE_TEMP_FILE_PATH", log_ids)

        ctx = StatementContext(
            statement_id=payload["statementId"],
            account_id=payload["accountId"],
            currency=payload["accountCurrency"],
            correlation_id=event["correlationId"],
        )
        started = time.monotonic()
        outcome: ParseOutcome | None = None
        for attempt, delay in enumerate((0.0, *self._backoff)):
            if delay and self._stop.wait(delay):
                log.info("shutdown during retry; message will be redelivered", extra=ids(**log_ids))
                return "INTERRUPTED"
            try:
                outcome = self._parse_file(path, ctx, log_ids)
                self._publish(outcome.event, key=ctx.account_id)
                break
            except TransientError as exc:
                log.warning("transient error", extra=ids(attempt=attempt + 1, errorType=type(exc.__cause__ or exc).__name__, **log_ids))
                outcome = None
        if outcome is None:
            return self._dead_letter(msg, "RETRIES_EXHAUSTED", log_ids)

        if outcome.succeeded:
            self._schedule_deletion(path)
        self._commit(msg)
        log.info("statement processed", extra=ids(
            outcome=outcome.event_type, reason=outcome.reason, eventId=outcome.event["eventId"],
            statementId=ctx.statement_id, accountId=ctx.account_id, correlationId=ctx.correlation_id,
            transactionCount=outcome.summary.transaction_count if outcome.succeeded else None,
            durationMs=int((time.monotonic() - started) * 1000)))
        return outcome.event_type

    def _parse_file(self, path: Path, ctx: StatementContext, log_ids: dict[str, Any]) -> ParseOutcome:
        if not path.is_file():
            return failure(ctx, "FILE_NOT_FOUND", "The uploaded file was not found in the upload folder", contracts=self.contracts)
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            return failure(ctx, "FILE_NOT_FOUND", "The uploaded file was not found in the upload folder", contracts=self.contracts)
        except OSError as exc:
            raise TransientError("file read failed") from exc
        try:
            return parse_pdf(data, ctx, self.sanitizer, self.contracts)
        except Exception as exc:  # deterministic bug: report it instead of retrying forever
            log.error("parser failed", extra=ids(errorType=type(exc).__name__, **log_ids))
            return failure(ctx, "INTERNAL_ERROR", contracts=self.contracts)

    # ------------------------------------------------------------------ kafka output

    def _send(self, topic: str, value: bytes, key: bytes | None, headers: list[tuple[str, bytes]]) -> None:
        result: dict[str, Any] = {}

        def on_delivery(err: Any, _msg: Any) -> None:
            result["err"] = err
            result["done"] = True

        try:
            self.producer.produce(topic, value=value, key=key, headers=headers, on_delivery=on_delivery)
            remaining = self.producer.flush(FLUSH_TIMEOUT_SECONDS)
        except (KafkaException, BufferError) as exc:
            raise TransientError("produce failed") from exc
        if remaining or not result.get("done") or result.get("err") is not None:
            raise TransientError("delivery not confirmed")

    def _publish(self, event: dict[str, Any], key: str) -> None:
        errors = self.contracts.validate(event)
        if errors:  # pipeline validates already; this is the last guard before the wire
            raise RuntimeError("refusing to publish an event that fails schema validation")
        self._send(
            events.TOPIC_BY_EVENT[event["eventType"]],
            json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            key.encode("utf-8"),
            events.kafka_headers(event),
        )

    def _dead_letter(self, msg: Any, reason: str, log_ids: dict[str, Any] | None = None) -> str:
        headers = list(msg.headers() or []) + [
            ("dlqReason", reason.encode("utf-8")),
            ("dlqSource", events.PRODUCER.encode("utf-8")),
            ("dlqFailedAt", events.now_iso().encode("utf-8")),
        ]
        topic = msg.topic() + events.DLQ_SUFFIX
        delays = (0.0, *self._backoff)
        attempt = 0
        while True:
            try:
                self._send(topic, msg.value(), msg.key(), headers)
                break
            except TransientError:
                delay = delays[min(attempt + 1, len(delays) - 1)] if delays else 1.0
                attempt += 1
                log.warning("DLQ publish failed; retrying", extra=ids(attempt=attempt, reason=reason))
                if self._stop.wait(delay):
                    return "INTERRUPTED"
        self._commit(msg)
        extra = log_ids or {"topic": msg.topic(), "partition": msg.partition(), "offset": msg.offset(),
                            "eventId": _header(msg.headers(), "eventId"),
                            "correlationId": _header(msg.headers(), "correlationId")}
        log.warning("message sent to DLQ", extra=ids(reason=reason, outcome="DLQ", **extra))
        return "DLQ"

    def _commit(self, msg: Any) -> None:
        if self._consumer is None:
            return
        try:
            self._consumer.commit(message=msg, asynchronous=False)
        except KafkaException as exc:  # at-least-once: the message may be redelivered; output is idempotent
            log.warning("offset commit failed", extra=ids(errorType=type(exc).__name__, topic=msg.topic(),
                                                            partition=msg.partition(), offset=msg.offset()))

    # ------------------------------------------------------------------ temp file retention (AC-SD-3)

    def _schedule_deletion(self, path: Path) -> None:
        retention = self.settings.upload_retention.total_seconds()
        if retention <= 0:
            self._delete(path)
        else:
            self._pending_deletions.append((time.monotonic() + retention, path))

    def _run_due_deletions(self, force: bool = False) -> None:
        if not self._pending_deletions:
            return
        now = time.monotonic()
        keep = []
        for due, path in self._pending_deletions:
            if force or due <= now:
                self._delete(path)
            else:
                keep.append((due, path))
        self._pending_deletions = keep

    @staticmethod
    def _delete(path: Path) -> None:
        try:
            path.unlink(missing_ok=True)
        except OSError as exc:
            log.error("could not delete temp file", extra=ids(errorType=type(exc).__name__))


def make_producer(settings: Settings) -> Producer:
    return Producer(producer_config(settings))
