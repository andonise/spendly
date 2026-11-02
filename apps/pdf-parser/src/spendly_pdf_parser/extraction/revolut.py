"""Revolut PDF statement (``<Account> <CCY> Statement``, Revolut Bank UAB).

Positioned text, no ruling lines. Sections, each with its own column header:

- ``Balance summary``: ``Product | Opening balance | Money out | Money in | Closing balance``; the first
  product row gives the opening and closing balance used for validation.
- ``Pending from … to …`` and ``Reverted from … to …``: not booked, skipped.
- ``Account transactions from … to …``: ``Date | Description | Money out | Money in | Balance``. Each row is
  followed by detail lines (``Transaction Id:``, ``To:``, ``From:``, ``Card:``, ``Reference:``, an IBAN,
  exchange rates). Detail lines are never read: the description is the main line only, the same text the
  Revolut CSV export has in ``Description``, so a statement imported once as CSV and once as PDF produces
  the same ``txHash`` values.

The header block (holders, address, IBAN, BIC) is never read.
"""

from __future__ import annotations

import re

import pdfplumber

from . import text_table as tt
from .base import ExtractionError, LayoutParser, RawStatement

_TITLE = re.compile(r"\b([A-Z]{3}) Statement$")


class RevolutStatementLayout(LayoutParser):
    name = "revolut"

    def detect(self, pdf: pdfplumber.PDF) -> bool:
        text = pdf.pages[0].extract_text() or ""
        return "Revolut Bank" in text and ("Account transactions from" in text or "Balance summary" in text)

    def extract(self, pdf: pdfplumber.PDF) -> RawStatement:
        result = tt.scan(pdf, continuation=False)
        if not result.headers:
            raise ExtractionError("UNSUPPORTED_LAYOUT", "No transaction table with the expected header was found")
        statement = RawStatement(layout=self.name)
        rows = result.rows
        running = tt.reconcile_running_balance(rows) if rows else None
        if running is not None:
            rows = running[0]
        statement.rows = tt.to_raw_rows(rows)
        summary = self._summary(result.lines)
        if summary is not None:
            statement.opening_balance, statement.closing_balance = summary
        elif running is not None:
            statement.opening_balance, statement.closing_balance = running[1], running[2]
        statement.currency = self._currency(result.lines) or tt.find_currency(result.lines, result.rows)
        if period := self._period(result.lines):
            statement.period_start, statement.period_end = period
        return statement

    @staticmethod
    def _summary(lines: list[tt.Line]):
        """Opening and closing balance from the first product row of the balance summary."""
        seen_header = False
        for line in lines:
            folded = tt.fold(line.text)
            if not seen_header:
                seen_header = "opening balance" in folded and "money out" in folded
                continue
            amounts = tt.line_amounts(line)
            if len(amounts) == 4 and not folded.startswith("total"):
                return amounts[0][0], amounts[3][0]
            if folded.startswith(("total", "pending", "account transactions", "date description")):
                break
        return None

    @staticmethod
    def _currency(lines: list[tt.Line]) -> str | None:
        for line in lines[:5]:
            if m := _TITLE.search(line.text.strip()):
                return m.group(1)
        return None

    @staticmethod
    def _period(lines: list[tt.Line]):
        account = [l for l in lines if tt.fold(l.text).startswith("account transactions")]
        return tt.find_period(account) or None
