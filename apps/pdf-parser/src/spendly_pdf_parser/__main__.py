"""Entry point: ``uv run spendly-pdf-parser`` or ``python -m spendly_pdf_parser``.

Starts the FastAPI app (uvicorn, main thread) and the Kafka worker (background thread) in one process.
SIGTERM/SIGINT are handled by uvicorn, whose shutdown stops the worker gracefully (finishes the current
message, closes the consumer, flushes the producer).
"""

from __future__ import annotations

import logging
import sys

import uvicorn

from .api import KafkaHealth, Runtime, create_app
from .contracts import load_contracts
from .kafka_worker import StatementWorker, make_producer
from .log import configure_logging
from .sanitizer import Sanitizer
from .settings import ConfigurationError, Settings, load_hmac_key

log = logging.getLogger("spendly.pdf_parser")


def build_runtime(settings: Settings) -> Runtime:
    contracts = load_contracts(settings.contracts_dir)
    key = load_hmac_key(settings.spendly_hmac_secret_path)
    if len(key) < 32:
        log.warning("HMAC key is shorter than 32 bytes; generate a longer one (see .env.example)")
    sanitizer = Sanitizer(contracts.pii_rules, key)
    if not settings.spendly_upload_dir.is_dir():
        log.warning("SPENDLY_UPLOAD_DIR does not exist or is not a directory; PDF events will fail with FILE_NOT_FOUND")
    runtime = Runtime(settings=settings, contracts=contracts, sanitizer=sanitizer)
    if settings.kafka_enabled:
        producer = make_producer(settings)
        runtime.worker = StatementWorker(settings, contracts, sanitizer, producer)
        runtime.kafka_health = KafkaHealth(producer)
    return runtime


def main() -> None:
    try:
        settings = Settings()
    except Exception as exc:  # pydantic errors may include input values: print the field names only
        errors = getattr(exc, "errors", lambda: [])()
        names = ", ".join(str(e.get("loc", ("?",))[0]) for e in errors) or type(exc).__name__
        print(f"spendly-pdf-parser: invalid configuration: {names}", file=sys.stderr)
        raise SystemExit(2) from None
    configure_logging(settings.log_level)
    try:
        runtime = build_runtime(settings)
    except ConfigurationError as exc:
        log.critical(str(exc))
        print(f"spendly-pdf-parser: {exc}", file=sys.stderr)
        raise SystemExit(2) from None
    log.info("starting")
    uvicorn.run(
        create_app(runtime),
        host="0.0.0.0",
        port=settings.pdf_parser_port,
        log_config=None,
        log_level=settings.log_level.lower(),
        timeout_graceful_shutdown=30,
    )


if __name__ == "__main__":
    main()
