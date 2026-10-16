"""Parsing samples/credit-card-2026-08.pdf end to end (no Kafka), plus the failure paths."""

from __future__ import annotations

import json
from dataclasses import replace
from decimal import Decimal

import pytest

from spendly_pdf_parser.extraction import RawRow, extract_statement
from spendly_pdf_parser.extraction.normalize import format_amount, parse_ro_date, parse_ro_decimal
from spendly_pdf_parser.pipeline import StatementContext, build_from_raw, parse_pdf

from .conftest import ACCOUNT_ID, STATEMENT_ID
from .helpers import assert_no_pii, minimal_pdf, recompute_tx_hashes

CTX = StatementContext(STATEMENT_ID, ACCOUNT_ID, "RON", "upl-20260901-7c1e9a")


@pytest.fixture(scope="module")
def outcome(sample_pdf_bytes, sanitizer, contracts):
    return parse_pdf(sample_pdf_bytes, CTX, sanitizer, contracts)


def test_sample_totals_match_samples_readme(outcome) -> None:
    assert outcome.event_type == "TransactionsImported"
    payload = outcome.event["payload"]
    amounts = [Decimal(t["amount"]) for t in payload["transactions"]]
    assert len(amounts) == 5
    assert sum(-a for a in amounts if a < 0) == Decimal("951.00")
    assert sum(a for a in amounts if a > 0) == Decimal("800.00")
    assert (payload["periodStart"], payload["periodEnd"]) == ("2026-08-01", "2026-08-31")
    assert outcome.summary.balanced is True
    assert outcome.summary.debit_total == "951.00" and outcome.summary.credit_total == "800.00"
    # -1200.00 + sum == -1351.00 (balances checked here from the README, never read from the event)
    assert Decimal("-1200.00") + sum(amounts) == Decimal("-1351.00")


def test_sample_rows(outcome, sanitizer) -> None:
    txs = outcome.event["payload"]["transactions"]
    assert [t["sequence"] for t in txs] == list(range(5))
    assert [t["bookingDate"] for t in txs] == ["2026-08-03", "2026-08-09", "2026-08-16", "2026-08-25", "2026-08-28"]
    assert [t["amount"] for t in txs] == ["-450.00", "-350.00", "800.00", "-146.00", "-5.00"]
    assert all(t["currency"] == "RON" and t["valueDate"] is None for t in txs)
    # raw PAN in the description is masked to the last 4
    assert txs[0]["description"] == "PLATA CARD ****5556 ALTEX IASI"
    # own current-account IBAN in the repayment row: masked, iban token as counterparty
    assert txs[2]["description"] == "RAMBURSARE DIN CONT RO**…3333"
    assert txs[2]["counterpartyTokenKind"] == "IBAN"
    assert txs[2]["counterpartyToken"] == sanitizer.hmac_hex("iban:RO54SPND0000111122223333")
    assert all(t["counterpartyToken"] is None for i, t in enumerate(txs) if i != 2)


def test_no_pii_or_balances_in_output(outcome) -> None:
    serialized = json.dumps(outcome.event, ensure_ascii=False) + json.dumps(outcome.summary.as_dict())
    assert_no_pii(serialized)
    for word in ("opening", "closing", "sold"):
        assert word not in serialized.lower()


def test_event_valid_and_hashes(outcome, contracts) -> None:
    event = outcome.event
    assert contracts.validate(event) == []
    assert event["producer"] == "spendly-pdf-parser"
    assert event["payload"]["parser"]["name"] == "python-pdf-pdfplumber"
    assert event["correlationId"] == CTX.correlation_id
    payload = event["payload"]
    assert payload["transactionCount"] == len(payload["transactions"])
    assert [t["txHash"] for t in payload["transactions"]] == recompute_tx_hashes(payload)


