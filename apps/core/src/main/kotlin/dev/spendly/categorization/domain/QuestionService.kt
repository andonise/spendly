package dev.spendly.categorization.domain

import dev.spendly.shared.crypto.AesGcmCipher
import dev.spendly.shared.messaging.ChangeCause
import dev.spendly.shared.outbox.DomainEvents
import dev.spendly.shared.privacy.Sanitizer
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.ConflictException
import dev.spendly.shared.web.FieldViolation
import dev.spendly.shared.web.NotFoundException
import dev.spendly.statement.StatementLifecycle
import dev.spendly.statement.StatementRecord
import dev.spendly.statement.StatementStatus
import dev.spendly.transaction.Category
import dev.spendly.transaction.CounterpartyRef
import dev.spendly.transfer.TransferApi
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

data class QuestionTransaction(
    val id: UUID,
    val accountId: UUID,
    val accountLabel: String,
    val bookingDate: LocalDate,
    val amount: String,
    val currency: String,
    val amountBase: String?,
    val description: String,
    val counterparty: CounterpartyRef?,
)

data class QuestionResponse(
    val id: UUID,
    val kind: String,
    val statementId: UUID?,
    val transaction: QuestionTransaction,
    val questionText: String,
    val suggestions: List<String>,
    val status: String,
)

/** Answer body (contracts/openapi-draft.yaml `Answer`): exactly one of `category` or `skip=true`. */
data class AnswerRequest(
    val category: String? = null,
    val skip: Boolean? = false,
    val hint: String? = null,
    val applyToFuture: Boolean? = false,
)

data class AnswerItemRequest(
    val questionId: UUID? = null,
    val category: String? = null,
    val skip: Boolean? = false,
    val hint: String? = null,
    val applyToFuture: Boolean? = false,
) {
    fun answer() = AnswerRequest(category, skip, hint, applyToFuture)
}

data class AnswersSubmission(val answers: List<AnswerItemRequest>? = null)

/** A validated answer. */
private data class ValidAnswer(val category: Category?, val skipped: Boolean, val hint: String?, val applyToFuture: Boolean)

/**
 * Human-in-the-loop questions (plan sections 5, 14). Submitting a statement's answers is ONE transaction:
 * guarded NEEDS_INPUT → CATEGORIZING (0 rows → 409) + answers + question statuses + outbox AnswersSubmitted.
 */
