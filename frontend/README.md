# Spendly frontend (Angular)

Angular 21 (standalone components, signals, zoneless), Angular Material 21 (M3 theme that follows
the OS light/dark preference), ngx-echarts 21 + ECharts 6. All versions are pinned exactly in
`package.json`.

`npm start` talks to the Kotlin backend (http://localhost:8080) through the dev proxy. An
**in-memory mock API** that implements `../contracts/openapi-draft.yaml` can stand in for it per
endpoint group (see below), and the unit tests always use it. All mock data is synthetic (the fictional accounts from
`../samples/README.md`).

## Run

```bash
npm ci                                   # Node 24 / npm 11
npm start                                # = ng serve; real backend via the proxy → http://localhost:4200
npm run start:real                       # = ng serve --configuration real; everything goes to the backend
```

## Pages

| Route | Page |
|---|---|
| `/accounts` | List and register accounts (IBAN shown masked only) |
| `/upload` | Drag-and-drop upload with account selector |
| `/statements`, `/statements/:id` | Statements list; status-driven statement page (progress → questions form → summary with “N internal transfers excluded” and **View transactions** → `/transactions?statementId=…`) |
| `/questions` | All open questions (toolbar badge “N questions need your input”). Statement questions link to their statement page; TRANSFER questions are answered here: **Yes, it's my transfer** (`INTERNAL_TRANSFER`), **No** (category picker), **Skip**, plus “Always treat this counterparty as my own account” (`applyToFuture`, sent with Yes; only shown when the transaction has a counterparty). One request per card; 409 → message + refresh. `?focus=<questionId>` highlights a card. |
| `/transactions` | Material table, server-side paging + sorting (date, amount), filters in the URL (`accountId`, `statementId`, `from`, `to`, `category`, `transferStatus`, `page`, `size`, `sort`). “Transfer” badge on INTERNAL rows; “Unconfirmed transfer” on CANDIDATE rows (links to `/questions?focus=…`); “Label counterparty” row action. Inline category editing is Phase 4 (disabled edit icon). |
| `/counterparties` | Counterparties (label, kind, own account, count, last seen, example) with the same label dialog. Tokens are shown only as a 6-character prefix. |
| `/dashboard`, `/learning`, `/chat` | Placeholders (later phases) |

**Transfer pair link:** clicking “Transfer” fetches the paired transaction (`GET /api/transactions/{id}`)
and navigates to `/transactions?accountId=<pair account>&from=<pair date>&to=<pair date>&highlight=<pair id>`
(other filters cleared). The narrow filter guarantees the pair is on page 1 with server paging; the
row is highlighted and a banner offers “Show all transactions”.

Try the flow on the mock (set every group to `true`): **Upload** → pick an account and any `.csv`/`.xls(x)`/`.pdf` file → the statement page
shows progress → 3 questions → answer/skip → summary. File names containing `fail` end in `FAILED`
(Retry works). Uploading the same file (same name + size) to the same account again returns the
existing statement (`duplicate: true`).

## Build and test

```bash
npm run build                            # = ng build (production config, real backend)
npm test                                 # = ng test --watch=false
```

Unit tests use the Angular CLI's default runner, **Vitest** with **jsdom** in Node; no browser
is needed, so they run headless in CI. `src/test-setup.ts` stubs the few browser APIs that jsdom
lacks (`ResizeObserver`, `matchMedia`).

## Mock vs real backend (per endpoint group)

The mock is an HTTP interceptor (`core/mock/mock-api.interceptor.ts`). For every `/api/...`
request it looks up the endpoint group, which is the same as the OpenAPI tag:

| Group        | Endpoints |
|--------------|-----------|
| `accounts`   | `/api/accounts`, `/api/accounts/{id}` |
| `statements` | `/api/statements`, `/{id}`, `/{id}/retry`, `/{id}/events` |
| `questions`  | `/api/statements/{id}/questions`, `/{id}/answers`, `/api/questions`, `/api/questions/{id}/answer` |
| `transactions` | `/api/transactions`, `/api/transactions/{id}` |
| `counterparties` | `/api/counterparties`, `/api/counterparties/{token}` |

A mocked group is answered from memory. Any other group is passed through to the backend.
The switches live in `src/environments/`:

| File | Used by | `mock` |
|---|---|---|
| `environment.ts` | `ng serve` (development) | all `false` (flip a group to `true` to mock it) |
| `environment.real.ts` | `ng serve --configuration real`, `ng build --configuration real` | all `false` |
| `environment.prod.ts` | `ng build` (production) | all `false` |

Unit tests use `provideMockApiForTests()` (`core/mock/testing.ts`), which mocks every group.
To work on the UI without the backend, set groups to `true` in `environment.ts`:

```ts
mock: { accounts: true, statements: true, questions: true, transactions: true, counterparties: true },
```

The state is shared in memory, so a half-mocked setup only makes sense for independent groups.
(With `questions`/`transactions` mocked and `statements` real, the mock's seeded statements and
transactions don't match what the real backend returns, so flip these groups together.)

**Mock seed (August 2026, synthetic, from `../samples/README.md`):** three statements (Generic
Bank current: CATEGORIZED; credit card: CATEGORIZED; Revolut: NEEDS_INPUT with 2 category
questions), 32 transactions. The BT −500 ↔ Revolut +500 top-up and the −800/+800 card repayment
are INTERNAL pairs (`pairedTransactionId` on both sides); one Revolut “Top-Up from RO**…7788” is
CANDIDATE with an open TRANSFER question; the 2200 rent is EXTERNAL to the counterparty
“Landlord”; the rest have mixed `categorySource` (RULE/EMBEDDING/LLM/USER/TRANSFER_MATCH) and
confidence. Transfer semantics follow the contract: `PUT /api/counterparties/{token}` with
`isOwnAccount: true` turns that counterparty's CANDIDATE/NONE rows INTERNAL (and back when unset;
an auto-resolved TRANSFER question reopens); a TRANSFER answer `INTERNAL_TRANSFER` → INTERNAL,
another category → EXTERNAL with it, skip → EXTERNAL + OTHER, `applyToFuture` → the counterparty
becomes an own account. Statement summaries are recomputed and stay absolute (INTERNAL and
CANDIDATE excluded from totals).
`mockOptions.latencyMs` simulates network latency, and `mockOptions.stepMs` sets how long each
pipeline step takes (UPLOADED → PARSED → CATEGORIZING → NEEDS_INPUT, and CATEGORIZING →
CATEGORIZED after answers). The simulation is lazy: transitions are applied when a statement is
read, using an injectable clock (`MOCK_CLOCK`), so tests can drive it deterministically.

## Proxy and status updates

`proxy.conf.json` forwards `/api` (including `GET /api/statements/{id}/events`, SSE) to
`http://localhost:8080`. `ng serve` uses it automatically. The Vite dev-server proxy streams
responses, and timeouts are disabled so long-lived SSE connections stay open.

`core/sse/statement-events.service.ts` follows a statement's status:

- against the real backend it uses `EventSource`, reconnects twice, then falls back to polling
  `GET /api/statements/{id}` every 2 s;
- when `statements` is mocked it polls only, because `EventSource` bypasses `HttpClient` and its
  interceptors, so it can't reach the mock;
- it completes on `CATEGORIZED` / `FAILED`.

`core/sse/sse-fetch.ts` (`postEventStream`) is the `fetch` + `ReadableStream` helper for future
POST streams (chat, regenerate recommendations).

## Structure

```
src/app/
├── core/
│   ├── api/          # THE SEAM: Accounts/Statements/Questions/Transactions/CounterpartiesApi (method = operationId), index.ts
│   ├── models/       # TypeScript types for the OpenAPI schemas
│   ├── config/       # APP_ENV injection token (environment, overridable in tests)
│   ├── http/         # error interceptor (RFC 7807 → snackbar), problem helpers
│   ├── sse/          # status events service (SSE + polling fallback), POST stream helper
│   ├── mock/         # in-memory mock API: interceptor, backend, seed data, test helpers
│   ├── state/        # OpenQuestionsStore (toolbar badge)
│   └── charts/       # tree-shaken, lazily loaded ECharts build
├── shared/           # money pipe, IBAN validator, category chip, status chip, progress, empty state
└── features/
    ├── accounts/     # list + register (client mod-97), close/delete, "Delete all my data" placeholder
    ├── upload/       # drag-and-drop + picker, account selector, duplicate handling
    ├── statement/    # statements list + status-driven statement page + summary chart
    ├── questions/    # statement questions form + /questions page (TRANSFER cards)
    ├── transactions/ # paged/sorted/filtered table, transfer badges, label counterparty
    ├── counterparties/ # list + label/own-account dialog (shared with transactions)
    └── dashboard/ learning/ chat/   # placeholders (later phases)
```

Every feature route is lazy-loaded (`app.routes.ts`).

Error handling: every HTTP error shows a snackbar with the problem's `title: detail`. A caller that
handles a status itself passes `{ context: handledErrors(409) }`. The questions form does this for
409, shows a friendly message, and reloads the statement status.

Privacy: the IBAN is held only in the registration form control until it is sent. The form is
reset after saving, and only `ibanMasked` is ever displayed or kept in state.

## Swapping in openapi-generator later

Features import API classes and types only from `core/api` (`index.ts`). The hand-written classes
already use the generator's conventions: one service per tag, method names = `operationId`s, and
an optional last `options` argument. To switch:

1. Generate a client once the backend publishes its spec at `/v3/api-docs`:
   ```bash
   npx @openapitools/openapi-generator-cli generate -g typescript-angular \
     -i http://localhost:8080/v3/api-docs -o src/app/core/api/generated \
     --additional-properties=ngVersion=21,providedIn=root,stringEnums=true
   ```
   Pin the generator version in `openapitools.json`.
2. Re-export the generated services and models from `core/api/index.ts` under the current names
   (`export { AccountsService as AccountsApi } …`), or keep thin wrappers that delegate to them.
3. Keep `api-options.ts` (`handledErrors`) and pass `context` through the generated methods'
   `options` argument.
4. Delete `core/models/api.models.ts` once nothing imports it. The mock API keeps working because
   it intercepts HTTP calls, not services.
