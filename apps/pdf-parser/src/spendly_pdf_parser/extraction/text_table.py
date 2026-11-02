"""Position-based statement tables: read a transaction table from word coordinates, not ruling lines.

Most bank PDFs print their transactions as positioned text (no grid). This module finds the column header
line (``Date | Description | Money out | Money in | Balance``, ``Data | Detalii | Debit | Credit | Sold``,
``Date | Details | Amount`` …), records each column's x-range, and reads every line below it that starts
with a date. Amounts are assigned to a column by their position, so a missing debit or credit cell is never
misread as the other one.

Header data (holder, address, IBAN, card) is never read: only table rows, section titles, the period line
and opening/closing balance lines are looked at. Row text is returned unsanitized; the pipeline sanitizes it
before anything else.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

import pdfplumber

from .base import ExtractionError, RawRow
from .normalize import NormalizationError, format_amount, parse_amount, parse_date

# Column header phrases, compared after folding (lowercase, no diacritics, no trailing ':' or '.').
HEADER_PHRASES: dict[str, tuple[str, ...]] = {
    "date": ("date", "data", "booking date", "transaction date", "posting date", "completed date",
             "data tranzactiei", "data tranzactie", "data operatiunii", "data operatiune", "data inregistrarii",
             "data contabila", "data efectuarii"),
    "value_date": ("value date", "data valuta", "data valutei", "data decontarii"),
    "description": ("description", "descriere", "details", "detalii", "transaction details", "detalii tranzactie",
                    "detalii tranzactii", "explicatii", "narrative", "particulars", "beneficiar / detalii",
                    "descrierea tranzactiei", "operatiune", "tranzactie"),
    "debit": ("debit", "debits", "money out", "paid out", "withdrawals", "plati", "suma debit", "iesiri",
              "retrageri", "out"),
    "credit": ("credit", "credits", "money in", "paid in", "deposits", "incasari", "suma credit", "intrari",
               "depuneri", "in"),
    "amount": ("amount", "suma", "valoare", "amount (ron)", "suma (ron)"),
    "balance": ("balance", "sold", "running balance", "sold curent", "balance (ron)", "sold (ron)"),
}
_MAX_PHRASE_WORDS = max(len(p.split()) for phrases in HEADER_PHRASES.values() for p in phrases)
NUMERIC_ROLES = ("debit", "credit", "amount", "balance")

# Section titles. A title line is short, has no amounts and STARTS with one of these phrases.
EXCLUDE_TITLES = ("pending", "reverted", "blocked", "on hold", "declined", "tranzactii in asteptare",
                  "tranzactii in curs", "tranzactii blocate", "sume blocate", "operatiuni in asteptare",
                  "operatiuni in curs", "autorizari", "authorisations", "authorizations")
INCLUDE_TITLES = ("account transactions", "transactions", "your transactions", "account activity",
                  "tranzactii", "operatiuni", "rulaj", "miscari")

OPENING_LABELS = ("opening balance", "sold initial", "sold anterior", "sold precedent", "previous balance",
                  "balance brought forward", "brought forward", "starting balance", "start balance",
                  "sold la inceputul perioadei", "sold inceput", "balance at start", "sold de deschidere")
CLOSING_LABELS = ("closing balance", "sold final", "new balance", "balance carried forward", "carried forward",
                  "ending balance", "end balance", "sold la sfarsitul perioadei", "balance at end",
                  "sold de inchidere", "sold disponibil final")

_PERIOD = re.compile(
    r"(?:^|\b)(?:from|de la|perioada|period|statement period|perioada extrasului|between)\s*:?\s+"
    r"(.+?)(?:\s*[-–]\s*|\s+(?:to|pana la|până la|and)\s+)(.+?)\s*$",
    re.I,
)
_CURRENCY = re.compile(r"^[A-Z]{3}$")
_CURRENCY_LINE = re.compile(r"^(?:moneda|currency|valuta)\s*:?\s*([A-Z]{3})\b", re.I)
_SYMBOL_CURRENCY = {"€": "EUR", "$": "USD", "£": "GBP"}
_NUMBERISH = re.compile(r"^[-+(€$£]?[0-9][0-9.,' ]*[0-9]\)?[-+]?$|^[-+(€$£]?[0-9]\)?$")
_Y_TOLERANCE = 3.0


def fold(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text)
    return " ".join("".join(c for c in decomposed if not unicodedata.combining(c)).lower().split())


def _fold_word(text: str) -> str:
    return fold(text).rstrip(":.")


# --------------------------------------------------------------------------- lines and words


@dataclass(frozen=True)
class Word:
    text: str = field(repr=False)
    x0: float
    x1: float


@dataclass
class Line:
    page: int
    top: float
    words: list[Word] = field(repr=False)

    @property
    def text(self) -> str:
        return " ".join(w.text for w in self.words)


def page_lines(page: pdfplumber.page.Page, page_number: int) -> list[Line]:
    """Words grouped into visual lines (same baseline within a small tolerance), left to right."""
    words = page.extract_words(keep_blank_chars=False, use_text_flow=False, x_tolerance=1.5)
    lines: list[Line] = []
    for w in sorted(words, key=lambda w: (round(w["top"]), w["x0"])):
        word = Word(w["text"], float(w["x0"]), float(w["x1"]))
        if lines and abs(lines[-1].top - float(w["top"])) <= _Y_TOLERANCE:
            lines[-1].words.append(word)
        else:
            lines.append(Line(page_number, float(w["top"]), [word]))
    for line in lines:
        line.words.sort(key=lambda w: w.x0)
    return lines


def document_lines(pdf: pdfplumber.PDF) -> list[Line]:
    out: list[Line] = []
    for i, page in enumerate(pdf.pages):
        out.extend(page_lines(page, i))
    return out


# --------------------------------------------------------------------------- header


@dataclass(frozen=True)
class Column:
    role: str
    x0: float
    x1: float


@dataclass(frozen=True)
class Header:
    columns: tuple[Column, ...]

    def get(self, role: str) -> Column | None:
        return next((c for c in self.columns if c.role == role), None)

    @property
    def numeric(self) -> list[Column]:
        return [c for c in self.columns if c.role in NUMERIC_ROLES]

    @property
    def has_balance(self) -> bool:
        return self.get("balance") is not None


def match_header(line: Line) -> Header | None:
    """The line is a transaction table header if it names a date, a description and money columns
    (debit + credit, or a signed amount), with at most two words that are not column names."""
    folded = [_fold_word(w.text) for w in line.words]
    columns: list[Column] = []
    unmatched = 0
    i = 0
    while i < len(folded):
        found = None
        for n in range(min(_MAX_PHRASE_WORDS, len(folded) - i), 0, -1):
            phrase = " ".join(folded[i:i + n])
            for role, phrases in HEADER_PHRASES.items():
                if phrase in phrases and all(c.role != role for c in columns):
                    found = (role, n)
                    break
            if found:
                break
        if found:
            role, n = found
            columns.append(Column(role, line.words[i].x0, line.words[i + n - 1].x1))
            i += n
        else:
            unmatched += 1
            i += 1
    roles = {c.role for c in columns}
    has_money = {"debit", "credit"} <= roles or "amount" in roles
    if not ({"date", "description"} <= roles and has_money) or unmatched > 2:
        return None
    return Header(tuple(columns))


# --------------------------------------------------------------------------- rows


@dataclass
class TableRow:
    """One transaction as printed. Strings are raw (unsanitized); never log or repr them."""

    page: int
    date_text: str = field(repr=False)
    description: str = field(repr=False)
    debit: Decimal | None = field(default=None, repr=False)
    credit: Decimal | None = field(default=None, repr=False)
    balance: Decimal | None = field(default=None, repr=False)
    value_date_text: str | None = field(default=None, repr=False)
    currency: str | None = None

    @property
    def signed(self) -> Decimal:
        return self.credit if self.credit is not None else -(self.debit or Decimal("0"))


@dataclass
class _AmountGroup:
    start: int  # word index range [start, end)
    end: int
    x0: float
    x1: float
    value: Decimal
    currency: str | None


def _amount_groups(words: list[Word], first: int) -> list[_AmountGroup]:
    """Amounts in ``words[first:]``: a number word, optionally joined with a neighbouring currency word or
    a thousands group split by a space (``1 234,56``)."""
    groups: list[_AmountGroup] = []
    i = first
    while i < len(words):
        w = words[i]
        if not _NUMBERISH.match(w.text):
            i += 1
            continue
        best: _AmountGroup | None = None
        # try "1 234,56" (two words) before the single word
        for n in (2, 1):
            if i + n > len(words):
                continue
            text = " ".join(x.text for x in words[i:i + n])
            if n == 2 and not (_NUMBERISH.match(words[i + 1].text) and words[i + 1].x0 - w.x1 < 4):
                continue
            try:
                value = parse_amount(text)
            except NormalizationError:
                continue
            best = _AmountGroup(i, i + n, w.x0, words[i + n - 1].x1, value, _symbol_currency(text))
            break
        if best is None:
            i += 1
            continue
        # a currency code right after (or right before) belongs to the amount
        if best.end < len(words) and _CURRENCY.match(words[best.end].text) and words[best.end].x0 - best.x1 < 12:
            best.currency, best.x1, best.end = words[best.end].text, words[best.end].x1, best.end + 1
        elif best.start > first and _CURRENCY.match(words[best.start - 1].text) and best.x0 - words[best.start - 1].x1 < 12:
            best.currency, best.x0, best.start = words[best.start - 1].text, words[best.start - 1].x0, best.start - 1
        groups.append(best)
        i = best.end
    return groups


def _symbol_currency(text: str) -> str | None:
    return next((code for symbol, code in _SYMBOL_CURRENCY.items() if symbol in text), None)


def _column_for(group: _AmountGroup, header: Header, tolerance: float = 40.0) -> Column | None:
    """The money column this amount sits in: the one it overlaps most, else the nearest right edge
    (amounts are usually right-aligned under their header) within ``tolerance`` points."""
    best, best_overlap = None, 0.0
    for col in header.numeric:
        overlap = min(group.x1, col.x1) - max(group.x0, col.x0)
        if overlap > best_overlap:
            best, best_overlap = col, overlap
    if best is not None:
        return best
    nearest = min(header.numeric, key=lambda c: abs(c.x1 - group.x1))
    return nearest if abs(nearest.x1 - group.x1) <= tolerance else None


def _leading_date(words: list[Word], header: Header) -> tuple[str, int] | None:
    """The date at the start of the line (1 to 3 words: ``03.08.2026``, ``Aug 3, 2026``, ``3 Aug 2026``)."""
    date_col = header.get("date")
    desc_col = header.get("description")
    if not words or date_col is None:
        return None
    limit = desc_col.x0 if desc_col and desc_col.x0 > date_col.x0 else date_col.x1 + 60
    if words[0].x0 < date_col.x0 - 15 or words[0].x0 >= limit:
        return None
    for n in (3, 2, 1):
        if len(words) < n:
            continue
        text = " ".join(w.text for w in words[:n])
        try:
            parse_date(text)
        except NormalizationError:
            continue
        return text, n
    return None


def _value_date(words: list[Word], start: int, header: Header) -> tuple[str, int, int] | None:
    col = header.get("value_date")
    if col is None:
        return None
    for i in range(start, len(words)):
        if abs(words[i].x0 - col.x0) > 25:
            continue
        for n in (3, 2, 1):
            text = " ".join(w.text for w in words[i:i + n])
            try:
                parse_date(text)
            except NormalizationError:
                continue
            return text, i, i + n
    return None


def parse_row(line: Line, header: Header) -> TableRow | None:
    words = line.words
    lead = _leading_date(words, header)
    if lead is None:
        return None
    date_text, used = lead
    consumed: set[int] = set(range(used))
    value_date_text = None
    if vd := _value_date(words, used, header):
        value_date_text = vd[0]
        consumed.update(range(vd[1], vd[2]))
    row = TableRow(line.page, date_text, "", value_date_text=value_date_text)
    money_left = min(c.x0 for c in header.numeric)
    for group in _amount_groups(words, used):
        if any(i in consumed for i in range(group.start, group.end)) or group.x1 < money_left - 40:
            continue  # numbers inside the description stay text
        col = _column_for(group, header)
        if col is None:
            continue
        consumed.update(range(group.start, group.end))
        row.currency = row.currency or group.currency
        if col.role == "debit":
            row.debit = abs(group.value)
        elif col.role == "credit":
            row.credit = abs(group.value)
        elif col.role == "amount":
            if group.value < 0:
                row.debit = -group.value
            else:
                row.credit = group.value
        else:
            row.balance = group.value
    if row.debit is None and row.credit is None:
        return None  # a dated line without money (e.g. "Sold la 31.08.2026" in the balance column)
    if row.debit is not None and row.credit is not None:
        if row.debit == 0:
            row.debit = None
        elif row.credit == 0:
            row.credit = None
    row.description = " ".join(w.text for i, w in enumerate(words) if i not in consumed)
    return row


# --------------------------------------------------------------------------- whole document


@dataclass
class TableScan:
    rows: list[TableRow] = field(default_factory=list, repr=False)
    headers: list[Header] = field(default_factory=list)
    lines: list[Line] = field(default_factory=list, repr=False)


def title_state(line: Line) -> bool | None:
    """True/False when the line is an include/exclude section title, None otherwise."""
    if len(line.words) > 14 or _amount_groups(line.words, 0):
        return None  # long lines are prose; lines with amounts are rows or totals
    text = fold(line.text)
    if any(text.startswith(t) for t in EXCLUDE_TITLES):
        return False
    if any(text.startswith(t) for t in INCLUDE_TITLES):
        return True
    return None


def scan(pdf: pdfplumber.PDF, *, continuation: bool) -> TableScan:
    """Read every transaction row under a recognised header. Rows in excluded sections (pending, reverted,
    blocked …) are skipped. With ``continuation``, a following line that sits entirely in the description
    column (and holds no amounts) is appended to the previous row's description."""
    result = TableScan(lines=document_lines(pdf))
    header: Header | None = None
    active = True
    last_row: TableRow | None = None
    last_top: tuple[int, float] | None = None
    for line in result.lines:
        if (h := match_header(line)) is not None:
            header = h
            result.headers.append(h)
            last_row = None
            continue
        state = title_state(line)
        if state is not None and (header is None or parse_row(line, header) is None):
            active, last_row = state, None
            continue
        if header is None or not active:
            continue
        if (row := parse_row(line, header)) is not None:
            result.rows.append(row)
            last_row, last_top = row, (line.page, line.top)
            continue
        if continuation and last_row is not None and last_top is not None:
            desc = header.get("description")
            money_left = min(c.x0 for c in header.numeric)
            same_block = line.page == last_top[0] and 0 < line.top - last_top[1] < 24
            inside = desc is not None and all(desc.x0 - 6 <= w.x0 and w.x1 <= money_left - 4 for w in line.words)
            if same_block and inside:
                last_row.description = f"{last_row.description} {line.text}".strip()
                last_top = (line.page, line.top)
                continue
        last_row = None
    return result


