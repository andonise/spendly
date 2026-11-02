"""Fallback for any bank: a positioned-text transaction table found by its column header.

Registered last, so bank-specific layouts win. It recognises a header naming a date, a description and
money columns (debit + credit, or a signed amount; optionally value date and running balance), in English
or Romanian (see ``text_table.HEADER_PHRASES``). Wrapped description lines are joined to their row.

Validation facts, in order of preference:
- ``Opening balance`` / ``Sold initial`` … and ``Closing balance`` / ``Sold final`` … lines;
- otherwise the running balance column (checked on every row; newest-first statements are reversed).
Without either, the pipeline rejects the statement (``UNSUPPORTED_LAYOUT``): nothing is imported unless the
amounts provably add up.
"""

from __future__ import annotations

import pdfplumber

from . import text_table as tt
from .base import ExtractionError, LayoutParser, RawStatement


class GenericTableLayout(LayoutParser):
    name = "generic-table"

    def detect(self, pdf: pdfplumber.PDF) -> bool:
        for i, page in enumerate(pdf.pages):
            if any(tt.match_header(line) for line in tt.page_lines(page, i)):
                return True
        return False

    def extract(self, pdf: pdfplumber.PDF) -> RawStatement:
        result = tt.scan(pdf, continuation=True)
        if not result.headers:
            raise ExtractionError("UNSUPPORTED_LAYOUT", "No transaction table with the expected header was found")
        statement = RawStatement(layout=self.name)
        rows = result.rows
        running = tt.reconcile_running_balance(rows) if rows else None
        if running is not None:
            rows = running[0]
        statement.rows = tt.to_raw_rows(rows)
        opening = tt.find_labelled_balance(result.lines, tt.OPENING_LABELS)
        closing = tt.find_labelled_balance(result.lines, tt.CLOSING_LABELS)
        if opening is not None and closing is not None:
            statement.opening_balance, statement.closing_balance = opening, closing
        elif running is not None:
            statement.opening_balance, statement.closing_balance = running[1], running[2]
        statement.currency = tt.find_currency(result.lines, result.rows)
        if period := tt.find_period(result.lines):
            statement.period_start, statement.period_end = period
        return statement
