# Spendly

A fully local bank statement analyzer. Spendly imports monthly statements from several accounts,
masks sensitive data before storing anything, excludes transfers between your own accounts,
categorizes spending with a local LLM (and asks you when it's unsure), and reports on any period.
Nothing leaves your machine.

**Stack:** Kotlin + Spring Boot + Spring AI · Python (PDF parsing, evaluation) · Angular ·
Ollama (`qwen3:8b`, `nomic-embed-text`) · PostgreSQL + pgvector · Redpanda (Kafka API)

## Repository layout

```
spendly-service/
├── backend/              # Kotlin + Spring Boot (Gradle Kotlin DSL)
├── pdf-parser/           # Python service (pyproject.toml, Dockerfile)
├── eval/                 # Python notebooks + scripts, golden datasets
├── frontend/             # Angular app
├── contracts/            # Event JSON Schemas, PII rules, sanitizer fixtures, OpenAPI draft
├── samples/              # synthetic sample statements (never real data)
├── docker-compose.yml    # postgres+pgvector, redpanda, console (+ pdf-parser)
└── README.md
```

`contracts/` is the single source of truth shared by every service; see
[`contracts/README.md`](contracts/README.md) for Kafka conventions and the sanitizer algorithm.

## Prerequisites

JDK 21, Python 3.12+ with `uv`, Node.js (22 or 24 LTS) with the Angular CLI, Docker, and Ollama with:

```bash
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

## Getting started

```bash
cp .env.example .env          # then set passwords, key paths and the AES key (see comments)
docker compose up -d          # postgres, redpanda, topic init, console
docker compose ps             # postgres and redpanda should be "healthy"
```

Secrets never go in git: `.env` is ignored, and the HMAC key file referenced by
`SPENDLY_HMAC_SECRET_PATH` lives outside the repository.

| Service | URL |
|---|---|
| PostgreSQL | `localhost:5432` (db/user `spendly`) |
| Kafka API (Redpanda) | `localhost:19092` from the host, `redpanda:9092` inside compose |
| Redpanda Console | http://localhost:8088 |
| Ollama (host) | http://localhost:11434 |
| Backend | http://localhost:8080 (Phase 0/1) |
| PDF parser | http://localhost:8090 (Phase 2) |
| Frontend | http://localhost:4200 (`ng serve`, proxies `/api` to 8080) |

## Privacy rules

- Sanitize at the edge: IBANs and person names become HMAC tokens, card numbers are cut to the last 4,
  and CNP, email and phone numbers are removed or masked, before anything reaches Kafka, the DB, logs or the LLM.
- Uploaded originals are deleted after parsing; balances are never persisted.
- `transactions.description` is encrypted at rest (AES-GCM).
- Only synthetic data in `samples/` and `eval/`. The `.gitignore` blocks statement files anywhere else.

## Status

| Phase | Scope | State |
|---|---|---|
| Foundation | layout, compose, contracts, samples | done |
| 0 / 1 | backend: accounts, sanitizer, upload, status machine, outbox, CSV parsers | planned |
| 2 | pdf-parser | planned |
| Frontend setup | shell, accounts, upload, statement page, questions form (mock API) | planned |
| 3–7 | transfers, categorization, reports, chat, eval, polish | later |
