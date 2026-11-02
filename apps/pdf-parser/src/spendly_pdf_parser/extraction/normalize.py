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


# --------------------------------------------------------------------------- any-bank formats
# Used by the pipeline for every layout, so a layout may hand over amounts and dates exactly as printed.

_MONTHS = {
    # English
    "jan": 1, "january": 1, "feb": 2, "february": 2, "mar": 3, "march": 3, "apr": 4, "april": 4, "may": 5,
    "jun": 6, "june": 6, "jul": 7, "july": 7, "aug": 8, "august": 8, "sep": 9, "sept": 9, "september": 9,
    "oct": 10, "october": 10, "nov": 11, "november": 11, "dec": 12, "december": 12,
    # Romanian (diacritics are folded before lookup)
    "ian": 1, "ianuarie": 1, "februarie": 2, "martie": 3, "aprilie": 4, "mai": 5, "iun": 6, "iunie": 6,
    "iul": 7, "iulie": 7, "septembrie": 9, "octombrie": 10, "noi": 11, "noiembrie": 11, "decembrie": 12,
}
_NUMERIC_DATE = re.compile(r"^([0-9]{1,2})([./-])([0-9]{1,2})\2([0-9]{4})$")
_ISO_DATE = re.compile(r"^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
_DAY_MONTH_YEAR = re.compile(r"^([0-9]{1,2})[ .-]+([A-Za-z]+)\.?[ .,-]+([0-9]{4})$")
_MONTH_DAY_YEAR = re.compile(r"^([A-Za-z]+)\.?[ ]+([0-9]{1,2}),?[ ]+([0-9]{4})$")
_AMOUNT_CORE = re.compile(
    r"^(?:[0-9]{1,3}(?:([.,' ])[0-9]{3})(?:\1[0-9]{3})*|[0-9]+)([.,])([0-9]{2})$"
)
_CURRENCY_TOKEN = re.compile(r"^(?:[A-Z]{3}|[€$£])$")
_SYMBOLS = "€$£"


def _fold(text: str) -> str:
    import unicodedata

    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).lower()


def _safe_date(year: int, month: int, day: int) -> date:
    try:
        return date(year, month, day)
    except ValueError:
        raise NormalizationError("invalid calendar date") from None


def parse_date(text: str, *, month_first: bool = False) -> date:
    """Any common statement date: ``03.08.2026``, ``03/08/2026``, ``2026-08-03``, ``Aug 3, 2026``,
    ``3 Aug 2026``, ``3 august 2026``. Numeric day/month order is day first unless ``month_first``."""
    t = " ".join(text.strip().split())
    if m := _ISO_DATE.match(t):
        return _safe_date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    if m := _NUMERIC_DATE.match(t):
        a, b = int(m.group(1)), int(m.group(3))
        day, month = (b, a) if month_first else (a, b)
        return _safe_date(int(m.group(4)), month, day)
    if m := _DAY_MONTH_YEAR.match(t):
        month = _MONTHS.get(_fold(m.group(2)))
        if month:
            return _safe_date(int(m.group(3)), month, int(m.group(1)))
    if m := _MONTH_DAY_YEAR.match(t):
        month = _MONTHS.get(_fold(m.group(1)))
        if month:
            return _safe_date(int(m.group(3)), month, int(m.group(2)))
    raise NormalizationError("not a supported date")


def is_date(text: str | None) -> bool:
    if not text:
        return False
    try:
        parse_date(text)
    except NormalizationError:
        return False
    return True


def parse_amount(text: str, *, allow_negative: bool = True) -> Decimal:
    """Any common statement amount with exactly 2 decimals: ``1.234,56``, ``1,234.56``, ``1 234,56``,
    ``1'234.56``, ``1234.56``, with an optional currency (``RON``, ``€``), sign (``-12.00``, ``12.00-``)
    or parentheses (``(12.00)``). Grouping and decimal separators must differ."""
    t = text.strip().replace(" ", " ").replace(" ", " ").replace("−", "-")
    parts = t.split(" ")
    while parts and _CURRENCY_TOKEN.match(parts[-1]):
        parts.pop()
    while parts and _CURRENCY_TOKEN.match(parts[0]):
        parts.pop(0)
    t = " ".join(parts).strip()
    negative = False
    if t.startswith("(") and t.endswith(")"):
        negative, t = True, t[1:-1].strip()
    if t and t[0] in "+-":
        negative, t = negative or t[0] == "-", t[1:].strip()
    elif t and t[-1] in "+-":
        negative, t = negative or t[-1] == "-", t[:-1].strip()
    t = t.strip(_SYMBOLS).strip()
    if t and t[0] in "+-":  # "€-12.00"
        negative, t = negative or t[0] == "-", t[1:]
    m = _AMOUNT_CORE.match(t)
    if not m or m.group(1) == m.group(2):
        raise NormalizationError("not a supported amount")
    if negative and not allow_negative:
        raise NormalizationError("negative amount not allowed here")
    whole = t[: m.start(2)]
    for sep in ".,' ":
        whole = whole.replace(sep, "")
    value = Decimal(f"{whole}.{m.group(3)}")
    return -value if negative else value


def is_amount(text: str | None) -> bool:
    if not text:
        return False
    try:
        parse_amount(text)
    except NormalizationError:
        return False
    return True
