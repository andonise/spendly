# Spendly core (Kotlin, Spring Boot)

The core service in `apps/core/`: accounts, upload and statement lifecycle, CSV parsing, transfer
detection, categorization (rules, embeddings, local LLM) and the REST API for `apps/web`.

```bash
./gradlew bootRun                     # http://localhost:8080 (needs the compose stack: postgres, redpanda)
./gradlew test                        # unit + integration tests (Testcontainers) + module boundaries
./gradlew evalCategorization          # categorization accuracy on eval/golden (-Pgolden=<csv>, -PllmOff=true)
```

## Structure: modular monolith

Each top-level package under `dev.spendly` is a module. A module's **root package is its public API**;
its subpackages (`web`, `domain`, `persistence`, `messaging`, …) are internal to it. Other modules may only
use root-package types.

| Module | Owns | Public API (root package) | Uses |
|---|---|---|---|
| `categorization` | rules → embeddings → LLM, questions and answers, learning | (none; REST and Kafka only) | statement, transfer, transaction |
| `transfer` | transfer detection, pair matching, counterparties | `TransferApi` | transaction |
| `statement` | upload, lifecycle (state machine), CSV parsing, import, status SSE | `StatementLifecycle`, `StatementViews`, `StatementRecord`, `StatementStatus` | account, transaction, fx |
| `transaction` | the ledger: transactions, counterparty data, categories, totals | `Category`, `TransferStatus`, `CounterpartyRef`, `StatementSummaryService`, `MerchantNormalizer`, `KeywordRules` | – |
| `account` | bank accounts | `AccountApi`, `AccountRef`, `AccountUsage` (implemented by statement) | – |
| `fx` | BNR exchange rates | `FxService` | – |
| `shared` | config, crypto, privacy (sanitizer, log masking), messaging (event contracts, Kafka, DLQ), outbox, web errors, observability | everything (an **open** module) | – |

Every module may use `shared`; `shared` uses no feature module. Dependencies form a cycle-free graph.

Rules of thumb:
- A new feature (e.g. Phase 4 reporting) is a new top-level package with the same layout.
- Need something from another module? Use or extend its root-package API, or react to its Kafka event.
  Never import another module's `domain`/`persistence` classes.
- If a lower module would need to call a higher one, invert it: the lower module declares an interface in
  its root package and the higher one implements it (see `AccountUsage`).

`architecture/ModularityTest` enforces this with [Spring Modulith](https://spring.io/projects/spring-modulith):
the build fails on access to another module's internals or on a cycle. The same test writes PlantUML
diagrams of the modules to `build/spring-modulith-docs/`.
