# Sample statements (synthetic, never real data)

Every person, address, IBAN and card number here is fictional. IBANs use made-up bank codes
(`SPND`, `FAKE`, `TEST`) but have valid mod-97 check digits, and card numbers are standard Luhn-valid
test numbers, so the sanitizer must treat them as real. Regenerate with:

```bash
uv run --no-project --with reportlab==4.2.5 python samples/tools/generate_samples.py
```

## Accounts these files belong to

| Account | Type | Currency | IBAN (register with this) | Card |
|---|---|---|---|---|
| Generic Bank current | `CURRENT` | RON | `RO54 SPND 0000 1111 2222 3333` | `****1111` |
| Revolut | `E_WALLET` | RON | `RO32 FAKE 0000 4444 5555 6666` | none |
| Generic Bank credit card | `CREDIT_CARD` | RON | `RO45 SPND 0000 7777 8888 9999` | `****5556` |

Third-party IBAN (landlord): `RO38 TEST 7777 8888 9999 0000`.

## Files and expected parser results

| File | Format | Period | Txns | Debits | Credits | Opening | Closing |
|---|---|---|---|---|---|---|---|
| `generic-bank-2026-08.csv` | generic bank CSV | 2026-08-01 – 2026-08-31 | 18 | 6393.56 | 8650.00 | 5000.00 | 7256.44 |
| `revolut-2026-08.csv` | Revolut CSV | 2026-08-11 – 2026-08-28 * | 8 ** | 534.54 | 500.00 | 250.00 | 215.46 |
| `credit-card-2026-08.pdf` | text PDF | 2026-08-01 – 2026-08-31 | 5 | 951.00 | 800.00 | -1200.00 | -1351.00 |

\* Revolut exports have no period header, so the period is min/max booking date (the date part of
`Completed Date`; for rows without one, `Started Date`).
\*\* 7 completed rows + 1 separate fee row (ATM fee 5.00). The `REVERTED` Uber row is ignored.

Debits and credits are absolute sums; `opening + credits − debits = closing` for every file.

### generic-bank-2026-08.csv

- `;`-separated, UTF-8, Romanian number format (`1.249,99`), dates `dd.MM.yyyy`.
- An 8-line preamble (holder, address, own IBAN, period, opening balance) precedes the header row
  `Data tranzactie;Data valuta;Descriere;Debit;Credit;Sold`. The preamble is never imported, except
  that the period may be read from the `Perioada` line. A trailing `Sold final` line follows the rows.
- Unsigned `Debit` / `Credit` columns: debit becomes a negative amount.
- Includes a raw card number (2 rows), the landlord IBAN plus a person name, the own Revolut IBAN, the
  credit-card IBAN, a phone number, diacritics, two identical Starbucks rows on one day (distinct `txHash`
  via `occurrence`), and an incoming transfer from a person.

### revolut-2026-08.csv

- Real Revolut column layout: `Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance`.
- Signed `Amount` with `.` decimals; `Fee` is separate and becomes its own `FEE …` row.
- Only `State = COMPLETED` rows are imported.

### credit-card-2026-08.pdf

- One page. The header (holder, address, IBAN, full card number) must **never** be extracted.
- One ruled table `Data | Descriere | Debit | Credit` plus a `Total` row (not a transaction).
- `Sold initial` / `Sold final` lines are used only to validate: opening + credits − debits = closing.

## Scenarios they enable (later phases)

- **AC-MA-2 / AC-MA-3:** −500.00 from Generic Bank on 10 Aug ↔ +500.00 Revolut top-up on 11 Aug.
- **AC-MA-5:** 800.00 of card purchases (Altex + Decathlon) and an 800.00 repayment (15 Aug current → 16 Aug card).
- **AC-MA-6:** 146.00 card interest on 25 Aug.
- **AC-MA-7:** 2200.00 rent to a person, landlord IBAN.
- **AC-RP-2:** Revolut covers only 11–28 Aug, so August is `PARTIAL` for it.