# --------------------------------------------------------------------------- statement facts


def find_period(lines: list[Line]) -> tuple[date, date] | None:
    for line in lines:
        if len(line.words) > 16:
            continue
        m = _PERIOD.search(line.text)
        if not m:
            continue
        try:
            start, end = parse_date(m.group(1)), parse_date(m.group(2))
        except NormalizationError:
            continue
        if start <= end:
            return start, end
    return None


def find_labelled_balance(lines: list[Line], labels: tuple[str, ...]) -> Decimal | None:
    """The first amount after one of ``labels`` on the same line (``Sold initial: 1.234,56 RON``)."""
    for line in lines:
        folded_words = [_fold_word(w.text) for w in line.words]
        joined = " ".join(folded_words)
        for label in labels:
            pos = joined.find(label)
            if pos < 0 or (pos > 0 and joined[pos - 1] != " "):
                continue
            label_end = len(joined[: pos + len(label)].split())
            groups = _amount_groups(line.words, label_end)
            if groups:
                return groups[0].value
    return None


def find_currency(lines: list[Line], rows: list[TableRow]) -> str | None:
    found = {r.currency for r in rows if r.currency}
    if len(found) == 1:
        return found.pop()
    for line in lines:
        if m := _CURRENCY_LINE.match(line.text.strip()):
            return m.group(1).upper()
    return None


