"""PDF -> sanitized, normalized, validated event. Pure: no Kafka, no file system writes.

Order of operations per row: sanitize the description FIRST, then normalize dates/amounts, then hash.
Balances read from the document are used only for the reconciliation check and never leave this module.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from decimal import Decimal
from typing import Any, BinaryIO

from . import events
from .contracts import Contracts
from .extraction import ExtractionError, LayoutParser, RawStatement, extract_statement
from .extraction.normalize import NormalizationError, format_amount, parse_ro_date, parse_ro_decimal
from .sanitizer import Sanitizer

MAX_DESCRIPTION = 500
EMPTY_DESCRIPTION = "N/A"

FAILURE_MESSAGES = {
    "BALANCE_MISMATCH": "Parsed transactions do not reconcile with the statement's opening and closing balance",
    "NO_TRANSACTIONS": "The statement table contains no transactions",
    "SCHEMA_VALIDATION": "The parsed result does not match the TransactionsImported contract",
    "INTERNAL_ERROR": "Unexpected error while parsing the statement",
}


@dataclass(frozen=True)
class StatementContext:
    statement_id: str
    account_id: str
    currency: str
    correlation_id: str


@dataclass(frozen=True)
class ValidationSummary:
    """Safe to return from /parse: counts and totals of emitted transactions, never balances."""

    balanced: bool | None
    schema_valid: bool
    transaction_count: int
    debit_count: int
    credit_count: int
    debit_total: str
    credit_total: str
    layout: str | None
    schema_errors: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, Any]:
        return {
            "balanced": self.balanced,
            "schemaValid": self.schema_valid,
            "transactionCount": self.transaction_count,
            "debitCount": self.debit_count,
            "creditCount": self.credit_count,
            "debitTotal": self.debit_total,
            "creditTotal": self.credit_total,
            "layout": self.layout,
            "schemaErrors": list(self.schema_errors),
        }


@dataclass(frozen=True)
class ParseOutcome:
    event: dict[str, Any]
    summary: ValidationSummary

    @property
    def event_type(self) -> str:
        return self.event["eventType"]

    @property
    def succeeded(self) -> bool:
        return self.event_type == "TransactionsImported"

    @property
    def reason(self) -> str | None:
        return None if self.succeeded else self.event["payload"]["reason"]


def tx_hash(account_id: str, booking_date: str, amount: str, currency: str, description: str, occurrence: int) -> str:
    joined = "|".join([account_id, booking_date, amount, currency, description, str(occurrence)])
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def _empty_summary(layout: str | None = None) -> ValidationSummary:
    return ValidationSummary(None, False, 0, 0, 0, "0.00", "0.00", layout)


def failure(ctx: StatementContext, reason: str, message: str | None = None, *, retryable: bool = False,
            summary: ValidationSummary | None = None, contracts: Contracts | None = None) -> ParseOutcome:
    event = events.statement_parse_failed(
        statement_id=ctx.statement_id,
        account_id=ctx.account_id,
        correlation_id=ctx.correlation_id,
        reason=reason,
        message=message or FAILURE_MESSAGES.get(reason, reason),
        retryable=retryable,
    )
    if contracts is not None:
        errors = contracts.validate(event)
        if errors:  # should never happen; a broken failure event must not be published
            raise RuntimeError("StatementParseFailed event failed schema validation")
    return ParseOutcome(event, summary or _empty_summary())


def parse_pdf(source: bytes | BinaryIO, ctx: StatementContext, sanitizer: Sanitizer, contracts: Contracts,
              layouts: list[LayoutParser] | None = None) -> ParseOutcome:
    try:
        raw = extract_statement(source, layouts)
    except ExtractionError as exc:
        return failure(ctx, exc.reason, exc.message, contracts=contracts)
    return build_from_raw(raw, ctx, sanitizer, contracts)


def build_from_raw(raw: RawStatement, ctx: StatementContext, sanitizer: Sanitizer, contracts: Contracts) -> ParseOutcome:
    if not raw.rows:
        return failure(ctx, "NO_TRANSACTIONS", summary=_empty_summary(raw.layout), contracts=contracts)
    if raw.currency and raw.currency != ctx.currency:
        return failure(ctx, "UNSUPPORTED_LAYOUT", "The statement currency does not match the account currency",
                       summary=_empty_summary(raw.layout), contracts=contracts)
    if raw.opening_balance is None or raw.closing_balance is None:
        return failure(ctx, "UNSUPPORTED_LAYOUT", "Opening or closing balance not found; the statement cannot be validated",
                       summary=_empty_summary(raw.layout), contracts=contracts)

    transactions: list[dict[str, Any]] = []
    seen: dict[tuple[str, str, str, str], int] = {}
    total = Decimal("0.00")
    debits = credits = Decimal("0.00")
    debit_count = credit_count = 0
    for sequence, row in enumerate(raw.rows):
        # 1. sanitize first
        clean = sanitizer.sanitize(row.description)
        description = clean.text[:MAX_DESCRIPTION].strip() or EMPTY_DESCRIPTION
        # 2. normalize
        try:
            booking = parse_ro_date(row.booking_date)
            value_date = parse_ro_date(row.value_date) if row.value_date else None
            debit = parse_ro_decimal(row.debit, allow_negative=False) if row.debit else None
            credit = parse_ro_decimal(row.credit, allow_negative=False) if row.credit else None
        except NormalizationError:
            return failure(ctx, "UNSUPPORTED_LAYOUT", "A transaction row has an unreadable date or amount",
                           summary=_empty_summary(raw.layout), contracts=contracts)
        if (debit is None) == (credit is None):
            return failure(ctx, "UNSUPPORTED_LAYOUT", "A transaction row must have exactly one of debit or credit",
                           summary=_empty_summary(raw.layout), contracts=contracts)
        if debit is not None:
            amount_value = -debit
            debits += debit
            debit_count += 1
        else:
            amount_value = credit  # type: ignore[assignment]
            credits += credit  # type: ignore[operator]
            credit_count += 1
        total += amount_value
        amount = format_amount(amount_value)
        booking_iso = booking.isoformat()
        # 3. hash (occurrence = earlier identical rows in this file)
        key = (booking_iso, amount, ctx.currency, description)
        occurrence = seen.get(key, 0)
        seen[key] = occurrence + 1
        iban_token = clean.first_token("iban")
        person_token = clean.first_token("person_name")
        counterparty, kind = (iban_token, "IBAN") if iban_token else ((person_token, "PERSON") if person_token else (None, None))
        transactions.append(
            {
                "txHash": tx_hash(ctx.account_id, booking_iso, amount, ctx.currency, description, occurrence),
                "sequence": sequence,
                "bookingDate": booking_iso,
                "valueDate": value_date.isoformat() if value_date else None,
                "amount": amount,
                "currency": ctx.currency,
                "description": description,
                "counterpartyToken": counterparty,
                "counterpartyTokenKind": kind,
            }
        )

    balanced = raw.opening_balance + total == raw.closing_balance
    dates = [t["bookingDate"] for t in transactions]
    period_start = raw.period_start.isoformat() if raw.period_start else min(dates)
    period_end = raw.period_end.isoformat() if raw.period_end else max(dates)

    event = events.transactions_imported(
        statement_id=ctx.statement_id,
        account_id=ctx.account_id,
        correlation_id=ctx.correlation_id,
        period_start=period_start,
        period_end=period_end,
        currency=ctx.currency,
        transactions=transactions,
    )
    errors = tuple(contracts.validate(event))
    summary = ValidationSummary(
        balanced=balanced,
        schema_valid=not errors,
        transaction_count=len(transactions),
        debit_count=debit_count,
        credit_count=credit_count,
        debit_total=format_amount(debits),
        credit_total=format_amount(credits),
        layout=raw.layout,
        schema_errors=errors,
    )
    if not balanced:
        return failure(ctx, "BALANCE_MISMATCH", summary=summary, contracts=contracts)
    if errors:
        return failure(ctx, "SCHEMA_VALIDATION", summary=summary, contracts=contracts)
    return ParseOutcome(event, summary)
