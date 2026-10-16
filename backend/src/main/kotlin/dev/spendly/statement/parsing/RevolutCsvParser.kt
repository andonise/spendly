package dev.spendly.statement.parsing

import dev.spendly.shared.messaging.ParserInfo
import dev.spendly.shared.privacy.Descriptions
import dev.spendly.shared.privacy.Sanitizer
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.springframework.stereotype.Component

/**
 * Revolut CSV export: `Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance`.
 * Only `COMPLETED` rows are imported; a non-zero `Fee` becomes its own `FEE …` row right after the main one.
 * The running `Balance` (per product and currency) is used only to validate.
 */
@Component
class RevolutCsvParser(private val sanitizer: Sanitizer) : StatementParser {
    override val info = ParserInfo("kotlin-csv-revolut", "1.0.0")

    private val required = listOf("TYPE", "STARTED DATE", "COMPLETED DATE", "DESCRIPTION", "AMOUNT", "FEE", "CURRENCY", "STATE")

    override fun detect(text: String): Boolean {
        val first = text.removePrefix("﻿").lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        val cells = Csv.parse(first, ',').firstOrNull()?.map(::normLabel) ?: return false
        return cells.containsAll(required)
    }

    override fun parse(text: String, ctx: ParseContext): ParsedStatement {
        val records = Csv.parse(text, ',').filterNot(Csv::isBlank)
        if (records.isEmpty()) throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Header row not found")
        val header = records[0].map(::normLabel)
        if (!header.containsAll(required)) throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Header row not found")
        val col = { name: String -> header.indexOf(name) }
        val cProduct = col("PRODUCT")
        val cStarted = col("STARTED DATE")
        val cCompleted = col("COMPLETED DATE")
        val cDesc = col("DESCRIPTION")
        val cAmount = col("AMOUNT")
        val cFee = col("FEE")
        val cCurrency = col("CURRENCY")
        val cState = col("STATE")
        val cBalance = col("BALANCE")

        val rows = ArrayList<ParsedTransaction>()
        val balances = HashMap<String, BigDecimal>() // (product|currency) -> last balance
        for ((offset, rec) in records.drop(1).withIndex()) {
            val line = offset + 2
            val cell = { i: Int -> if (i >= 0) rec.getOrElse(i) { "" }.trim() else "" }
            if (cell(cState).uppercase() != "COMPLETED") continue

            val booking = date(cell(cCompleted).ifEmpty { cell(cStarted) }, line)
            val currency = cell(cCurrency).uppercase()
            if (!Regex("^[A-Z]{3}$").matches(currency)) {
                throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unreadable currency at line $line")
            }
            val amount = amount(cell(cAmount), line)
            val fee = cell(cFee).takeIf { it.isNotEmpty() }?.let { amount(it, line).abs() } ?: BigDecimal.ZERO.setScale(2)

            // Sanitize first: the raw description never leaves this block.
            val clean = sanitizer.sanitize(cell(cDesc))
            val description = Descriptions.finalize(clean.text)
            rows += ParsedTransaction(booking, null, amount, currency, description, clean.counterparty())
            if (fee.signum() != 0) {
                rows += ParsedTransaction(booking, null, fee.negate(), currency, Descriptions.fee(description), null)
            }

            val balanceText = cell(cBalance)
            if (balanceText.isNotEmpty()) {
                val balance = amount(balanceText, line)
                val key = cell(cProduct) + "|" + currency
                val previous = balances[key]
                if (previous != null && previous.add(amount).subtract(fee).compareTo(balance) != 0) {
                    throw ParseFailure(ParseFailure.BALANCE_MISMATCH, "Running balance does not reconcile at line $line")
                }
                balances[key] = balance
            }
        }
        if (rows.isEmpty()) throw ParseFailure(ParseFailure.NO_TRANSACTIONS, "The statement contains no completed transactions")
        return ParsedStatement(
            parser = info,
            periodStart = rows.minOf { it.bookingDate },
            periodEnd = rows.maxOf { it.bookingDate },
            currency = ctx.accountCurrency,
            transactions = rows,
        )
    }

    private fun date(s: String, line: Int): LocalDate = try {
        LocalDate.parse(s.trim().take(10))
    } catch (e: DateTimeParseException) {
        throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unreadable date at line $line")
    }

    private fun amount(s: String, line: Int): BigDecimal = try {
        Amounts.parse(s, '.')
    } catch (e: IllegalArgumentException) {
        throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unreadable amount at line $line")
    } catch (e: ArithmeticException) {
        throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unreadable amount at line $line")
    }
}
