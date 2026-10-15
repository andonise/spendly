"""FastAPI app: GET /health and POST /parse (debug). The Kafka worker runs in a background thread."""

from __future__ import annotations

import logging
import re
import threading
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from python_multipart import MultipartParser
from python_multipart.multipart import parse_options_header

from . import __version__, events
from .contracts import Contracts
from .kafka_worker import StatementWorker
from .log import ids
from .pipeline import StatementContext, parse_pdf
from .sanitizer import Sanitizer
from .settings import Settings

log = logging.getLogger("spendly.pdf_parser.api")

_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_CORRELATION = re.compile(r"^[A-Za-z0-9._:-]{1,100}$")
_CURRENCY = re.compile(r"^[A-Z]{3}$")
NIL_UUID = "00000000-0000-0000-0000-000000000000"


class KafkaHealth:
    """Cached broker reachability check using the shared producer's metadata request."""

    def __init__(self, producer: Any | None, ttl: float = 5.0) -> None:
        self._producer = producer
        self._ttl = ttl
        self._lock = threading.Lock()
        self._checked_at = 0.0
        self._state = "UNKNOWN"

    def state(self) -> str:
        if self._producer is None:
            return "DISABLED"
        with self._lock:
            if time.monotonic() - self._checked_at > self._ttl:
                try:
                    md = self._producer.list_topics(timeout=2.0)
                    self._state = "UP" if md.brokers else "DOWN"
                except Exception:  # noqa: BLE001 - any failure means "not reachable"
                    self._state = "DOWN"
                self._checked_at = time.monotonic()
            return self._state


@dataclass
class Runtime:
    settings: Settings
    contracts: Contracts
    sanitizer: Sanitizer
    worker: StatementWorker | None = None
    kafka_health: KafkaHealth = field(default_factory=lambda: KafkaHealth(None))
    _thread: threading.Thread | None = None

    def start_worker(self) -> None:
        if self.worker is None:
            return
        self._thread = threading.Thread(target=self.worker.run, name="kafka-worker", daemon=True)
        self._thread.start()

    def stop_worker(self, timeout: float = 30.0) -> None:
        if self.worker is None or self._thread is None:
            return
        self.worker.stop()
        self._thread.join(timeout)

    @property
    def worker_alive(self) -> bool | None:
        if self.worker is None:
            return None
        return self._thread is not None and self._thread.is_alive()


async def _read_multipart_in_memory(request: Request, max_bytes: int) -> tuple[dict[str, str], bytes | None]:
    """Parse multipart/form-data fully in memory (never spooled to disk, unlike Starlette's UploadFile)."""
    content_type, params = parse_options_header(request.headers.get("content-type", ""))
    if content_type != b"multipart/form-data" or b"boundary" not in params:
        raise HTTPException(415, "Expected multipart/form-data with a 'file' part")
    declared = request.headers.get("content-length")
    if declared is not None and declared.isdigit() and int(declared) > max_bytes + 64 * 1024:
        raise HTTPException(413, "Upload too large")

    fields: dict[str, str] = {}
    file_data: bytearray | None = None
    state: dict[str, Any] = {"headers": {}, "field": b"", "value": b"", "buf": bytearray(), "size": 0}

    def on_part_begin() -> None:
        state["headers"], state["buf"] = {}, bytearray()

    def on_header_field(data: bytes, start: int, end: int) -> None:
        state["field"] += data[start:end]

    def on_header_value(data: bytes, start: int, end: int) -> None:
        state["value"] += data[start:end]

    def on_header_end() -> None:
        state["headers"][state["field"].decode("latin-1").lower()] = state["value"]
        state["field"], state["value"] = b"", b""

    def on_part_data(data: bytes, start: int, end: int) -> None:
        state["size"] += end - start
        if state["size"] > max_bytes:
            raise HTTPException(413, "Upload too large")
        state["buf"] += data[start:end]

    def on_part_end() -> None:
        nonlocal file_data
        _, disp = parse_options_header(state["headers"].get("content-disposition", b""))
        name = disp.get(b"name", b"").decode("utf-8", errors="replace")
        if name == "file":
            file_data = state["buf"]
        elif name and len(state["buf"]) <= 200:
            fields[name] = state["buf"].decode("utf-8", errors="replace").strip()

    parser = MultipartParser(params[b"boundary"], {
        "on_part_begin": on_part_begin, "on_header_field": on_header_field, "on_header_value": on_header_value,
        "on_header_end": on_header_end, "on_part_data": on_part_data, "on_part_end": on_part_end,
    })
    async for chunk in request.stream():
        parser.write(chunk)
    parser.finalize()
    return fields, bytes(file_data) if file_data is not None else None


def create_app(runtime: Runtime) -> FastAPI:
    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        runtime.start_worker()
        try:
            yield
        finally:
            runtime.stop_worker()

    app = FastAPI(title="Spendly PDF parser", version=__version__, lifespan=lifespan)
    app.state.runtime = runtime

    @app.get("/health")
    async def health() -> JSONResponse:
        kafka = await run_in_threadpool(runtime.kafka_health.state)
        worker_alive = runtime.worker_alive
        status = "UP"
        if worker_alive is False or kafka == "DOWN":
            status = "DEGRADED"
        return JSONResponse({
            "status": status,
            "version": __version__,
            "contractsLoaded": runtime.contracts is not None,
            "kafka": {"state": kafka, "consumerRunning": worker_alive, "consumerGroup": events.CONSUMER_GROUP},
        })

    @app.post(
        "/parse",
        summary="Debug: parse a PDF in memory and return the would-be TransactionsImported event",
        description=(
            "multipart/form-data with a 'file' part (PDF) and optional fields accountId, statementId (UUIDs), "
            "currency (default RON) and correlationId. Nothing is published or stored; no balances are returned."
        ),
        openapi_extra={
            "requestBody": {
                "required": True,
                "content": {"multipart/form-data": {"schema": {
                    "type": "object",
                    "required": ["file"],
                    "properties": {
                        "file": {"type": "string", "format": "binary"},
                        "accountId": {"type": "string"},
                        "statementId": {"type": "string"},
                        "currency": {"type": "string"},
                        "correlationId": {"type": "string"},
                    },
                }}},
            }
        },
    )
    async def parse(request: Request) -> JSONResponse:
        fields, data = await _read_multipart_in_memory(request, runtime.settings.max_upload_bytes)
        if not data:
            raise HTTPException(400, "Missing or empty 'file' part")
        account_id = fields.get("accountId") or NIL_UUID
        statement_id = fields.get("statementId") or str(uuid.uuid4())
        currency = (fields.get("currency") or "RON").upper()
        correlation_id = fields.get("correlationId") or f"debug-parse-{uuid.uuid4().hex[:12]}"
        if not _UUID.match(account_id) or not _UUID.match(statement_id):
            raise HTTPException(422, "accountId and statementId must be lowercase UUIDs")
        if not _CURRENCY.match(currency) or not _CORRELATION.match(correlation_id):
            raise HTTPException(422, "Invalid currency or correlationId")
        ctx = StatementContext(statement_id, account_id, currency, correlation_id)
        outcome = await run_in_threadpool(parse_pdf, data, ctx, runtime.sanitizer, runtime.contracts)
        del data
        log.info("debug parse", extra=ids(outcome=outcome.event_type, reason=outcome.reason,
                                            statementId=statement_id, correlationId=correlation_id))
        return JSONResponse({
            "outcome": outcome.event_type,
            "published": False,
            "event": outcome.event,
            "validation": outcome.summary.as_dict(),
        })

    return app
