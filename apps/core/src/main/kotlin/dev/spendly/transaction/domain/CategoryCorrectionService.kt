package dev.spendly.transaction.domain

import dev.spendly.shared.crypto.AesGcmCipher
import dev.spendly.shared.messaging.ChangeCause
import dev.spendly.shared.outbox.DomainEvents
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.ConflictException
import dev.spendly.shared.web.FieldViolation
import dev.spendly.shared.web.NotFoundException
import dev.spendly.transaction.Category
import dev.spendly.transaction.CategoryLearning
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

data class CategoryUpdate(val category: String?, val applyToFuture: Boolean = false)

/**
 * `PATCH /api/transactions/{id}/category` (plan section 7, "Manual category correction"). One transaction:
 * category (source USER) + optional learning + outbox TransactionsChanged (MANUAL_CORRECTION) for the transaction's
 * account and date, so every report covering that day becomes STALE.
 */
@Service
class CategoryCorrectionService(
    private val jdbc: JdbcClient,
    private val cipher: AesGcmCipher,
    private val learning: CategoryLearning,
    private val events: DomainEvents,
    private val queries: TransactionQueryService,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Target(val accountId: UUID, val date: LocalDate, val description: String, val transferStatus: String, val statementStatus: String)

    fun correct(id: UUID, update: CategoryUpdate): TransactionResponse {
        val category = Category.parse(update.category) ?: throw bad("category", "not a category")
        if (category == Category.INTERNAL_TRANSFER) throw bad("category", "INTERNAL_TRANSFER is set by transfer detection or transfer questions")
        tx.executeWithoutResult {
            val t = jdbc.sql(
                """
                SELECT t.account_id, t.booking_date, t.description, t.transfer_status, s.status
                FROM transactions t JOIN statements s ON s.id = t.statement_id
                WHERE t.id = :id FOR UPDATE OF t
                """.trimIndent(),
            ).param("id", id).query { rs, _ ->
                Target(rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getString(4), rs.getString(5))
            }.optional().orElseThrow { NotFoundException("Transaction $id") }
            if (t.statementStatus != "CATEGORIZED") {
                throw ConflictException("STATEMENT_NOT_CATEGORIZED", "The transaction's statement is not CATEGORIZED; answer its questions instead")
            }
            if (t.transferStatus == "INTERNAL" || t.transferStatus == "CANDIDATE") {
                throw ConflictException("TRANSACTION_IS_TRANSFER", "The transaction is a transfer between your own accounts")
            }
            jdbc.sql("UPDATE transactions SET category = :c, category_source = 'USER', confidence = 1 WHERE id = :id")
                .param("c", category.name).param("id", id).update()
            if (update.applyToFuture) learning.learn(id, cipher.decrypt(t.description), category)
            events.transactionsChanged(listOf(t.accountId), t.date, t.date, ChangeCause.MANUAL_CORRECTION, null, MDC.get("correlationId") ?: "correction-$id")
        }
        log.info("Transaction {} recategorized (applyToFuture={})", id, update.applyToFuture)
        return queries.get(id)
    }

    private fun bad(field: String, message: String) = BadRequestException("VALIDATION_ERROR", "Invalid $field: $message", listOf(FieldViolation(field, message)))
}
