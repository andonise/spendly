# Spendly contracts

Everything the services agree on lives here. **Services read these files; they never copy or edit them.**
A service that needs a change proposes it (what, why, which consumers are affected) and the change is
made here first, then in every service at once.

| File | What it is | Used by |
|---|---|---|
| `schemas/envelope.schema.json` | Envelope every Kafka message uses | all producers/consumers |
| `schemas/common.schema.json` | Shared definitions (uuid, dates, decimal amount, currency, category, parser) | all schemas |
| `schemas/statement-uploaded.schema.json` | `StatementUploaded` event | backend → CSV parser, pdf-parser |
| `schemas/transactions-imported.schema.json` | `TransactionsImported` event | CSV parser, pdf-parser → backend |
| `schemas/statement-parse-failed.schema.json` | `StatementParseFailed` event | CSV parser, pdf-parser → backend |
| `examples/*.json` | One valid example per event (validated in CI-style check below) | tests, docs |
| `pii-rules.json` | Sanitizer rules (data) | backend + pdf-parser sanitizers |
| `fixtures/sanitizer-cases.json` | Inputs with exact expected sanitized output | both sanitizer test suites (AC-SD-4) |
| `openapi-draft.yaml` | Hand-written REST draft (plan section 15) | frontend, until springdoc publishes the real spec |

Schemas are JSON Schema 2020-12. `$ref`s are relative (`common.schema.json#/$defs/uuid`); each schema
has an `$id` under `https://spendly.local/contracts/schemas/`, so validators must load all files of
`schemas/` into one registry (Python `referencing.Registry`, networknt `SchemaRegistry`/URI mapping)
and resolve `$id`s locally. **Never fetch `spendly.local` over the network.**

## Kafka conventions

| Topic | Event | Key | Producer | Consumer group(s) |
|---|---|---|---|---|
| `spendly.statement-uploaded` | `StatementUploaded` | accountId | backend (outbox) | `spendly-backend-csv-parser`, `spendly-pdf-parser` |
| `spendly.transactions-imported` | `TransactionsImported` | accountId | CSV parser, pdf-parser | `spendly-backend-import` |
| `spendly.statement-parse-failed` | `StatementParseFailed` | accountId | CSV parser, pdf-parser | `spendly-backend-import` |
| `<topic>.dlq` | original message, unchanged | same key | whoever gave up on it | `spendly-backend-dlq` (moves the statement to `FAILED`) |

Topics are created by the `redpanda-init` service in `docker-compose.yml` (3 partitions, 24 h retention).

- **Value:** the envelope as UTF-8 JSON. Producers validate against the schema before sending;
  consumers validate on receipt and send invalid messages straight to the DLQ (no retries).
- **Headers** (UTF-8 strings): `eventId`, `eventType`, `schemaVersion`, `correlationId`. They mirror
  the envelope; the envelope wins if they ever differ. DLQ messages add `dlqReason`, `dlqSource`
  (`spendly-backend` / `spendly-pdf-parser`), and `dlqFailedAt`; never put descriptions or amounts in them.
- **Ownership by file type:** both parsers read `spendly.statement-uploaded`. The CSV parser handles
  `CSV`, `XLS`, `XLSX`; the pdf-parser handles `PDF`. A consumer that receives a file type it does not own
  skips it (the backend still records it in `processed_events`).
- **Idempotency:** consumers record `(eventId, consumer)` in `processed_events` in the same DB
  transaction as their effect. The pdf-parser has no DB: it is idempotent by construction (same input
  file → same `TransactionsImported` content), commits its Kafka offset only after publishing, and
  the backend import handler dedupes on `statementId` + status guard (`UPDATE … WHERE status = 'UPLOADED'`).
- **Retries:** transient errors get the initial attempt plus 3 retries (after 1 s, 2 s, 4 s; 4 attempts in
  total), then go to the DLQ. Business failures (balance mismatch, unsupported layout) are **not** retried:
  publish `StatementParseFailed` instead.
- **`dlqReason` values:** `INVALID_JSON` (value is not JSON), `SCHEMA_VALIDATION` (fails its schema),
  `UNSAFE_TEMP_FILE_PATH` (`tempFilePath` escapes the upload folder; this goes to the DLQ, not to
  `StatementParseFailed`), `RETRIES_EXHAUSTED` (transient error after all retries). The backend DLQ
  consumer moves the statement to `FAILED` (only from `UPLOADED`) when the message carries a known
  `statementId`; otherwise it only logs the IDs.
- **Unknown statement:** an event for a `statementId` the backend doesn't have is logged (IDs only),
  recorded in `processed_events`, and skipped. It is not dead-lettered.
- **Logs** contain IDs only (eventId, statementId, accountId, correlationId), never descriptions,
  amounts or tokens.

### Upload folder and `tempFilePath`

The backend saves each upload as `SPENDLY_UPLOAD_DIR/<statementId>.<ext>` (lowercase extension,
never the original filename) and sends `tempFilePath = "<statementId>.<ext>"`, relative to the upload
folder. Each service resolves it against its own `SPENDLY_UPLOAD_DIR` (the host path for the backend,
`/data/uploads` inside the pdf-parser container). Resolve, normalize, and reject any path that escapes
the upload folder. The parser that successfully imported a file deletes it (immediately by default,
`SPENDLY_UPLOAD_RETENTION`), so it's deleted by the CSV parser for CSV/XLS(X), by the pdf-parser for PDF.

### Transaction hash (`txHash`)

Dedupe key for a transaction (re-uploading the same or an overlapping statement is safe):

```
txHash = lowercase hex SHA-256 of UTF-8 bytes of
         accountId | bookingDate | amount | currency | description | occurrence
```

