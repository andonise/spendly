"""Romanian number and date normalization. Error messages never include the offending value."""

from __future__ import annotations

import re
from datetime import date
from decimal import Decimal

_RO_NUMBER = re.compile(r"^(-?)([0-9]{1,3}(?:\.[0-9]{3})+|[0-9]+),([0-9]{2})$")
_RO_DATE = re.compile(r"^([0-9]{2})\.([0-9]{2})\.([0-9]{4})$")
_CURRENCY_SUFFIX = re.compile(r"\s*[A-Z]{3}$")


class NormalizationError(ValueError):
    pass


def parse_ro_decimal(text: str, *, allow_negative: bool = True) -> Decimal:
    """``1.234,56`` -> Decimal('1234.56'); ``-1.200,00 RON`` -> Decimal('-1200.00')."""
    cleaned = _CURRENCY_SUFFIX.sub("", text.strip().replace(" ", " ")).replace(" ", "")
    m = _RO_NUMBER.match(cleaned)
    if not m or (m.group(1) and not allow_negative):
        raise NormalizationError("not a Romanian-format amount")
    return Decimal(f"{m.group(1)}{m.group(2).replace('.', '')}.{m.group(3)}")


def parse_ro_date(text: str) -> date:
    """``dd.MM.yyyy`` -> date."""
    m = _RO_DATE.match(text.strip())
    if not m:
        raise NormalizationError("not a dd.MM.yyyy date")
    try:
        return date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
    except ValueError:
        raise NormalizationError("invalid calendar date") from None


def is_ro_date(text: str | None) -> bool:
    return bool(text) and bool(_RO_DATE.match(text.strip()))


def format_amount(value: Decimal) -> str:
    """Canonical contract amount: exactly 2 decimals, '.' separator, no thousands separators, no '-0.00'."""
    quantized = value.quantize(Decimal("0.01"))
    if quantized == 0:
        quantized = Decimal("0.00")
    return f"{quantized:.2f}"
