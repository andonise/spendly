"""Positioned-text layouts (Revolut, generic fallback) and the any-format date/amount parsers."""

from __future__ import annotations

import io
import json
from dataclasses import replace
from datetime import date
from decimal import Decimal

import pdfplumber
import pytest

from spendly_pdf_parser.extraction import ExtractionError, extract_statement
from spendly_pdf_parser.extraction import text_table as tt
from spendly_pdf_parser.extraction.generic_table import GenericTableLayout
from spendly_pdf_parser.extraction.normalize import is_amount, is_date, parse_amount, parse_date
from spendly_pdf_parser.extraction.revolut import RevolutStatementLayout
from spendly_pdf_parser.pipeline import StatementContext, parse_pdf

from .conftest import ACCOUNT_ID, REPO_DIR, STATEMENT_ID
from .helpers import assert_no_pii, minimal_pdf

CTX = StatementContext(STATEMENT_ID, ACCOUNT_ID, "RON", "upl-20261001-layouts")
SAMPLES = REPO_DIR / "samples"


def _bytes(name: str) -> bytes:
    return (SAMPLES / name).read_bytes()


def _parse(name: str, sanitizer, contracts):
    return parse_pdf(_bytes(name), CTX, sanitizer, contracts)


# --------------------------------------------------------------------------- samples end to end


@pytest.mark.parametrize(
    ("name", "layout", "count", "debits", "credits", "period"),
    [
        ("revolut-2026-09.pdf", "revolut", 12, "810.40", "1450.00", ("2026-09-01", "2026-09-30")),
        ("other-bank-2026-09.pdf", "generic-table", 8, "1364.10", "2800.00", ("2026-09-01", "2026-09-30")),
        ("ro-bank-2026-09.pdf", "generic-table", 5, "1553.43", "300.00", ("2026-09-01", "2026-09-30")),
    ],
)
def test_sample_totals(name, layout, count, debits, credits, period, sanitizer, contracts) -> None:
    out = _parse(name, sanitizer, contracts)
    assert out.event_type == "TransactionsImported", out.reason
    assert out.summary.layout == layout
    assert out.summary.balanced is True and out.summary.schema_valid is True
    assert out.summary.transaction_count == count
    assert (out.summary.debit_total, out.summary.credit_total) == (debits, credits)
    payload = out.event["payload"]
    assert (payload["periodStart"], payload["periodEnd"]) == period
    assert_no_pii(json.dumps(out.event, ensure_ascii=False))


def test_revolut_skips_pending_reverted_and_detail_lines(sanitizer, contracts) -> None:
    out = _parse("revolut-2026-09.pdf", sanitizer, contracts)
    descriptions = [t["description"] for t in out.event["payload"]["transactions"]]
    assert descriptions[:3] == ["Apple Pay top-up by *3333", "Lidl", "Spotify"]
    assert "Emag" not in descriptions and "Uber" not in descriptions  # pending / reverted
    text = json.dumps(out.event)
    for detail in ("Transaction Id", "Card:", "411111", "Revolut Rate", "REVOROBB", "RO32"):
        assert detail not in text


def test_revolut_person_names_are_tokenized(sanitizer, contracts) -> None:
    txs = _parse("revolut-2026-09.pdf", sanitizer, contracts).event["payload"]["transactions"]
    person = [t for t in txs if t["counterpartyTokenKind"] == "PERSON"]
    assert len(person) == 2
    assert all(t["description"].startswith("Transfer from [PERSON#") for t in person)


def test_revolut_detects_currency_and_rejects_other_account_currency(sanitizer, contracts) -> None:
    assert extract_statement(_bytes("revolut-2026-09.pdf")).currency == "RON"
    out = parse_pdf(_bytes("revolut-2026-09.pdf"), replace(CTX, currency="EUR"), sanitizer, contracts)
    assert out.reason == "UNSUPPORTED_LAYOUT"


def test_newest_first_statement_is_put_in_date_order_and_wraps_are_joined(sanitizer, contracts) -> None:
    txs = _parse("other-bank-2026-09.pdf", sanitizer, contracts).event["payload"]["transactions"]
    dates = [t["bookingDate"] for t in txs]
    assert dates == sorted(dates)
    assert txs[0]["description"] == "CARD PAYMENT TESCO STORES 2231 LONDON GB"
    # wrapped line joined; the person name is pseudonymized and the stop word REF ends it
    assert txs[2]["description"].startswith("FASTER PAYMENT FROM [PERSON#")
    assert txs[2]["description"].endswith("] REF FLAT SHARE SEPT")
    landlord = txs[6]
    assert landlord["description"] == "STANDING ORDER TO LANDLORD RO**…0000"
    assert landlord["counterpartyTokenKind"] == "IBAN"
    # identical rows on one day get distinct hashes
    pret = [t["txHash"] for t in txs if "PRET" in t["description"]]
    assert len(set(pret)) == 2


