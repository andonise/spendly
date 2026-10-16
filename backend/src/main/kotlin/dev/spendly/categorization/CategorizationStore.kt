package dev.spendly.categorization

import dev.spendly.crypto.AesGcmCipher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/** Reads categorization inputs and writes categorization results for one statement (caller's transaction). */
@Repository
class CategorizationStore(
    private val jdbc: JdbcClient,
    private val cipher: AesGcmCipher,
    private val learning: LearningRepository,
    private val jsonMapper: JsonMapper,
) {

    /**
     * Transactions of a statement that still need a category: not INTERNAL, not decided by the user,
     * and without an OPEN question (a re-run never re-asks).
     */
    fun inputsFor(statementId: UUID): List<TxInput> = jdbc.sql(
        """
        SELECT t.id, t.description, t.amount, t.currency, c.user_label, t.transfer_status
        FROM transactions t LEFT JOIN counterparties c ON c.token = t.counterparty_token
        WHERE t.statement_id = :s AND t.transfer_status <> 'INTERNAL' AND t.category_source IS DISTINCT FROM 'USER'
          AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.transaction_id = t.id AND q.status = 'OPEN')
        ORDER BY t.sequence
        """.trimIndent(),
    ).param("s", statementId).query { rs, _ ->
        TxInput(
            id = rs.getObject(1, UUID::class.java),
            description = cipher.decrypt(rs.getString(2)),
            amount = rs.getBigDecimal(3),
            currency = rs.getString(4),
            counterpartyLabel = rs.getString(5),
            transferCandidate = rs.getString(6) == TransferStatus.CANDIDATE.name,
        )
    }.list()

    /** Saves decided categories (skipping rows that became INTERNAL or user-decided meanwhile). Returns rows updated. */
    fun applyDecisions(decisions: List<TxDecision>): Int {
        var n = 0
        val merchantIds = HashMap<String, UUID>()
        for (d in decisions) {
            val category = d.category ?: continue
            val merchantId = d.merchant?.let { m -> merchantIds.getOrPut(m.key) { learning.upsertMerchant(m) } }
            n += jdbc.sql(
                """
                UPDATE transactions SET category = :category, category_source = :source, confidence = :confidence,
                    merchant_id = COALESCE(CAST(:merchant AS uuid), merchant_id)
                WHERE id = :id AND transfer_status <> 'INTERNAL' AND category_source IS DISTINCT FROM 'USER'
                """.trimIndent(),
            ).param("category", category.name).param("source", d.source?.name)
                .param("confidence", d.confidence?.let { BigDecimal.valueOf(it).setScale(3, java.math.RoundingMode.HALF_UP) })
                .param("merchant", merchantId).param("id", d.id).update()
        }
        return n
    }

    /** CATEGORY questions for undecided transactions (still not INTERNAL). Returns questions created. */
    fun createQuestions(statementId: UUID, decisions: List<TxDecision>): Int {
        var n = 0
        for (d in decisions) {
            val text = d.question ?: continue
            d.merchant?.let { learning.upsertMerchant(it) }?.let { mid ->
                jdbc.sql("UPDATE transactions SET merchant_id = :m WHERE id = :id").param("m", mid).param("id", d.id).update()
            }
            n += jdbc.sql(
                """
                INSERT INTO questions (id, kind, statement_id, transaction_id, question_text, suggestions)
                SELECT :id, 'CATEGORY', :s, t.id, :text, CAST(:suggestions AS jsonb)
                FROM transactions t WHERE t.id = :tx AND t.transfer_status <> 'INTERNAL'
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("id", UUID.randomUUID()).param("s", statementId).param("tx", d.id).param("text", text.take(500))
                .param("suggestions", jsonMapper.writeValueAsString(d.suggestions.take(3).map { it.name })).update()
        }
        return n
    }

    fun openQuestionCount(statementId: UUID): Int =
        jdbc.sql("SELECT count(*) FROM questions WHERE statement_id = :s AND status = 'OPEN'").param("s", statementId)
            .query(Int::class.java).single()

    /** Transactions with no category that are neither INTERNAL nor covered by an OPEN question ("new unknowns"). */
    fun uncoveredUnknowns(statementId: UUID): List<UUID> = jdbc.sql(
        """
        SELECT t.id FROM transactions t
        WHERE t.statement_id = :s AND t.category IS NULL AND t.transfer_status <> 'INTERNAL'
          AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.transaction_id = t.id AND q.status = 'OPEN')
        """.trimIndent(),
    ).param("s", statementId).query(UUID::class.java).list().filterNotNull()
}
