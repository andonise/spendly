"""pdfplumber layout: a ruled table with header ``Data | Descriere | Debit | Credit`` (Romanian bank
statement, e.g. samples/credit-card-2026-08.pdf).

Header handling (AC-SD-6 and plan section 11): page text is scanned line by line ONLY for the
``Perioada``, ``Moneda``, ``Sold initial`` and ``Sold final`` lines. Every other header line (holder,
address, IBAN, card number) is discarded without being stored anywhere.
"""

from __future__ import annotations

import re

import pdfplumber

from .base import ExtractionError, LayoutParser, RawRow, RawStatement
from .normalize import NormalizationError, is_ro_date, parse_ro_date, parse_ro_decimal

HEADER = ("data", "descriere", "debit", "credit")
_PERIOD = re.compile(r"^Perioada\s*:\s*([0-9]{2}\.[0-9]{2}\.[0-9]{4})\s*-\s*([0-9]{2}\.[0-9]{2}\.[0-9]{4})\s*$", re.I)
_CURRENCY = re.compile(r"^Moneda\s*:\s*([A-Z]{3})\s*$", re.I)
_OPENING = re.compile(r"^Sold initial\s*:\s*(-?[0-9.]+,[0-9]{2})(?:\s+[A-Z]{3})?\s*$", re.I)
_CLOSING = re.compile(r"^Sold final\s*:\s*(-?[0-9.]+,[0-9]{2})(?:\s+[A-Z]{3})?\s*$", re.I)

_TABLE_SETTINGS = {"vertical_strategy": "lines", "horizontal_strategy": "lines"}


def _cell(value: object) -> str:
    return "" if value is None else str(value).strip()


def _is_header(row: list[object]) -> bool:
    return tuple(_cell(c).lower() for c in row) == HEADER


class RomanianCardTableLayout(LayoutParser):
    name = "ro-table-data-descriere-debit-credit"

    def detect(self, pdf: pdfplumber.PDF) -> bool:
        for page in pdf.pages:
            for table in page.extract_tables(_TABLE_SETTINGS):
                if table and len(table[0]) == len(HEADER) and _is_header(table[0]):
                    return True
        return False

    def extract(self, pdf: pdfplumber.PDF) -> RawStatement:
        statement = RawStatement(layout=self.name)
        self._read_validation_lines(pdf, statement)
        statement.rows = self._read_rows(pdf)
        return statement

    # ------------------------------------------------------------------ header (validation facts only)

    @staticmethod
    def _read_validation_lines(pdf: pdfplumber.PDF, statement: RawStatement) -> None:
        try:
            for page in pdf.pages:
                for line in (page.extract_text() or "").splitlines():
                    line = line.strip()
                    if m := _PERIOD.match(line):
                        statement.period_start = parse_ro_date(m.group(1))
                        statement.period_end = parse_ro_date(m.group(2))
                    elif m := _CURRENCY.match(line):
                        statement.currency = m.group(1).upper()
                    elif m := _OPENING.match(line):
                        statement.opening_balance = parse_ro_decimal(m.group(1))
                    elif m := _CLOSING.match(line):
                        statement.closing_balance = parse_ro_decimal(m.group(1))
                    # any other line (holder, address, IBAN, card, ...) is deliberately ignored
        except NormalizationError:
            raise ExtractionError("UNSUPPORTED_LAYOUT", "A period or balance line could not be read") from None

    # ------------------------------------------------------------------ transaction table

    @staticmethod
    def _read_rows(pdf: pdfplumber.PDF) -> list[RawRow]:
        rows: list[RawRow] = []
        in_table = False
        for page in pdf.pages:
            for table in page.extract_tables(_TABLE_SETTINGS):
                if not table or len(table[0]) != len(HEADER):
                    continue
                body = table
                if _is_header(table[0]):
                    in_table, body = True, table[1:]
                elif not (in_table and is_ro_date(_cell(table[0][0]))):
                    continue  # unrelated 4-column table; a continuation table must start with a date row
                for raw in body:
                    cells = [_cell(c) for c in raw]
                    date_text, description, debit, credit = cells
                    if not date_text and description.lower().startswith("total"):
                        continue  # totals row, not a transaction
                    if not any(cells):
                        continue
                    if not date_text and description and not debit and not credit and rows:
                        prev = rows[-1]  # wrapped description line continuing the previous row
                        rows[-1] = RawRow(prev.booking_date, f"{prev.description} {description}", prev.debit, prev.credit)
                        continue
                    if not is_ro_date(date_text):
                        raise ExtractionError("UNSUPPORTED_LAYOUT", "A table row has no valid booking date")
                    rows.append(RawRow(booking_date=date_text, description=description, debit=debit, credit=credit))
        if not in_table:
            raise ExtractionError("UNSUPPORTED_LAYOUT", "No transaction table with the expected header was found")
        return rows
