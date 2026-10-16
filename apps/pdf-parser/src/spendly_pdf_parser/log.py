"""Structured JSON logging that carries IDs only.

Only whitelisted ``extra`` keys are emitted, so a careless ``extra={"description": ...}`` can never leak.
Messages are static strings written in this code base; exception details are reduced to the exception
type (no message, no traceback) because library exception messages may echo input values.
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime

ALLOWED_FIELDS = (
    "eventId",
    "eventType",
    "statementId",
    "accountId",
    "correlationId",
    "topic",
    "partition",
    "offset",
    "reason",
    "attempt",
    "outcome",
    "durationMs",
    "errorType",
    "transactionCount",
)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, object] = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage() if record.name.startswith("spendly") else _safe_library_message(record),
        }
        for key in ALLOWED_FIELDS:
            value = getattr(record, key, None)
            if value is not None:
                entry[key] = value
        if record.exc_info and record.exc_info[0] is not None:
            entry.setdefault("errorType", record.exc_info[0].__name__)
        return json.dumps(entry, ensure_ascii=False)


def _safe_library_message(record: logging.LogRecord) -> str:
    # uvicorn access logs: method + path + status only (no query string, no bodies)
    if record.name == "uvicorn.access" and isinstance(record.args, tuple) and len(record.args) >= 5:
        _, method, path, _, status = record.args[:5]
        return f"{method} {str(path).split('?', 1)[0]} {status}"
    return record.getMessage()


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level.upper())
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        lg = logging.getLogger(name)
        lg.handlers[:] = []
        lg.propagate = True
    quiet_content_loggers()


def quiet_content_loggers() -> None:
    """pdfminer logs raw page content (names, IBANs, card numbers) at DEBUG: never let it below WARNING."""
    for name in ("pdfminer", "pdfplumber", "confluent_kafka"):
        logging.getLogger(name).setLevel(logging.WARNING)


def ids(**kwargs: object) -> dict[str, object]:
    """Build a logging ``extra`` dict, dropping None values."""
    return {k: v for k, v in kwargs.items() if v is not None}