def test_duplicate_rows_get_distinct_hashes(sanitizer, contracts, sample_pdf_bytes) -> None:
    raw = extract_statement(sample_pdf_bytes)
    dup = raw.rows[3]
    raw = replace(raw, rows=[*raw.rows, dup], closing_balance=raw.closing_balance - Decimal("146.00"))
    out = build_from_raw(raw, CTX, sanitizer, contracts)
    assert out.succeeded
    hashes = [t["txHash"] for t in out.event["payload"]["transactions"]]
    assert len(set(hashes)) == len(hashes)
    assert hashes == recompute_tx_hashes(out.event["payload"])


def test_extra_row_gives_balance_mismatch(sanitizer, contracts, sample_pdf_bytes) -> None:
    raw = extract_statement(sample_pdf_bytes)
    tampered = replace(raw, rows=[*raw.rows, RawRow("30.08.2026", "PLATA CARD 4000 0566 5566 5556 EXTRA", "10,00", "")])
    out = build_from_raw(tampered, CTX, sanitizer, contracts)
    assert out.event_type == "StatementParseFailed"
    payload = out.event["payload"]
    assert payload["reason"] == "BALANCE_MISMATCH"
    assert payload["retryable"] is False
    assert contracts.validate(out.event) == []
    assert out.summary.balanced is False
    assert_no_pii(json.dumps(out.event, ensure_ascii=False))
    assert "10.00" not in payload["message"] and "EXTRA" not in payload["message"]


def test_tampered_amount_gives_balance_mismatch(sanitizer, contracts, sample_pdf_bytes) -> None:
    raw = extract_statement(sample_pdf_bytes)
    rows = list(raw.rows)
    rows[1] = replace(rows[1], debit="351,00")
    out = build_from_raw(replace(raw, rows=rows), CTX, sanitizer, contracts)
    assert out.reason == "BALANCE_MISMATCH"


def test_no_transactions(sanitizer, contracts, sample_pdf_bytes) -> None:
    raw = extract_statement(sample_pdf_bytes)
    out = build_from_raw(replace(raw, rows=[]), CTX, sanitizer, contracts)
    assert out.reason == "NO_TRANSACTIONS"
    assert contracts.validate(out.event) == []


def test_unreadable_file(sanitizer, contracts) -> None:
    out = parse_pdf(b"this is not a pdf", CTX, sanitizer, contracts)
    assert out.reason == "FILE_UNREADABLE"
    assert contracts.validate(out.event) == []


def test_pdf_without_table_is_unsupported(sanitizer, contracts) -> None:
    out = parse_pdf(minimal_pdf("Just some text, no table"), CTX, sanitizer, contracts)
    assert out.reason == "UNSUPPORTED_LAYOUT"
    assert contracts.validate(out.event) == []


def test_currency_mismatch_is_unsupported(sanitizer, contracts, sample_pdf_bytes) -> None:
    out = parse_pdf(sample_pdf_bytes, replace(CTX, currency="EUR"), sanitizer, contracts)
    assert out.reason == "UNSUPPORTED_LAYOUT"


def test_raw_statement_repr_hides_balances(sample_pdf_bytes) -> None:
    raw = extract_statement(sample_pdf_bytes)
    assert raw.opening_balance == Decimal("-1200.00") and raw.closing_balance == Decimal("-1351.00")
    assert_no_pii(repr(raw) + repr(raw.rows))
    assert "1200" not in repr(raw) and "1351" not in repr(raw)


@pytest.mark.parametrize(
    ("text", "expected"),
    [("1.234,56", "1234.56"), ("450,00", "450.00"), ("-1.200,00 RON", "-1200.00"), ("1.234.567,89", "1234567.89")],
)
def test_ro_numbers(text: str, expected: str) -> None:
    assert format_amount(parse_ro_decimal(text)) == expected


@pytest.mark.parametrize("bad", ["1,2", "12.34", "1.23,45", "abc", ""])
def test_ro_numbers_reject(bad: str) -> None:
    with pytest.raises(ValueError):
        parse_ro_decimal(bad)


def test_ro_dates() -> None:
    assert parse_ro_date("03.08.2026").isoformat() == "2026-08-03"
    with pytest.raises(ValueError):
        parse_ro_date("31.02.2026")
    assert format_amount(Decimal("-0.00")) == "0.00"
