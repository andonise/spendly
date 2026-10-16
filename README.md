# Spendly

A fully local bank statement analyzer. Spendly imports monthly statements from several accounts,
masks sensitive data before storing anything, excludes transfers between your own accounts,
categorizes spending with a local LLM (and asks you when it's unsure), and reports on any period.
Nothing leaves your machine.

**Stack:** Kotlin + Spring Boot + Spring AI · Python (PDF parsing, evaluation) · Angular ·
Ollama (`qwen3:8b`, `nomic-embed-text`) · PostgreSQL + pgvector · Redpanda (Kafka API)

## Repository layout

One repository, three apps, and the material they share:

```
spendly/
├── apps/
│   ├── core/             # Kotlin + Spring Boot: the core service (Gradle Kotlin DSL)
│   ├── web/              # Angular app
│   └── pdf-parser/       # Python service: PDF statements → transactions (uv, Dockerfile)
├── contracts/            # shared agreements: OpenAPI draft, event JSON Schemas, PII rules, sanitizer fixtures
├── eval/                 # golden datasets for measuring categorization (eval toolkit: Phase 6)
├── samples/              # synthetic sample statements with their expected results (never real data)
├── docker-compose.yml    # postgres+pgvector, redpanda, console, pdf-parser
└── README.md
```

| Folder | Read more |
|---|---|
| `apps/core` | [`apps/core/README.md`](apps/core/README.md): modules (modular monolith) and their rules |
| `apps/web` | [`apps/web/README.md`](apps/web/README.md): pages, mock API, tests |
| `apps/pdf-parser` | [`apps/pdf-parser/README.md`](apps/pdf-parser/README.md): parsing, Kafka worker, tests |
| `contracts` | [`contracts/README.md`](contracts/README.md): Kafka conventions, sanitizer algorithm |
| `eval` | [`eval/golden/README.md`](eval/golden/README.md): metric, target, current results |
| `samples` | [`samples/README.md`](samples/README.md): the fictional accounts and expected totals |

`contracts/` is the single source of truth: the apps read it and never copy it. A change is made
there first, then in every app that uses it.

## Prerequisites

JDK 21, Python 3.12+ with `uv`, Node.js (22 or 24 LTS) with the Angular CLI, Docker, and Ollama with:

```bash
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

## Getting started

```bash
cp .env.example .env          # then set passwords, key paths and the AES key (see comments)
docker compose up -d          # postgres, redpanda, topic init, console, pdf-parser
docker compose ps             # postgres and redpanda should be "healthy"

cd apps/core && ./gradlew bootRun          # core service on :8080 (reads ../../.env)
cd apps/web && npm ci && npm start         # web app on :4200, proxies /api to :8080
```

Secrets never go in git: `.env` is ignored, and the HMAC key file referenced by
`SPENDLY_HMAC_SECRET_PATH` lives outside the repository.

| Service | URL |
|---|---|
| Web app | http://localhost:4200 (`npm start`, proxies `/api` to 8080) |
| Core service | http://localhost:8080 (OpenAPI UI at `/swagger-ui.html`) |
| PDF parser | http://localhost:8090 (`/health`) |
| PostgreSQL | `localhost:5432` (db/user `spendly`) |
| Kafka API (Redpanda) | `localhost:19092` from the host, `redpanda:9092` inside compose |
| Redpanda Console | http://localhost:8088 |
| Ollama (host) | http://localhost:11434 |

## Tests

```bash
cd apps/core && ./gradlew test                 # unit + integration (Testcontainers) + module boundaries
cd apps/core && ./gradlew evalCategorization   # categorization accuracy on eval/golden
cd apps/web && npm test                        # Vitest
cd apps/pdf-parser && uv run pytest
uv run --with jsonschema --with referencing python contracts/tools/validate_examples.py   # contract examples
```

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
| 0 / 1 | core: accounts, sanitizer, upload, status machine, outbox, CSV parsers | done |
| 2 | pdf-parser | done (one PDF layout) |
| Web | shell, accounts, upload, statement page, questions, transactions, counterparties | done |
| 3 | transfer detection, categorization (rules → embeddings → LLM), questions and learning | done; holdout accuracy 84.2%, below the 90% target (see `eval/golden/README.md`) |
| 4–7 | reports, chat, eval toolkit, polish | next |