@Service
class QuestionService(
    private val jdbc: JdbcClient,
    private val cipher: AesGcmCipher,
    private val statements: StatementLifecycle,
    private val events: DomainEvents,
    private val transfers: TransferApi,
    private val sanitizer: Sanitizer,
    private val jsonMapper: JsonMapper,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun forStatement(statementId: UUID): List<QuestionResponse> {
        statements.find(statementId) ?: throw NotFoundException("Statement $statementId")
        return query("q.statement_id = :s", mapOf("s" to statementId))
    }

    fun list(status: String?): List<QuestionResponse> {
        val s = (status ?: "OPEN").uppercase()
        if (s !in setOf("OPEN", "ANSWERED", "SKIPPED")) {
            throw BadRequestException("INVALID_STATUS", "status must be OPEN, ANSWERED or SKIPPED", listOf(FieldViolation("status", "invalid value")))
        }
        return query("q.status = :status", mapOf("status" to s))
    }

    fun get(id: UUID): QuestionResponse = query("q.id = :id", mapOf("id" to id)).firstOrNull() ?: throw NotFoundException("Question $id")

    fun submitAnswers(statementId: UUID, body: AnswersSubmission, correlationId: String): StatementRecord {
        statements.find(statementId) ?: throw NotFoundException("Statement $statementId")
        val items = body.answers
        if (items.isNullOrEmpty()) {
            throw BadRequestException("VALIDATION_ERROR", "answers must contain at least one answer", listOf(FieldViolation("answers", "must not be empty")))
        }
        val parsed = items.mapIndexed { i, item ->
            val qid = item.questionId ?: throw BadRequestException("VALIDATION_ERROR", "questionId is required", listOf(FieldViolation("answers[$i].questionId", "required")))
            qid to validate(item.answer(), "answers[$i]")
        }
        if (parsed.map { it.first }.toSet().size != parsed.size) {
            throw BadRequestException("DUPLICATE_ANSWER", "Each question may be answered once")
        }
        tx.executeWithoutResult {
            val statement = statements.lock(statementId)!!
            if (!statements.tryTransition(statementId, StatementStatus.NEEDS_INPUT, StatementStatus.CATEGORIZING)) {
                throw ConflictException("STATEMENT_NOT_IN_NEEDS_INPUT", "Answers are accepted only while the statement is NEEDS_INPUT")
            }
            val open = jdbc.sql("SELECT id FROM questions WHERE statement_id = :s AND status = 'OPEN' FOR UPDATE")
                .param("s", statementId).query(UUID::class.java).list().toSet()
            val unknown = parsed.map { it.first }.filterNot(open::contains)
            if (unknown.isNotEmpty()) {
                throw BadRequestException("UNKNOWN_QUESTION", "Some answers are not for an open question of this statement",
                    unknown.map { FieldViolation("questionId", "$it is not an open question of this statement") })
            }
            val missing = open - parsed.map { it.first }.toSet()
            if (missing.isNotEmpty()) {
                throw BadRequestException("ANSWERS_INCOMPLETE", "Every open question must be answered or skipped (${missing.size} missing)")
            }
            parsed.forEach { (qid, a) -> insertAnswer(qid, a, applied = false) }
            events.answersSubmitted(statementId, statement.accountId, parsed.size, correlationId)
        }
        log.info("Answers submitted for statement {} ({} answers)", statementId, parsed.size)
        return statements.find(statementId)!!
    }

    /**
     * POST /api/questions/{id}/answer, for TRANSFER questions: INTERNAL_TRANSFER = yes (INTERNAL); any other
     * category = no (EXTERNAL with that category); skip = EXTERNAL + OTHER. `applyToFuture` on a yes marks the
     * counterparty as an own account. Publishes TransactionsChanged (TRANSFER_ANSWERED).
     */
    fun answerQuestion(questionId: UUID, body: AnswerRequest, correlationId: String): QuestionResponse {
        val a = validate(body, "")
        tx.executeWithoutResult {
            data class Q(val kind: String, val status: String, val txId: UUID, val accountId: UUID, val date: LocalDate, val token: String?)
            val q = jdbc.sql(
                """
                SELECT q.kind, q.status, q.transaction_id, t.account_id, t.booking_date, t.counterparty_token
                FROM questions q JOIN transactions t ON t.id = q.transaction_id WHERE q.id = :id FOR UPDATE OF q
                """.trimIndent(),
            ).param("id", questionId).query { rs, _ ->
                Q(rs.getString(1), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java),
                    rs.getObject(5, LocalDate::class.java), rs.getString(6))
            }.optional().orElseThrow { NotFoundException("Question $questionId") }
            if (q.status != "OPEN") throw ConflictException("QUESTION_NOT_OPEN", "The question is already ${q.status}")
            if (q.kind != "TRANSFER") {
                throw BadRequestException("USE_STATEMENT_ANSWERS", "CATEGORY questions are answered with POST /api/statements/{id}/answers")
            }
            insertAnswer(questionId, a, applied = true)
            val category = if (a.skipped) Category.OTHER else a.category!!
            if (category == Category.INTERNAL_TRANSFER) {
                jdbc.sql(
                    """
                    UPDATE transactions SET transfer_status = 'INTERNAL', transfer_reason = 'USER',
                        category = 'INTERNAL_TRANSFER', category_source = 'USER', confidence = 1 WHERE id = :id
                    """.trimIndent(),
                ).param("id", q.txId).update()
                if (a.applyToFuture && q.token != null) transfers.markOwnCounterparty(q.token, correlationId)
            } else {
                jdbc.sql(
                    """
                    UPDATE transactions SET transfer_status = 'EXTERNAL', transfer_reason = 'USER',
                        category = :c, category_source = 'USER', confidence = 1 WHERE id = :id
                    """.trimIndent(),
                ).param("c", category.name).param("id", q.txId).update()
            }
            events.transactionsChanged(listOf(q.accountId), q.date, q.date, ChangeCause.TRANSFER_ANSWERED, null, correlationId)
        }
        return get(questionId)
    }

    private fun insertAnswer(questionId: UUID, a: ValidAnswer, applied: Boolean) {
        jdbc.sql(
            """
            INSERT INTO answers (id, question_id, category, skipped, hint, apply_to_future, applied_at)
            VALUES (:id, :q, :c, :skipped, :hint, :future, CASE WHEN :applied THEN now() END)
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("q", questionId).param("c", a.category?.name).param("skipped", a.skipped)
            .param("hint", a.hint?.let(cipher::encrypt)).param("future", a.applyToFuture).param("applied", applied).update()
        jdbc.sql("UPDATE questions SET status = :st, resolved_at = now() WHERE id = :id")
            .param("st", if (a.skipped) "SKIPPED" else "ANSWERED").param("id", questionId).update()
    }

    private fun validate(a: AnswerRequest, path: String): ValidAnswer {
        val prefix = if (path.isEmpty()) "" else "$path."
        val skip = a.skip == true
        val category = a.category?.let {
            Category.parse(it) ?: throw BadRequestException("INVALID_CATEGORY", "Unknown category", listOf(FieldViolation("${prefix}category", "not a category")))
        }
        if (skip == (category != null)) {
            if (!(skip && category == null)) {
                throw BadRequestException("VALIDATION_ERROR", "Give exactly one of category or skip=true",
                    listOf(FieldViolation("${prefix}category", "exactly one of category or skip=true")))
            }
        }
        if (!skip && category == null) {
            throw BadRequestException("VALIDATION_ERROR", "Give exactly one of category or skip=true", listOf(FieldViolation("${prefix}category", "required unless skip=true")))
        }
        val hint = a.hint?.trim()?.takeIf { it.isNotEmpty() }
        if (hint != null && hint.length > 500) {
            throw BadRequestException("VALIDATION_ERROR", "hint is too long", listOf(FieldViolation("${prefix}hint", "at most 500 characters")))
        }
        // Hints go into LLM prompts: sanitize them like descriptions (no IBANs, cards, contacts, person names).
        return ValidAnswer(category, skip, hint?.let { sanitizer.sanitize(it).text }, a.applyToFuture == true)
    }

    private fun query(where: String, params: Map<String, Any>): List<QuestionResponse> {
        var spec = jdbc.sql(
            """
            SELECT q.id, q.kind, q.statement_id, q.question_text, q.suggestions::text AS suggestions, q.status,
                   t.id AS tx_id, t.account_id, a.label, t.booking_date, t.amount, t.currency, t.amount_base, t.description,
                   t.counterparty_token, t.counterparty_token_kind, c.user_label, c.is_own_account, oa.id AS own_account_id
            FROM questions q JOIN transactions t ON t.id = q.transaction_id JOIN accounts a ON a.id = t.account_id
            LEFT JOIN counterparties c ON c.token = t.counterparty_token LEFT JOIN accounts oa ON oa.iban_hash = t.counterparty_token
            WHERE $where
            ORDER BY CASE WHEN q.status = 'OPEN' THEN 0 ELSE 1 END, q.created_at, t.booking_date, t.sequence, q.id
            """.trimIndent(),
        )
        params.forEach { (k, v) -> spec = spec.param(k, v) }
        return spec.query(::map).list()
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): QuestionResponse = QuestionResponse(
        id = rs.getObject("id", UUID::class.java),
        kind = rs.getString("kind"),
        statementId = rs.getObject("statement_id", UUID::class.java),
        transaction = QuestionTransaction(
            id = rs.getObject("tx_id", UUID::class.java),
            accountId = rs.getObject("account_id", UUID::class.java),
            accountLabel = rs.getString("label"),
            bookingDate = rs.getObject("booking_date", LocalDate::class.java),
            amount = money(rs.getBigDecimal("amount"))!!,
            currency = rs.getString("currency"),
            amountBase = money(rs.getBigDecimal("amount_base")),
            description = cipher.decrypt(rs.getString("description")),
            counterparty = CounterpartyRef.from(rs),
        ),
        questionText = rs.getString("question_text"),
        suggestions = jsonMapper.readValue(rs.getString("suggestions"), Array<String>::class.java).toList().take(3),
        status = rs.getString("status"),
    )

    companion object {
        fun money(v: BigDecimal?): String? = v?.setScale(2, RoundingMode.HALF_EVEN)?.toPlainString()
    }
}