def day_first(rows: list[TableRow]) -> bool:
    """Numeric dates are day-first unless a row proves month-first (``08/31/2026``)."""
    for r in rows:
        m = re.match(r"^([0-9]{1,2})[./-]([0-9]{1,2})[./-][0-9]{4}$", r.date_text.strip())
        if m:
            if int(m.group(1)) > 12:
                return True
            if int(m.group(2)) > 12:
                return False
    return True


def reconcile_running_balance(rows: list[TableRow]) -> tuple[list[TableRow], Decimal, Decimal] | None:
    """Check the printed running balance on every row and return the rows in chronological order with the
    opening and closing balance they imply. Statements printed newest-first are reversed. Rows without a
    printed balance are allowed (some banks print it once per day). Returns None when no row has a balance.
    Raises BALANCE_MISMATCH when the balances contradict the amounts in both directions."""
    if not any(r.balance is not None for r in rows):
        return None
    for ordered in (rows, list(reversed(rows))):
        result = _walk(ordered)
        if result is not None:
            return (ordered, *result)
    raise ExtractionError("BALANCE_MISMATCH", "The running balance printed on the statement does not match its transactions")


def _walk(rows: list[TableRow]) -> tuple[Decimal, Decimal] | None:
    last: Decimal | None = None
    pending = Decimal("0")
    opening: Decimal | None = None
    for row in rows:
        pending += row.signed
        if row.balance is None:
            continue
        if last is None:
            opening = row.balance - pending
        elif last + pending != row.balance:
            return None
        last, pending = row.balance, Decimal("0")
    assert last is not None and opening is not None
    return opening, last + pending


def to_raw_rows(rows: list[TableRow]) -> list[RawRow]:
    """Dates become ISO (``2026-08-03``) and amounts canonical (``1234.56``), so the pipeline needs no
    knowledge of the bank's formats. Raises UNSUPPORTED_LAYOUT on an unreadable date."""
    first = day_first(rows)
    out: list[RawRow] = []
    for r in rows:
        try:
            booking = parse_date(r.date_text, month_first=not first).isoformat()
            value = parse_date(r.value_date_text, month_first=not first).isoformat() if r.value_date_text else None
        except NormalizationError:
            raise ExtractionError("UNSUPPORTED_LAYOUT", "A table row has no valid booking date") from None
        out.append(RawRow(
            booking_date=booking,
            description=r.description,
            debit=format_amount(r.debit) if r.debit is not None else "",
            credit=format_amount(r.credit) if r.credit is not None else "",
            value_date=value,
        ))
    return out


def line_amounts(line: Line) -> list[tuple[Decimal, str | None]]:
    """Every amount printed on the line, left to right, with its currency when printed next to it."""
    return [(g.value, g.currency) for g in _amount_groups(line.words, 0)]
