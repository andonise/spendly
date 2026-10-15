"""Envelope builders for the events this service produces, and the Kafka headers that mirror them."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

from . import __version__

PRODUCER = "spendly-pdf-parser"
PARSER = {"name": "python-pdf-pdfplumber", "version": __version__}
SCHEMA_VERSION = 1

TOPIC_STATEMENT_UPLOADED = "spendly.statement-uploaded"
TOPIC_TRANSACTIONS_IMPORTED = "spendly.transactions-imported"
TOPIC_STATEMENT_PARSE_FAILED = "spendly.statement-parse-failed"
DLQ_SUFFIX = ".dlq"
TOPIC_BY_EVENT = {
    "TransactionsImported": TOPIC_TRANSACTIONS_IMPORTED,
    "StatementParseFailed": TOPIC_STATEMENT_PARSE_FAILED,
}
CONSUMER_GROUP = "spendly-pdf-parser"


def now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def envelope(event_type: str, correlation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "schemaVersion": SCHEMA_VERSION,
        "occurredAt": now_iso(),
        "correlationId": correlation_id,
        "producer": PRODUCER,
        "payload": payload,
    }


def transactions_imported(
    *,
    statement_id: str,
    account_id: str,
    correlation_id: str,
    period_start: str,
    period_end: str,
    currency: str,
    transactions: list[dict[str, Any]],
) -> dict[str, Any]:
    return envelope(
        "TransactionsImported",
        correlation_id,
        {
            "statementId": statement_id,
            "accountId": account_id,
            "parser": dict(PARSER),
            "periodStart": period_start,
            "periodEnd": period_end,
            "currency": currency,
            "transactionCount": len(transactions),
            "transactions": transactions,
        },
    )


def statement_parse_failed(
    *,
    statement_id: str,
    account_id: str,
    correlation_id: str,
    reason: str,
    message: str,
    retryable: bool = False,
) -> dict[str, Any]:
    return envelope(
        "StatementParseFailed",
        correlation_id,
        {
            "statementId": statement_id,
            "accountId": account_id,
            "parser": dict(PARSER),
            "reason": reason,
            "message": message[:500],
            "retryable": retryable,
        },
    )


def kafka_headers(event: dict[str, Any]) -> list[tuple[str, bytes]]:
    return [
        ("eventId", event["eventId"].encode("utf-8")),
        ("eventType", event["eventType"].encode("utf-8")),
        ("schemaVersion", str(event["schemaVersion"]).encode("utf-8")),
        ("correlationId", event["correlationId"].encode("utf-8")),
    ]
