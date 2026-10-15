# spendly-pdf-parser

Python service (Phase 2) that turns PDF bank statements into `TransactionsImported` events.
It consumes `StatementUploaded` (`fileType = PDF`) from `spendly.statement-uploaded`, sanitizes every
description first, normalizes the rows, checks `opening + sum(amounts) == closing`, validates the event
against `../contracts/schemas`, publishes it, and then deletes the temp PDF. Failures become
`StatementParseFailed`; invalid input goes to `spendly.statement-uploaded.dlq`.

Contracts (schemas, `pii-rules.json`, sanitizer algorithm, Kafka conventions) live in `../contracts/README.md`.

## Layout

| Module | What it does |
|---|---|
| `settings.py` | env vars (`KAFKA_BOOTSTRAP_SERVERS`, `SPENDLY_UPLOAD_DIR`, `SPENDLY_UPLOAD_RETENTION`, `SPENDLY_HMAC_SECRET_PATH`, `SPENDLY_CONTRACTS_DIR`, `PDF_PARSER_PORT`, `OLLAMA_BASE_URL`, `LOG_LEVEL`); fails fast if the HMAC key or contracts are missing (never prints values) |
| `contracts.py` | loads all schemas into a local `referencing.Registry` (never fetches `spendly.local`) and `pii-rules.json` |
| `sanitizer.py` | the shared sanitizer, exactly as specified in `contracts/README.md` |
| `extraction/` | `LayoutParser` interface + `RomanianCardTableLayout` (pdfplumber, header `Data \| Descriere \| Debit \| Credit`) + Romanian number/date normalization |
| `pipeline.py` | sanitize → normalize → `txHash` / `counterpartyToken` → balance check → schema validation; pure, no I/O |
| `kafka_worker.py` | consumer group `spendly-pdf-parser`, manual commits, retries (1 s, 2 s, 4 s) then DLQ, temp file deletion |
| `api.py` | FastAPI `GET /health`, `POST /parse`; runs the worker in a background thread |
| `log.py` | JSON logs with whitelisted ID fields only |

Adding a layout: implement `extraction.base.LayoutParser` (`detect`, `extract`) and register it in
`extraction.default_layouts()`. A `docling`/`camelot` extractor, or the optional Ollama fallback for
unknown layouts (not implemented; see the docstring in `extraction/base.py`), plugs in there. Its rows
still go through the sanitizer and the balance check.

## Run locally (uv)

Settings come from environment variables; when run from this folder, `../.env` is read as a fallback.
Host processes use `KAFKA_BOOTSTRAP_SERVERS=localhost:19092`.

```bash
cd pdf-parser
uv sync
uv run spendly-pdf-parser          # or: uv run python -m spendly_pdf_parser
# http://localhost:8090/health, http://localhost:8090/docs
```

Stop the compose `pdf-parser` container first if it is running, since both use port 8090 and the same
consumer group. Set `PDF_PARSER_KAFKA_ENABLED=false` to run only the HTTP API (for `/parse`).

## Run in docker compose

```bash
# from the repository root; needs SPENDLY_UPLOAD_DIR and SPENDLY_HMAC_SECRET_PATH in .env
docker compose up -d --build pdf-parser
curl http://localhost:8090/health
```

The image (python 3.12 slim, uv, non-root uid 10001) contains only the code. Contracts are mounted
read-only at `/contracts`, the HMAC key at `/run/secrets/hmac.key`, uploads at `/data/uploads`.
SIGTERM (`docker compose stop`) finishes the current message, closes the consumer and flushes the producer.

## Test

```bash
cd pdf-parser
uv run pytest                       # all tests; the Kafka integration test skips itself if unavailable
uv run pytest -m "not integration"  # unit tests only
```

`tests/test_integration_kafka.py` needs the compose Redpanda on `localhost:19092` and a running
pdf-parser (container or `uv run`). It copies the sample PDF into `SPENDLY_UPLOAD_DIR` under a random
statementId, produces a `StatementUploaded`, waits for the matching `TransactionsImported`, and removes the
file afterwards. If the backend's import consumer is running it will also receive that event, for an
unknown statementId.

## `POST /parse` (debug)

Parses a PDF in memory. The response holds the would-be event and a validation summary. Nothing is
published, the file is never written to disk (multipart is parsed in memory, capped by
`SPENDLY_UPLOAD_MAX_SIZE_MB`), and balances are never returned.

```bash
curl -F "file=@../samples/credit-card-2026-08.pdf" \
     -F "accountId=3f1c2a9e-7b4d-4e21-9a6f-0c5d8e2b1a47" \
     http://localhost:8090/parse
```

Optional form fields: `accountId` (default: the nil UUID), `statementId` (default: random), `currency`
(default `RON`), `correlationId`. The response looks like
`{"outcome": "TransactionsImported" | "StatementParseFailed", "published": false, "event": {...},
"validation": {"balanced": true, "schemaValid": true, "transactionCount": 5, "debitCount": 4,
"creditCount": 1, "debitTotal": "951.00", "creditTotal": "800.00", ...}}`.

## Behaviour notes

- **Header data:** only the `Perioada`, `Moneda`, `Sold initial` and `Sold final` lines are read. Holder,
  address, IBAN and card lines are skipped and never stored. Balances are used only for the
  reconciliation check (AC-SD-6).
- **Failure reasons:** `BALANCE_MISMATCH`, `UNSUPPORTED_LAYOUT` (no known table, unreadable row, missing
  balances, currency differs from `accountCurrency`), `NO_TRANSACTIONS`, `FILE_NOT_FOUND`,
  `FILE_UNREADABLE`, `SCHEMA_VALIDATION`, `INTERNAL_ERROR`. All are published with `retryable: false`
  and a fixed message that has no PII.
- **DLQ:** invalid JSON or schema, an unsafe `tempFilePath`, or exhausted retries. The original
  key/value/headers are kept, and `dlqReason`, `dlqSource=spendly-pdf-parser` and `dlqFailedAt` are added.
- **Retention:** with `PT0S` the PDF is deleted right after the `TransactionsImported` delivery report.
  With a longer retention the deletion is scheduled in memory; on shutdown, pending deletions run
  immediately, because privacy comes first.
- **Redelivery:** offsets are committed only after the publish. If the service crashes between the
  publish and the commit, the message is reprocessed. If the PDF was already deleted, that produces a
  `FILE_NOT_FOUND` failure, which the backend's status guard (`WHERE status = 'UPLOADED'`) ignores.
- Descriptions are capped at 500 characters. An empty sanitized description becomes `N/A`.
