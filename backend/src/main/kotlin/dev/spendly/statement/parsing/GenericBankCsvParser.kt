package dev.spendly.statement.parsing

import dev.spendly.shared.messaging.ParserInfo
import dev.spendly.shared.privacy.Descriptions
import dev.spendly.shared.privacy.Sanitizer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.springframework.stereotype.Component

/**
 * Generic Romanian bank CSV (samples/generic-bank-2026-08.csv): `;`-separated, a preamble before the header
 * `Data tranzactie;Data valuta;Descriere;Debit;Credit;Sold`, Romanian decimals (`1.234,56`), dates
 * `dd.MM.yyyy`, unsigned Debit/Credit columns, a running `Sold` column and a `Sold final` trailer.
 *
 * From the preamble only `Perioada`, `Moneda` and `Sold initial` are read; holder name, address and IBAN are
 * never extracted. Balances are used only for validation and are never returned.
 */
@Component
class GenericBankCsvParser(private val sanitizer: Sanitizer) : StatementParser {
    override val info = ParserInfo("kotlin-csv-generic", "1.0.0")

    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    override fun detect(text: String): Boolean =
        text.lineSequence().take(60).any { line -> isHeader(line.split(';')) }

    private fun isHeader(cells: List<String>): Boolean {
        val n = cells.map(::normLabel)
        return n.contains("DATA TRANZACTIE") && n.contains("DESCRIERE") && n.contains("DEBIT") && n.contains("CREDIT")
    }

    override fun parse(text: String, ctx: ParseContext): ParsedStatement {
        val records = Csv.parse(text, ';')
        val headerIndex = records.indexOfFirst(::isHeader)
        if (headerIndex < 0) throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Header row not found")
        val header = records[headerIndex].map(::normLabel)
        val colDate = header.indexOf("DATA TRANZACTIE")
        val colValue = header.indexOf("DATA VALUTA")
        val colDesc = header.indexOf("DESCRIERE")
        val colDebit = header.indexOf("DEBIT")
        val colCredit = header.indexOf("CREDIT")
        val colBalance = header.indexOf("SOLD")

        var periodStart: LocalDate? = null
        var periodEnd: LocalDate? = null
        var currency = ctx.accountCurrency
        var opening: BigDecimal? = null
        for (rec in records.subList(0, headerIndex)) {
            if (rec.size < 2) continue
            when (normLabel(rec[0])) {
                "PERIOADA" -> {
                    val parts = rec[1].split("-").map { it.trim() }
                    if (parts.size == 2) {
                        periodStart = date(parts[0], "period"); periodEnd = date(parts[1], "period")
                    }
                }
                "MONEDA" -> rec[1].trim().uppercase().takeIf { Regex("^[A-Z]{3}$").matches(it) }?.let { currency = it }
                "SOLD INITIAL" -> opening = amount(rec[1], "opening balance")
            }
        }

        val rows = ArrayList<ParsedTransaction>()
        var closing: BigDecimal? = null
        var running: BigDecimal? = opening
        for ((offset, rec) in records.subList(headerIndex + 1, records.size).withIndex()) {
            val line = headerIndex + 2 + offset
            if (Csv.isBlank(rec)) continue
            val first = rec.getOrElse(0) { "" }
            if (normLabel(first) == "SOLD FINAL") {
                closing = amount(rec.getOrElse(1) { "" }, "closing balance"); continue
            }
            val booking = tryDate(first) ?: continue // other trailer/summary lines are not transactions
            val debit = rec.getOrElse(colDebit) { "" }.trim()
            val credit = rec.getOrElse(colCredit) { "" }.trim()
            if (debit.isEmpty() && credit.isEmpty()) {
                throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Row at line $line has neither debit nor credit")
            }
            val value = (if (credit.isEmpty()) BigDecimal.ZERO.setScale(2) else amount(credit, "credit"))
                .subtract(if (debit.isEmpty()) BigDecimal.ZERO.setScale(2) else amount(debit, "debit"))

            // Sanitize first: the raw description never leaves this block.
            val clean = sanitizer.sanitize(rec.getOrElse(colDesc) { "" })
            rows += ParsedTransaction(
                bookingDate = booking,
                valueDate = if (colValue >= 0) tryDate(rec.getOrElse(colValue) { "" }) else null,
                amount = value,
                currency = currency,
                description = Descriptions.finalize(clean.text),
                counterparty = clean.counterparty(),
            )

            val rowBalance = if (colBalance >= 0) rec.getOrElse(colBalance) { "" }.trim().takeIf { it.isNotEmpty() } else null
            val balance = rowBalance?.let { amount(it, "balance") }
            running = when {
                running != null -> running.add(value)
                balance != null -> balance // first row without an opening balance: trust its running balance
                else -> null
            }
            if (balance != null && running != null && balance.compareTo(running) != 0) {
                throw ParseFailure(ParseFailure.BALANCE_MISMATCH, "Running balance does not reconcile at line $line")
            }
        }
        if (closing != null && running != null && closing.compareTo(running) != 0) {
            throw ParseFailure(ParseFailure.BALANCE_MISMATCH, "Parsed transactions do not reconcile with the statement's opening and closing balance")
        }
        if (rows.isEmpty() && periodStart == null) {
            throw ParseFailure(ParseFailure.NO_TRANSACTIONS, "The statement contains no transactions")
        }
        val start = periodStart ?: rows.minOf { it.bookingDate }
        val end = periodEnd ?: rows.maxOf { it.bookingDate }
        if (end.isBefore(start)) throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Statement period end is before its start")
        return ParsedStatement(info, start, end, currency, rows)
    }

    private fun tryDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim(), dateFormat)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun date(s: String, what: String): LocalDate =
        tryDate(s) ?: throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unreadable $what date")

    private fun amount(s: String, what: String): BigDecimal = try {
        Amounts.parse(s, ',')
    } catch (e: IllegalArgumentException) {
        throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unreadable $what amount")
    } catch (e: ArithmeticException) {
        throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unreadable $what amount")
    }
}
