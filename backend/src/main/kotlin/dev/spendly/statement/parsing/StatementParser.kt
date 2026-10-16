package dev.spendly.statement.parsing

import dev.spendly.shared.messaging.ImportedTransaction
import dev.spendly.shared.messaging.ParserInfo
import dev.spendly.shared.privacy.Sanitizer
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

data class ParseContext(val accountId: UUID, val accountCurrency: String, val bank: String)

/**
 * One parsed row. [description] is already the FINAL sanitized description (contracts "Description" rule);
 * raw text never leaves the parser. Opening/closing balances are local to the parser and never appear here.
 */
data class ParsedTransaction(
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val counterparty: Pair<String, Sanitizer.CounterpartyKind>?,
) {
    override fun toString() = "ParsedTransaction(bookingDate=$bookingDate, <redacted>)"
}

data class ParsedStatement(
    val parser: ParserInfo,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val currency: String,
    val transactions: List<ParsedTransaction>,
)

/** A statement layout. Implementations sanitize every description before anything else touches it. */
interface StatementParser {
    val info: ParserInfo

    /** True when [text] has this parser's header row. */
    fun detect(text: String): Boolean

    /** Parses, sanitizes, normalizes and validates against balances when the file has them. */
    fun parse(text: String, ctx: ParseContext): ParsedStatement
}

/** txHash and sequence per contracts/README.md "Transaction hash". */
object TxHashes {
    fun hash(accountId: UUID, bookingDate: LocalDate, amount: String, currency: String, description: String, occurrence: Int): String {
        val joined = listOf(accountId.toString(), bookingDate.toString(), amount, currency, description, occurrence.toString())
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun toEvent(accountId: UUID, rows: List<ParsedTransaction>): List<ImportedTransaction> {
        val seen = HashMap<List<String>, Int>()
        return rows.mapIndexed { index, t ->
            val amount = Amounts.canonical(t.amount)
            val key = listOf(t.bookingDate.toString(), amount, t.currency, t.description)
            val occurrence = seen.getOrDefault(key, 0)
            seen[key] = occurrence + 1
            ImportedTransaction(
                txHash = hash(accountId, t.bookingDate, amount, t.currency, t.description, occurrence),
                sequence = index,
                bookingDate = t.bookingDate.toString(),
                valueDate = t.valueDate?.toString(),
                amount = amount,
                currency = t.currency,
                description = t.description,
                counterpartyToken = t.counterparty?.first,
                counterpartyTokenKind = t.counterparty?.second?.name,
            )
        }
    }
}
