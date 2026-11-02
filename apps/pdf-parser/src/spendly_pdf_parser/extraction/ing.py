"""ING Bank N.V. Amsterdam - Sucursala Bucuresti ("Extras de cont"), positioned text, no grid.

Layout:
- Header block (holder, address, account and card numbers): never read, except the ``Pentru perioada:``
  line (period) and the ``Moneda`` line (currency).
- Column header ``Data | Detalii tranzactie | Debit | Credit``, repeated on every page.
- One line per transaction: ``01 octombrie 2026  Cumparare POS  12,34`` (date, type, one amount in the
  Debit or Credit column), followed by labelled detail lines in the description column
  (``Terminal:``, ``Beneficiar:``, ``In contul:``, ``Numar card:``, ``Referinta:`` …).
- ``Sold initial:`` / ``Sold final:`` after the last row (used only for the balance check).

The description keeps the transaction type plus the details that identify the merchant or counterparty
(terminal, beneficiary/payer, their account, free-text details). Card numbers, authorisation numbers and
dates, references, bank names and tax codes are dropped: they are noise for categorization and only make
descriptions unique. Everything kept still goes through the sanitizer in the pipeline.
"""

from __future__ import annotations

import re

import pdfplumber

from . import text_table as tt
from .base import ExtractionError, LayoutParser, RawStatement
from .normalize import NormalizationError, parse_date

# Detail labels (folded) worth keeping, and the prefix they get in the description ("" = value only).
_KEEP = {
    "terminal": "",
    "beneficiar": "Beneficiar:",
    "ordonator": "Ordonator:",
    "in contul": "In contul:",
    "din contul": "Din contul:",
    "detalii": "Detalii:",
}
_LABEL = re.compile(r"^([^:]{2,30}):\s*(.*)$")
_PERIOD = re.compile(
    r"perioada\s*:?\s*([0-9]{1,2}[./][0-9]{1,2}[./][0-9]{4})\s*-\s*([0-9]{1,2}[./][0-9]{1,2}[./][0-9]{4})", re.I
)
_MARKERS = ("ing bank", "extras de cont")


class _Pending:
    """The row being read and its kept detail parts."""

    def __init__(self, row: tt.TableRow) -> None:
        self.row = row
        self.parts: list[str] = []
        self.keeping = False  # whether the last label was kept (an unlabelled line continues it)

    def finish(self) -> tt.TableRow:
        self.row.description = " ".join([self.row.description, *self.parts]).strip()
        return self.row


class IngStatementLayout(LayoutParser):
    name = "ing-ro"

    def detect(self, pdf: pdfplumber.PDF) -> bool:
        first = tt.fold(pdf.pages[0].extract_text() or "")
        if not all(m in first for m in _MARKERS):
            return False
        return any(tt.match_header(line) is not None for line in tt.page_lines(pdf.pages[0], 0))

    def extract(self, pdf: pdfplumber.PDF) -> RawStatement:
        lines = tt.document_lines(pdf)
        rows = self._read_rows(lines)
        statement = RawStatement(layout=self.name)
        statement.rows = tt.to_raw_rows(rows)
        statement.currency = tt.find_currency(lines, rows)
        left = [line for line in lines if line.words[0].x0 < 100]  # balance lines start at the left margin
        statement.opening_balance = tt.find_labelled_balance(left, ("sold initial",))
        statement.closing_balance = tt.find_labelled_balance(left, ("sold final",))
        if period := self._period(lines):
            statement.period_start, statement.period_end = period
        return statement

    @staticmethod
    def _period(lines: list[tt.Line]):
        for line in lines:
            if m := _PERIOD.search(line.text):
                try:
                    start, end = parse_date(m.group(1)), parse_date(m.group(2))
                except NormalizationError:
                    raise ExtractionError("UNSUPPORTED_LAYOUT", "A period or balance line could not be read") from None
                if start <= end:
                    return start, end
        return None

    @staticmethod
    def _read_rows(lines: list[tt.Line]) -> list[tt.TableRow]:
        rows: list[tt.TableRow] = []
        header: tt.Header | None = None
        active = True
        current: _Pending | None = None

        def close() -> None:
            nonlocal current
            if current is not None:
                rows.append(current.finish())
                current = None

        for line in lines:
            if (h := tt.match_header(line)) is not None:
                header = h  # repeated on every page; a row's details may continue after it
                continue
            if header is None:
                continue  # header block: holder, address, account and card data are never read
            desc = header.get("description")
            assert desc is not None
            x0 = line.words[0].x0
            if x0 < desc.x0 - 40:
                row = tt.parse_row(line, header)
                if row is not None:
                    close()
                    if active:
                        current = _Pending(row)
                    continue
                state = tt.title_state(line)
                if state is not None:
                    close()
                    active = state
                    continue
                if tt.fold(line.text).startswith("sold "):
                    close()  # "Sold initial/final" ends the table
                continue  # footer or other left-margin text: not part of the row
            if current is not None and abs(x0 - desc.x0) <= 8:
                IngStatementLayout._detail(current, line.text)
        close()
        if header is None:
            raise ExtractionError("UNSUPPORTED_LAYOUT", "No transaction table with the expected header was found")
        return rows

    @staticmethod
    def _detail(current: _Pending, text: str) -> None:
        m = _LABEL.match(text)
        if m is None:
            if current.keeping:
                current.parts.append(text.strip())  # wrapped value of a kept label
            return
        label = tt.fold(m.group(1))
        prefix = _KEEP.get(label)
        current.keeping = prefix is not None
        value = m.group(2).strip()
        if prefix is None or not value:
            return
        current.parts.append(f"{prefix} {value}".strip())
