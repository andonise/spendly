package dev.spendly.transaction.domain

import dev.spendly.shared.crypto.AesGcmCipher
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.FieldViolation
import dev.spendly.shared.web.NotFoundException
import dev.spendly.transaction.Category
import dev.spendly.transaction.CounterpartyRef
import dev.spendly.transaction.TransferStatus
import io.swagger.v3.oas.annotations.tags.Tag
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class TransactionResponse(
    val id: UUID,
    val accountId: UUID,
    val accountLabel: String,
    val statementId: UUID,
    val bookingDate: LocalDate,
    val amount: String,
    val currency: String,
    val amountBase: String?,
    val fxRate: String?,
    val description: String,
    val category: String?,
    val categorySource: String?,
    val confidence: Double?,
    val transferStatus: String,
    val transferPairId: UUID?,
    val pairedTransactionId: UUID?,
    val counterparty: CounterpartyRef?,
)

data class TransactionPage(val items: List<TransactionResponse>, val page: Int, val size: Int, val totalItems: Long, val totalPages: Int)

data class TransactionFilter(
    val accountId: UUID? = null,
    val statementId: UUID? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val category: String? = null,
    val transferStatus: String? = null,
)

/** Read side of `transactions` (decrypted, sanitized descriptions), per contracts/openapi-draft.yaml. */
@Service
class TransactionQueryService(private val jdbc: JdbcClient, private val cipher: AesGcmCipher) {

    fun page(filter: TransactionFilter, page: Int, size: Int, sort: String): TransactionPage {
        if (page < 0) throw bad("page", "must be >= 0")
        if (size !in 1..200) throw bad("size", "must be between 1 and 200")
        val orderBy = SORTS[sort] ?: throw bad("sort", "one of ${SORTS.keys.joinToString()}")
        filter.category?.let { if (Category.parse(it) == null) throw bad("category", "not a category") }
        filter.transferStatus?.let { v -> if (TransferStatus.entries.none { it.name == v }) throw bad("transferStatus", "not a transfer status") }
        if (filter.from != null && filter.to != null && filter.to.isBefore(filter.from)) throw bad("to", "must not be before from")

        val total = bind(jdbc.sql("SELECT count(*) FROM transactions t WHERE $WHERE"), filter).query(Long::class.java).single()
        val items = bind(jdbc.sql("$SELECT WHERE $WHERE ORDER BY $orderBy LIMIT :limit OFFSET :offset"), filter)
            .param("limit", size).param("offset", page.toLong() * size)
            .query(::map).list()
        val totalPages = if (total == 0L) 0 else ((total + size - 1) / size).toInt()
        return TransactionPage(items, page, size, total, totalPages)
    }

    fun get(id: UUID): TransactionResponse =
        jdbc.sql("$SELECT WHERE t.id = :id").param("id", id).query(::map).optional().orElseThrow { NotFoundException("Transaction $id") }

    private fun bind(spec: JdbcClient.StatementSpec, f: TransactionFilter): JdbcClient.StatementSpec = spec
        .param("accountId", f.accountId?.toString()).param("statementId", f.statementId?.toString())
        .param("from", f.from?.toString()).param("to", f.to?.toString())
        .param("category", f.category?.uppercase()).param("transferStatus", f.transferStatus)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = TransactionResponse(
        id = rs.getObject("id", UUID::class.java),
        accountId = rs.getObject("account_id", UUID::class.java),
        accountLabel = rs.getString("account_label"),
        statementId = rs.getObject("statement_id", UUID::class.java),
        bookingDate = rs.getObject("booking_date", LocalDate::class.java),
        amount = money(rs.getBigDecimal("amount"))!!,
        currency = rs.getString("currency"),
        amountBase = money(rs.getBigDecimal("amount_base")),
        fxRate = rs.getBigDecimal("fx_rate")?.stripTrailingZeros()?.let { if (it.scale() < 4) it.setScale(4) else it }?.toPlainString(),
        description = cipher.decrypt(rs.getString("description")),
        category = rs.getString("category"),
        categorySource = rs.getString("category_source"),
        confidence = rs.getBigDecimal("confidence")?.toDouble(),
        transferStatus = rs.getString("transfer_status"),
        transferPairId = rs.getObject("transfer_pair_id", UUID::class.java),
        pairedTransactionId = rs.getObject("paired_id", UUID::class.java),
        counterparty = CounterpartyRef.from(rs),
    )

    private fun bad(field: String, message: String) = BadRequestException("VALIDATION_ERROR", "Invalid $field: $message", listOf(FieldViolation(field, message)))

    companion object {
        private val SORTS = mapOf(
            "bookingDate,desc" to "t.booking_date DESC, t.sequence DESC, t.id",
            "bookingDate,asc" to "t.booking_date ASC, t.sequence ASC, t.id",
            "amount,desc" to "t.amount DESC, t.booking_date DESC, t.id",
            "amount,asc" to "t.amount ASC, t.booking_date DESC, t.id",
        )
        private val SELECT = """
            SELECT t.id, t.account_id, a.label AS account_label, t.statement_id, t.booking_date, t.amount, t.currency,
                   t.amount_base, t.fx_rate, t.description, t.category, t.category_source, t.confidence, t.transfer_status,
                   t.transfer_pair_id,
                   CASE WHEN p.outgoing_tx_id = t.id THEN p.incoming_tx_id ELSE p.outgoing_tx_id END AS paired_id,
                   t.counterparty_token, t.counterparty_token_kind, c.user_label, c.is_own_account, oa.id AS own_account_id
            FROM transactions t
            JOIN accounts a ON a.id = t.account_id
            LEFT JOIN transfer_pairs p ON p.id = t.transfer_pair_id
            LEFT JOIN counterparties c ON c.token = t.counterparty_token
            LEFT JOIN accounts oa ON oa.iban_hash = t.counterparty_token
        """.trimIndent()
        private val WHERE = """
            (CAST(:accountId AS uuid) IS NULL OR t.account_id = CAST(:accountId AS uuid))
            AND (CAST(:statementId AS uuid) IS NULL OR t.statement_id = CAST(:statementId AS uuid))
            AND (CAST(:from AS date) IS NULL OR t.booking_date >= CAST(:from AS date))
            AND (CAST(:to AS date) IS NULL OR t.booking_date <= CAST(:to AS date))
            AND (CAST(:category AS varchar) IS NULL OR t.category = CAST(:category AS varchar))
            AND (CAST(:transferStatus AS varchar) IS NULL OR t.transfer_status = CAST(:transferStatus AS varchar))
        """.trimIndent()

        fun money(v: BigDecimal?): String? = v?.setScale(2, RoundingMode.HALF_EVEN)?.toPlainString()
    }
}
