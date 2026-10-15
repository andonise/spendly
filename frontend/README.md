# Spendly frontend (Angular)

Angular 21 (standalone components, signals, zoneless), Angular Material 21 (M3 theme that follows
the OS light/dark preference), ngx-echarts 21 + ECharts 6. All versions are pinned exactly in
`package.json`.

Until the Kotlin backend runs, the app works against an **in-memory mock API** that implements
`../contracts/openapi-draft.yaml`. All mock data is synthetic (the fictional accounts from
`../samples/README.md`).

## Run

```bash
npm ci                                   # Node 24 / npm 11
npm start                                # = ng serve; all endpoint groups mocked → http://localhost:4200
npm run start:real                       # = ng serve --configuration real; everything goes to the backend
```

Try the flow: **Upload** → pick an account and any `.csv`/`.xls(x)`/`.pdf` file → the statement page
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

A mocked group is answered from memory. Any other group is passed through to the backend.
The switches live in `src/environments/`:

| File | Used by | `mock` |
|---|---|---|
| `environment.ts` | `ng serve` (development), `ng test` | all `true` |
| `environment.real.ts` | `ng serve --configuration real`, `ng build --configuration real` | all `false` |
| `environment.prod.ts` | `ng build` (production) | all `false` |

To move one group to the real backend (e.g. in Stage 3), set it to `false` in `environment.ts`:

```ts
mock: { accounts: false, statements: true, questions: true },
```

The state is shared in memory, so a half-mocked setup only makes sense for independent groups.
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
│   ├── api/          # THE SEAM: AccountsApi, StatementsApi, QuestionsApi (method = operationId), index.ts
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
    ├── questions/    # human-in-the-loop questions form
    └── dashboard/ transactions/ learning/ chat/   # placeholders (later phases)
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