- Fields joined with `|`; `amount` is the canonical decimal string (`-45.20`); `description` is the
  **final** description exactly as sent in the event (see "Description" below); `occurrence` is the 0-based count of earlier rows in the **same file** with the
  same `(bookingDate, amount, currency, description)`. Two identical coffees on one day hash differently,
  and the same day re-imported from another file produces the same two hashes.
- The backend inserts with `ON CONFLICT (account_id, tx_hash) DO NOTHING`.

### Description

Both parsers build the event `description` (and hash it) the same way:

1. `s = sanitize(raw).text`
2. If `s` is longer than 500 **Unicode code points**, keep the first 500 code points (Kotlin: use
   `codePointCount`/`offsetByCodePoints`, not `String.length`, which counts UTF-16 units).
3. Trim leading/trailing spaces.
4. If the result is empty, use `N/A`.

A fee row's description is `"FEE " + <final description of the main row>`, then steps 2–4 again.
A fee row's `counterpartyToken` and `counterpartyTokenKind` are always `null` (the fee goes to the bank).

### Amounts

- Signed: negative = money leaving the account, positive = money arriving.
- Exactly 2 fraction digits, `.` separator, no thousands separators, as a JSON **string**.
- A fee shown in a separate column (e.g. Revolut `Fee`) becomes its own transaction directly after the
  main one, with the description `FEE <sanitized description>`, the same dates, and the fee as a negative amount.
- Opening/closing balances are used only to validate parsing and are **never** put in an event (AC-SD-6).

## Sanitizer algorithm

Both sanitizers implement exactly this; `fixtures/sanitizer-cases.json` is the proof (AC-SD-4).

```
sanitize(text) -> { text, tokens: [ { ruleId, token } ] }
```

1. **Normalize the input.** Apply Unicode NFC, replace every character in `normalization.whitespaceToSpace`
   with a space, collapse runs of spaces to one, then trim.
2. **Apply the rules in file order** (`iban`, `cnp`, `pan`, `email`, `phone`, `person_name`). Each rule scans
   the *current* text left to right for non-overlapping matches of `pattern`. This is the same as
   Python `re.sub(pattern, fn, text)` and a Java `Matcher.find()`/`appendReplacement` loop. Scanning
   resumes after the end of each match, whether or not it was replaced. For each match:
   - **`iban`** (`validator: iban_mod97`, `shrinkOnFailure: space_chunks`): split the match on `" "` into
     chunks. For `n` = chunk count down to 1, let `s` = the first `n` chunks joined with no separator. The first
     `s` that matches `^[A-Z]{2}[0-9]{2}[A-Z0-9]+$`, has length 15–34, and passes ISO 7064 mod-97
     (move the first 4 chars to the end, letters → 10..35, number mod 97 = 1) wins. Replace the first `n`
     chunks (as they appear, with their spaces) by `{country}**…{last4}` (`…` is U+2026), keep the rest
     of the match as is, and emit token `HMAC("iban:" + s)`. If no `n` passes, leave the match unchanged.
   - **`cnp`** (`cnp_checksum`): weights `2,7,9,1,4,6,3,5,8,2,7,9` over the first 12 digits; `r = sum mod 11`;
     the control digit is `1` if `r == 10`, else `r`; it must equal digit 13. Valid → replace with `""`.
   - **`pan`** (`luhn`, `space_chunks`): same chunk shrinking as `iban`, with `s` = the digits; valid when
     13 ≤ length ≤ 19 and Luhn passes. Replace the first `n` chunks with `****{last4}`, keep the rest.
   - **`email`, `phone`**: replace with the rule's `replacement` (no validator).
   - **`person_name`** (`person_name`): `keyword` = group 1, `name` = group 2. Split `name` on spaces into
     words. `norm(w)` = NFKD, drop all combining marks (Unicode category `Mn`), uppercase (locale-independent),
     remove every `.`. Truncate the word list at the first word whose `norm` is in `stopWords`. Those
     words and everything after them are the `tail`. If fewer than `minWords` words remain, or any remaining word's
     `norm` is in `companyWords`, leave the match unchanged. Otherwise `canonical` = remaining `norm`s
     joined with one space, token = `HMAC("person:" + canonical)`, and the match becomes
     `keyword + " [PERSON#" + token[0:8] + "]"` + (`" " + tail words joined by " "` if any).
3. **Clean up the output.** Collapse runs of spaces to one and trim.
4. **Return the tokens.** `tokens` contains one entry per `token`-action replacement, in the order made
   (rule order, then left to right). `token` is the full 64-char lowercase hex
   HMAC-SHA256 (key: the trimmed UTF-8 content of the file at `SPENDLY_HMAC_SECRET_PATH`;
   in the fixtures, `hmacSecret`).

`counterpartyToken` in `TransactionsImported` = the first `iban` token, else the first `person_name`
token, else `null` (with `counterpartyTokenKind` `IBAN`/`PERSON`/`null`). Own-IBAN detection compares it
with `accounts.iban_hash`, which uses the same `HMAC("iban:" + compactIban)`.

Known limitations (documented, covered by fixtures): names must start with a capital letter; names
longer than 3 words are cut to the first 3; a 13-digit Luhn-valid number that is also a valid CNP
is removed (the safer outcome).

Regex portability rules: patterns avoid `\d`, `\w`, `\s`, `\b` (Unicode semantics differ between Java and
Python) and named groups. They use only scoped inline flags `(?i:...)`. Compile with no global flags.
Read `pii-rules.json` as UTF-8.

## Validating the contracts

```bash
# from the repository root
uv run --with jsonschema --with referencing python contracts/tools/validate_examples.py
```