def test_generic_reads_labelled_balances_and_signed_amounts() -> None:
    raw = extract_statement(_bytes("ro-bank-2026-09.pdf"))
    assert raw.layout == "generic-table"
    assert (raw.opening_balance, raw.closing_balance) == (Decimal("2500.00"), Decimal("1246.57"))
    assert raw.currency == "RON"
    assert [r.debit or "+" + r.credit for r in raw.rows][:2] == ["45.30", "+300.00"]
    assert "2500" not in repr(raw) and "1246" not in repr(raw)


def test_card_sample_still_uses_the_ruled_table_layout() -> None:
    assert extract_statement((SAMPLES / "credit-card-2026-08.pdf").read_bytes()).layout.startswith("ro-table")


# --------------------------------------------------------------------------- engine details


def _rows(*specs: tuple[str, str | None, str | None, str | None]) -> list[tt.TableRow]:
    def dec(v: str | None) -> Decimal | None:
        return Decimal(v) if v is not None else None

    return [tt.TableRow(0, d, "x", debit=dec(o), credit=dec(i), balance=dec(b)) for d, o, i, b in specs]


def test_running_balance_ascending() -> None:
    rows = _rows(("01.09.2026", "10.00", None, "90.00"), ("02.09.2026", None, "5.00", "95.00"))
    ordered, opening, closing = tt.reconcile_running_balance(rows)
    assert ordered == rows and (opening, closing) == (Decimal("100.00"), Decimal("95.00"))


def test_running_balance_newest_first_is_reversed() -> None:
    rows = _rows(("02.09.2026", None, "5.00", "95.00"), ("01.09.2026", "10.00", None, "90.00"))
    ordered, opening, closing = tt.reconcile_running_balance(rows)
    assert [r.date_text for r in ordered] == ["01.09.2026", "02.09.2026"]
    assert (opening, closing) == (Decimal("100.00"), Decimal("95.00"))


def test_running_balance_allows_rows_without_balance() -> None:
    rows = _rows(("01.09.2026", "10.00", None, None), ("01.09.2026", "5.00", None, "85.00"), ("02.09.2026", "1.00", None, None))
    _, opening, closing = tt.reconcile_running_balance(rows)
    assert (opening, closing) == (Decimal("100.00"), Decimal("84.00"))


def test_running_balance_mismatch_and_absent() -> None:
    with pytest.raises(ExtractionError) as exc:
        tt.reconcile_running_balance(_rows(("01.09.2026", "10.00", None, "90.00"), ("02.09.2026", "1.00", None, "50.00")))
    assert exc.value.reason == "BALANCE_MISMATCH"
    assert tt.reconcile_running_balance(_rows(("01.09.2026", "10.00", None, None))) is None


def test_day_first_detection_and_iso_rows() -> None:
    assert tt.day_first(_rows(("08/31/2026", "1.00", None, None))) is False
    assert tt.day_first(_rows(("31/08/2026", "1.00", None, None))) is True
    assert tt.day_first(_rows(("03/04/2026", "1.00", None, None))) is True
    raw = tt.to_raw_rows(_rows(("08/31/2026", "1.50", None, None)))
    assert (raw[0].booking_date, raw[0].debit, raw[0].credit) == ("2026-08-31", "1.50", "")
    with pytest.raises(ExtractionError):
        tt.to_raw_rows(_rows(("31/31/2026", "1.00", None, None)))


def _line(*words: tuple[str, float]) -> tt.Line:
    return tt.Line(0, 100.0, [tt.Word(t, x, x + 6 * len(t)) for t, x in words])


def test_header_matching() -> None:
    header = tt.match_header(_line(("Date", 40), ("Description", 120), ("Money", 330), ("out", 362), ("Money", 415), ("in", 444), ("Balance", 525)))
    assert header is not None and [c.role for c in header.columns] == ["date", "description", "debit", "credit", "balance"]
    assert tt.match_header(_line(("Data", 40), ("Detalii", 120), ("Suma", 400))) is not None
    # prose that happens to contain column words is not a header
    assert tt.match_header(_line(("The", 40), ("date", 60), ("and", 90), ("description", 110), ("of", 170), ("the", 190), ("amount", 210), ("may", 260), ("vary", 290))) is None
    assert tt.match_header(_line(("Date", 40), ("Description", 120))) is None


