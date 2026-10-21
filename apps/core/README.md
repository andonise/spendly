# Spendly core (Kotlin, Spring Boot)

The core service in `apps/core/`: accounts, upload and statement lifecycle, CSV parsing, transfer
detection, categorization (rules, embeddings, local LLM), period reports and the REST API for `apps/web`.

```bash
./gradlew bootRun                     # http://localhost:8080 (needs the compose stack: postgres, redpanda)
./gradlew check                       # tests (Testcontainers) + module boundaries + coverage gate (80% of lines)
./gradlew evalCategorization          # categorization accuracy on eval/golden (-Pgolden=<csv>, -PllmOff=true,
                                      #   -PminAccuracy=0.95 -PminCoverage=0.75 to fail below a floor)
```

## Structure: modular monolith

Each top-level package under `dev.spendly` is a module. A module's **root package is its public API**;
its subpackages (`web`, `domain`, `persistence`, `messaging`, …) are internal to it. Other modules may only
use root-package types.

| Module | Owns | Public API (root package) | Uses |
|---|---|---|---|
| `report` | daily aggregates, coverage, period reports, insight rules | (none; REST and Kafka only) | – (reads the ledger tables read-only in SQL) |
| `categorization` | rules → embeddings → LLM, questions and answers, learning | (none; REST and Kafka only) | statement, transfer, transaction |
| `transfer` | transfer detection, pair matching, counterparties | `TransferApi` | transaction |
| `statement` | upload, lifecycle (state machine), CSV parsing, import, status SSE | `StatementLifecycle`, `StatementViews`, `StatementRecord`, `StatementStatus` | account, transaction, fx |
| `transaction` | the ledger: transactions, counterparty data, categories, totals, manual category corrections | `Category`, `TransferStatus`, `CounterpartyRef`, `StatementSummaryService`, `MerchantNormalizer`, `KeywordRules`, `CategoryLearning` (implemented by categorization) | – |
| `account` | bank accounts | `AccountApi`, `AccountRef`, `AccountUsage` (implemented by statement) | – |
| `fx` | BNR exchange rates | `FxService` | – |
| `shared` | config, crypto, privacy (sanitizer, log masking), messaging (event contracts, Kafka, DLQ), outbox, web errors, observability | everything (an **open** module) | – |

Every module may use `shared`; `shared` uses no feature module. Dependencies form a cycle-free graph.

Rules of thumb:
- A new feature is a new top-level package with the same layout (like `report` in Phase 4).
- Need something from another module? Use or extend its root-package API, or react to its Kafka event.
  Never import another module's `domain`/`persistence` classes.
- If a lower module would need to call a higher one, invert it: the lower module declares an interface in
  its root package and the higher one implements it (see `AccountUsage`).

`architecture/ModularityTest` enforces this with [Spring Modulith](https://spring.io/projects/spring-modulith):
the build fails on access to another module's internals or on a cycle. The same test writes PlantUML
diagrams of the modules to `build/spring-modulith-docs/`.

## Reports (Phase 4)

`TransactionsChanged` → **aggregator** (one transaction: delete + insert `daily_aggregates` for the changed accounts and
dates under per-account advisory locks, overlapping reports → `STALE` with `version + 1`, `ReportRequested` for the
ones rebuilt eagerly) → **report builder** (one read-only `REPEATABLE READ` snapshot; saved only while
`reports.version` is unchanged).

- Monthly reports are rebuilt eagerly and created automatically for every month that gets its first covered day;
  day, week and custom reports are built on request and rebuilt on their next view (stale-while-revalidate).
- Insight rules are small classes in `report/domain/InsightRules.kt`, each declaring the scales it supports
  (DAY = 1 day, WEEK = 2–27 days, MONTH = 28+ days). Thresholds live under `spendly.reports.*`.
- Comparison rules (category spikes) need at least `min-baseline-periods` (2) previous periods with `COMPLETE`
  coverage out of the 4 days / 4 weeks / 3 months looked at; otherwise the report says "Not enough history yet".
- Statements categorized before Phase 4 are aggregated once on startup (`AggregateBackfill`).
