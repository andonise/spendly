"""Layout-parser extension point.

A ``LayoutParser`` turns an opened PDF into a ``RawStatement``: the raw (unsanitized) transaction rows plus
the few header facts needed for validation (period, opening/closing balance, currency). Implementations
MUST NOT return any other header data (holder name, address, IBAN, card number).

To add a layout (another bank, or a ``docling``/``camelot`` based extractor), implement ``LayoutParser``
and register it in ``spendly_pdf_parser.extraction.default_layouts()``. Layouts are tried in order; the
first whose ``detect`` returns True wins.

Optional LLM fallback (plan section 13, Phase 2; not implemented): a future ``OllamaLayoutParser`` would be
registered LAST, ``detect`` returns True when no other layout matched, and ``extract`` asks the local
model (``OLLAMA_BASE_URL``) to return rows as JSON. Its rows go through the same pipeline, so they are
still sanitized first and still validated against the opening/closing balances before anything is
published. Note that it would send unsanitized table text to the local model; that must be sanitized
cell by cell first to respect AC-SD-2.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

import pdfplumber


class ExtractionError(Exception):
    """A business failure while reading the document. ``reason`` is a StatementParseFailed reason.

    ``message`` must be static text: no PII, amounts or descriptions.
    """

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(message)
        self.reason = reason
        self.message = message


@dataclass(frozen=True)
class RawRow:
    """One table row exactly as read from the document (strings, unsanitized)."""

    booking_date: str
    description: str = field(repr=False)  # raw, unsanitized: keep out of reprs/logs
    debit: str = field(repr=False)
    credit: str = field(repr=False)
    value_date: str | None = None


@dataclass
class RawStatement:
    layout: str
    rows: list[RawRow] = field(default_factory=list, repr=False)
    period_start: date | None = None
    period_end: date | None = None
    # Used only for validation. Never emitted, logged or stored (AC-SD-6).
    opening_balance: Decimal | None = field(default=None, repr=False)
    closing_balance: Decimal | None = field(default=None, repr=False)
    currency: str | None = None


class LayoutParser(ABC):
    name: str

    @abstractmethod
    def detect(self, pdf: pdfplumber.PDF) -> bool:
        """Cheap check: does this document use the layout?"""

    @abstractmethod
    def extract(self, pdf: pdfplumber.PDF) -> RawStatement:
        """Extract raw rows and validation facts. Raises ExtractionError on business failures."""
