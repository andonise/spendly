package dev.spendly.categorization

import dev.spendly.crypto.AesGcmCipher
import dev.spendly.events.AnswersSubmittedPayload
import dev.spendly.events.ConsumerNames
import dev.spendly.events.EventEnvelope
import dev.spendly.outbox.ProcessedEventsRepository
import dev.spendly.statement.ProcessingStep
import dev.spendly.statement.StatementRepository
import dev.spendly.statement.StatementStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** An answer not yet applied to its transaction. */
data class PendingAnswer(
    val answerId: UUID,
    val questionKind: String,
    val transactionId: UUID,
    val description: String,
    val category: Category?,
    val skipped: Boolean,
    val hint: String?,
    val applyToFuture: Boolean,
    val counterpartyToken: String?,
) {
    /** Skip → OTHER (never blocks the flow). */
    val effectiveCategory: Category get() = if (skipped) Category.OTHER else category ?: Category.OTHER

    override fun toString() = "PendingAnswer(answerId=$answerId, category=$category, skipped=$skipped)"
}

/**
 * Resume handler (group `spendly-backend-resume`, topic `spendly.answers-submitted`).
 * Embeddings for the new few-shot examples are computed first (no transaction); then ONE transaction applies
 * the answers to the transactions, saves the learning (alias when "apply to future", hint when text was given,
 * an example for every non-skipped answer), re-checks the statement for remaining unknowns and moves it to
 * NEEDS_INPUT (rare: new unknowns) or CATEGORIZED + TransactionsChanged.
 */
@Service
class ResumeService(
    private val statements: StatementRepository,
    private val processed: ProcessedEventsRepository,
    private val jdbc: JdbcClient,
    private val cipher: AesGcmCipher,
    private val learning: LearningRepository,
    private val store: CategorizationStore,
    private val embeddings: EmbeddingGateway,
    private val categorizer: CategorizerService,
    private val metrics: CategorizationMetrics,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(env: EventEnvelope<AnswersSubmittedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        val statementId = UUID.fromString(env.payload.statementId)
        if (processed.exists(eventId, CONSUMER)) {
            log.info("Duplicate AnswersSubmitted eventId={} ignored", eventId)
            return
        }
        val pending = pendingAnswers(statementId)
        val toLearn = pending.filter { !it.skipped && it.effectiveCategory != Category.INTERNAL_TRANSFER }
        val texts = toLearn.map { MerchantNormalizer.normalize(it.description)?.display ?: it.description }
        val vectors = embeddings.embed(texts) // null when the embedding model is down: examples are stored without vectors
        val vectorByAnswer = toLearn.mapIndexed { i, a -> a.answerId to vectors?.getOrNull(i) }.toMap()

        tx.executeWithoutResult {
            if (!processed.tryInsert(eventId, CONSUMER)) return@executeWithoutResult
            val statement = statements.lockById(statementId)
            if (statement == null) {
                log.warn("AnswersSubmitted eventId={} refers to unknown statementId={}; skipped", eventId, statementId)
                return@executeWithoutResult
            }
            if (statement.status != StatementStatus.CATEGORIZING) {
                log.info("AnswersSubmitted eventId={} for statement {} ignored: status is {}", eventId, statementId, statement.status)
                return@executeWithoutResult
            }
            // Re-read inside the transaction: only answers still unapplied.
            val current = pendingAnswers(statementId)
            current.forEach { apply(it, vectorByAnswer[it.answerId]) }
            metrics.categorized(CategorySource.USER, current.size)

            val unknowns = store.uncoveredUnknowns(statementId)
            if (unknowns.isNotEmpty()) {
                // Rare: transactions left without a category and without a question get a generic one.
                val decisions = unknowns.map { id ->
                    TxDecision(id, null, null, null, null, "Which category is this transaction?", emptyList(), "NONE")
                }
                metrics.questionsCreated("CATEGORY", store.createQuestions(statementId, decisions))
            }
            categorizer.finish(statement, env.correlationId, ProcessingStep.APPLY_ANSWERS)
            log.info("Applied {} answer(s) to statement {}", current.size, statementId)
        }
    }

    private fun apply(a: PendingAnswer, vector: FloatArray?) {
        val category = a.effectiveCategory
        if (category == Category.INTERNAL_TRANSFER) {
            jdbc.sql(
                """
                UPDATE transactions SET transfer_status = 'INTERNAL', transfer_reason = 'USER',
                    category = 'INTERNAL_TRANSFER', category_source = 'USER', confidence = 1
                WHERE id = :id
                """.trimIndent(),
            ).param("id", a.transactionId).update()
        } else {
            // An already-INTERNAL row (paired while waiting) keeps its transfer category.
            jdbc.sql(
                """
                UPDATE transactions SET category = :c, category_source = 'USER', confidence = 1
                WHERE id = :id AND transfer_status <> 'INTERNAL'
                """.trimIndent(),
            ).param("c", category.name).param("id", a.transactionId).update()
        }
        val merchant = MerchantNormalizer.normalize(a.description)
        val merchantId = merchant?.let { learning.upsertMerchant(it, if (a.applyToFuture && !a.skipped) category else null) }
        if (merchantId != null) {
            jdbc.sql("UPDATE transactions SET merchant_id = :m WHERE id = :id").param("m", merchantId).param("id", a.transactionId).update()
        }
        if (a.applyToFuture && !a.skipped && merchant != null && merchantId != null && category != Category.INTERNAL_TRANSFER) {
            learning.upsertUserAlias(merchant.key, merchantId)
        }
        if (!a.hint.isNullOrBlank()) learning.insertHint(merchantId, a.hint.trim())
        if (!a.skipped && category != Category.INTERNAL_TRANSFER) learning.insertExample(a.description, category, vector, a.answerId)
        jdbc.sql("UPDATE answers SET applied_at = now() WHERE id = :id").param("id", a.answerId).update()
    }

    private fun pendingAnswers(statementId: UUID): List<PendingAnswer> = jdbc.sql(
        """
        SELECT a.id, q.kind, q.transaction_id, t.description, a.category, a.skipped, a.hint, a.apply_to_future, t.counterparty_token
        FROM answers a JOIN questions q ON q.id = a.question_id JOIN transactions t ON t.id = q.transaction_id
        WHERE q.statement_id = :s AND a.applied_at IS NULL
        ORDER BY a.answered_at, a.id
        """.trimIndent(),
    ).param("s", statementId).query { rs, _ ->
        PendingAnswer(
            answerId = rs.getObject(1, UUID::class.java),
            questionKind = rs.getString(2),
            transactionId = rs.getObject(3, UUID::class.java),
            description = cipher.decrypt(rs.getString(4)),
            category = Category.parse(rs.getString(5)),
            skipped = rs.getBoolean(6),
            hint = rs.getString(7)?.let(cipher::decrypt),
            applyToFuture = rs.getBoolean(8),
            counterpartyToken = rs.getString(9),
        )
    }.list()

    companion object {
        const val CONSUMER = ConsumerNames.RESUME
    }
}