def test_row_amounts_go_to_their_column_and_description_numbers_stay_text() -> None:
    header = tt.match_header(_line(("Date", 40), ("Details", 120), ("Paid", 360), ("out", 390), ("Paid", 440), ("in", 470), ("Balance", 520)))
    row = tt.parse_row(_line(("12/09/2026", 40), ("SHOP", 120), ("12.50", 160), ("1,234.56", 440), ("2,000.00", 520)), header)
    assert row.description == "SHOP 12.50" and row.credit == Decimal("1234.56") and row.debit is None
    assert row.balance == Decimal("2000.00")
    assert tt.parse_row(_line(("Total", 40), ("9.00", 360)), header) is None
    assert tt.parse_row(_line(("12/09/2026", 40), ("Closing", 120), ("2,000.00", 520)), header) is None


def test_section_titles() -> None:
    assert tt.title_state(_line(("Pending", 40), ("from", 90), ("September", 120))) is False
    assert tt.title_state(_line(("Tranzactii", 40), ("in", 100), ("asteptare", 115))) is False
    assert tt.title_state(_line(("Account", 40), ("transactions", 90))) is True
    assert tt.title_state(_line(("To:", 125), ("Ubr*", 150), ("Pending.uber.com", 180))) is None


def test_period_and_labelled_balance_lines() -> None:
    lines = [_line(("Statement", 40), ("period:", 90), ("01/09/2026", 140), ("to", 200), ("30/09/2026", 215)),
             _line(("Sold", 40), ("initial:", 70), ("1.234,56", 120), ("RON", 170))]
    assert tt.find_period(lines) == (date(2026, 9, 1), date(2026, 9, 30))
    assert tt.find_labelled_balance(lines, tt.OPENING_LABELS) == Decimal("1234.56")
    assert tt.find_labelled_balance(lines, tt.CLOSING_LABELS) is None


def test_document_without_header_is_not_detected() -> None:
    with pdfplumber.open(io.BytesIO(minimal_pdf("Date Description"))) as pdf:
        assert GenericTableLayout().detect(pdf) is False
        assert RevolutStatementLayout().detect(pdf) is False


# --------------------------------------------------------------------------- parsers


@pytest.mark.parametrize(
    ("text", "expected"),
    [("1.234,56", "1234.56"), ("1,234.56", "1234.56"), ("1 234,56", "1234.56"), ("1'234.56", "1234.56"),
     ("1234.56", "1234.56"), ("450,00", "450.00"), ("-1.200,00 RON", "-1200.00"), ("999.99 RON", "999.99"),
     ("(12.00)", "-12.00"), ("12.00-", "-12.00"), ("€99.99", "99.99"), ("€-5.00", "-5.00"), ("RON 10,00", "10.00"),
     ("1,234,567.89", "1234567.89"), ("1.234.567,89", "1234567.89"), ("+3.10", "3.10")],
)
def test_parse_amount(text: str, expected: str) -> None:
    assert f"{parse_amount(text):.2f}" == expected


@pytest.mark.parametrize("bad", ["1,2", "12.345", "1.23,45", "abc", "", "-", "RON", "1,234", "1.234,567"])
def test_parse_amount_rejects(bad: str) -> None:
    assert not is_amount(bad)


def test_parse_amount_negative_not_allowed() -> None:
    with pytest.raises(ValueError):
        parse_amount("-1.00", allow_negative=False)


@pytest.mark.parametrize(
    ("text", "expected"),
    [("03.08.2026", "2026-08-03"), ("03/08/2026", "2026-08-03"), ("3-8-2026", "2026-08-03"), ("2026-08-03", "2026-08-03"),
     ("Oct 9, 2026", "2026-10-09"), ("October 9, 2026", "2026-10-09"), ("9 Oct 2026", "2026-10-09"),
     ("3 august 2026", "2026-08-03"), ("12 ian. 2026", "2026-01-12"), ("5 Noiembrie 2026", "2026-11-05")],
)
def test_parse_date(text: str, expected: str) -> None:
    assert parse_date(text).isoformat() == expected


def test_parse_date_month_first_and_rejects() -> None:
    assert parse_date("08/03/2026", month_first=True).isoformat() == "2026-08-03"
    for bad in ("31.02.2026", "Foo 9, 2026", "14:22", "", "2026-13-01"):
        assert not is_date(bad)
