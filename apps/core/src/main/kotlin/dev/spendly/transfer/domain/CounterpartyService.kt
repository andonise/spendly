package dev.spendly.transfer.domain

import dev.spendly.shared.crypto.AesGcmCipher
import dev.spendly.shared.messaging.ChangeCause
import dev.spendly.shared.outbox.DomainEvents
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.ConflictException
import dev.spendly.shared.web.FieldViolation
import dev.spendly.shared.web.NotFoundException
import dev.spendly.transaction.Category
import dev.spendly.transaction.CounterpartyRef
import dev.spendly.transaction.KeywordRules
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

data class CounterpartyResponse(
    val token: String,
    val kind: String?,
    val label: String?,
    val isOwnAccount: Boolean,
    val ownAccountId: UUID?,
    val transactionCount: Long,
    val lastSeen: LocalDate?,
    val sampleDescription: String?,
)

data class CounterpartyUpdate(val label: String? = null, val isOwnAccount: Boolean? = null)

/**
 * Counterparty labels (plan section 10 step 4, AC-MA-7). Marking a token as an own account re-runs transfer
 * detection for its transactions (CANDIDATE/NONE → INTERNAL, then pair matching); unmarking reverts them; a label
 * on a third party makes its transfers EXTERNAL and, when the label implies one ("Landlord"), sets the category.
 * Any change publishes TransactionsChanged (COUNTERPARTY_LABELLED) for the affected accounts and dates.
 */
@Service
class CounterpartyService(
    private val jdbc: JdbcClient,
    private val cipher: AesGcmCipher,
    private val transfers: TransferService,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(): List<CounterpartyResponse> = query(null)

    fun get(token: String): CounterpartyResponse = query(token).firstOrNull() ?: throw NotFoundException("Counterparty")

    fun update(token: String, body: CounterpartyUpdate, correlationId: String): CounterpartyResponse {
        val isOwn = body.isOwnAccount ?: throw dev.spendly.shared.web.BadRequestException(
            "VALIDATION_ERROR", "isOwnAccount is required", listOf(dev.spendly.shared.web.FieldViolation("isOwnAccount", "required")),
        )
        val label = body.label?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null && label.length > 80) {
            throw dev.spendly.shared.web.BadRequestException("VALIDATION_ERROR", "label is too long", listOf(dev.spendly.shared.web.FieldViolation("label", "at most 80 characters")))
        }
        tx.executeWithoutResult {
            data class Row(val label: String?, val own: Boolean, val registered: Boolean)
            val row = jdbc.sql(
                """
                SELECT c.user_label, c.is_own_account, EXISTS (SELECT 1 FROM accounts a WHERE a.iban_hash = c.token)
                FROM counterparties c WHERE c.token = :t FOR UPDATE
                """.trimIndent(),
            ).param("t", token).query { rs, _ -> Row(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)) }
                .optional().orElseThrow { NotFoundException("Counterparty") }
            if (row.registered && !isOwn) {
                throw ConflictException("COUNTERPARTY_IS_REGISTERED_ACCOUNT", "This counterparty is one of your registered accounts")
            }
            if (row.label == label && row.own == isOwn) return@executeWithoutResult
            jdbc.sql("UPDATE counterparties SET user_label = :l, is_own_account = :o, updated_at = now() WHERE token = :t")
                .param("l", label).param("o", isOwn).param("t", token).update()
            when {
                isOwn && !row.own -> markOwn(token, correlationId)
                !isOwn && row.own -> unmarkOwn(token, label)
            }
            if (!isOwn) relabel(token, label)
            publishChange(token, correlationId)
            log.info("Counterparty updated (own={}, labelled={})", isOwn, label != null)
        }
        return get(token)
    }

    /** From a TRANSFER answer with applyToFuture: mark the token as own account (caller's transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun markOwnFromAnswer(token: String, correlationId: String) {
        val changed = jdbc.sql(
            """
            INSERT INTO counterparties (token, is_own_account) VALUES (:t, true)
            ON CONFLICT (token) DO UPDATE SET is_own_account = true, updated_at = now() WHERE NOT counterparties.is_own_account
            """.trimIndent(),
        ).param("t", token).update()
        if (changed > 0) {
            markOwn(token, correlationId)
            publishChange(token, correlationId)
        }
    }

    private fun markOwn(token: String, correlationId: String) {
        jdbc.sql(
            """
            UPDATE transactions SET transfer_status = 'INTERNAL', transfer_reason = 'OWN_COUNTERPARTY',
                category = 'INTERNAL_TRANSFER', category_source = 'TRANSFER_MATCH', confidence = 1
            WHERE counterparty_token = :t AND transfer_reason IS DISTINCT FROM 'USER'
              AND (transfer_status IN ('NONE', 'CANDIDATE') OR (transfer_status = 'EXTERNAL' AND transfer_reason = 'LABELLED'))
            """.trimIndent(),
        ).param("t", token).update()
        // Open TRANSFER questions about those transactions are resolved by the label.
        jdbc.sql(
            """
            UPDATE questions q SET status = 'ANSWERED', resolved_at = now()
            FROM transactions t WHERE t.id = q.transaction_id AND t.counterparty_token = :t
              AND q.kind = 'TRANSFER' AND q.status = 'OPEN' AND t.transfer_status = 'INTERNAL'
            """.trimIndent(),
        ).param("t", token).update()
        val range = dateRange(token) ?: return
        transfers.matchPairs(range.first.minusDays(3), range.second.plusDays(3), correlationId)
    }

    /** Reverts OWN_COUNTERPARTY (unpaired) transactions to what detection would say without the flag. */
    private fun unmarkOwn(token: String, label: String?) {
        val rows = jdbc.sql(
            "SELECT id, description, amount FROM transactions WHERE counterparty_token = :t AND transfer_reason = 'OWN_COUNTERPARTY' AND transfer_pair_id IS NULL",
        ).param("t", token).query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), cipher.decrypt(rs.getString(2)), rs.getBigDecimal(3)) }.list()
        for ((id, description, amount) in rows) {
            val (status, reason) = when {
                TransferPatterns.matches(description) -> "CANDIDATE" to "PATTERN"
                label != null -> "EXTERNAL" to "LABELLED"
                else -> "NONE" to null
            }
            val category = categoryFor(description, amount, label)
            jdbc.sql(
                """
                UPDATE transactions SET transfer_status = :s, transfer_reason = CAST(:r AS varchar), category = :c,
                    category_source = CAST(:src AS varchar), confidence = CAST(:conf AS numeric)
                WHERE id = :id
                """.trimIndent(),
            ).param("s", status).param("r", reason).param("c", (category ?: Category.OTHER).name)
                .param("src", if (category != null) "RULE" else null)
                .param("conf", if (category != null) BigDecimal.ONE else null).param("id", id).update()
        }
    }

    /** A labelled third party: its NONE transfers become EXTERNAL; a label that implies a category applies it. */
    private fun relabel(token: String, label: String?) {
        if (label == null) {
            jdbc.sql("UPDATE transactions SET transfer_status = 'NONE', transfer_reason = NULL WHERE counterparty_token = :t AND transfer_reason = 'LABELLED'")
                .param("t", token).update()
            return
        }
        jdbc.sql("UPDATE transactions SET transfer_status = 'EXTERNAL', transfer_reason = 'LABELLED' WHERE counterparty_token = :t AND transfer_status = 'NONE'")
            .param("t", token).update()
        val rows = jdbc.sql(
            """
            SELECT id, amount FROM transactions
            WHERE counterparty_token = :t AND transfer_status = 'EXTERNAL' AND category_source IS DISTINCT FROM 'USER'
            """.trimIndent(),
        ).param("t", token).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getBigDecimal(2) }.list()
        for ((id, amount) in rows) {
            val category = KeywordRules.matchLabel(label, amount) ?: continue
            jdbc.sql("UPDATE transactions SET category = :c, category_source = 'RULE', confidence = 1 WHERE id = :id")
                .param("c", category.name).param("id", id).update()
        }
    }

    private fun categoryFor(description: String, amount: BigDecimal, label: String?): Category? =
        KeywordRules.matchLabel(label, amount) ?: KeywordRules.match(description, amount)

    private fun publishChange(token: String, correlationId: String) {
        val accounts = jdbc.sql(
            "SELECT account_id, min(booking_date), max(booking_date) FROM transactions WHERE counterparty_token = :t GROUP BY account_id ORDER BY min(booking_date), account_id",
        ).param("t", token).query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java), rs.getObject(3, LocalDate::class.java)) }.list()
        if (accounts.isEmpty()) return
        events.transactionsChanged(
            accounts.map { it.first }, accounts.minOf { it.second }, accounts.maxOf { it.third },
            ChangeCause.COUNTERPARTY_LABELLED, null, correlationId,
        )
    }

    private fun dateRange(token: String): Pair<LocalDate, LocalDate>? =
        jdbc.sql("SELECT min(booking_date), max(booking_date) FROM transactions WHERE counterparty_token = :t").param("t", token)
            .query { rs, _ -> rs.getObject(1, LocalDate::class.java)?.let { it to rs.getObject(2, LocalDate::class.java) } }.single()

    private fun query(token: String?): List<CounterpartyResponse> {
        val spec = jdbc.sql(
            """
            SELECT c.token AS counterparty_token, c.kind AS counterparty_token_kind, c.user_label, c.is_own_account,
                   oa.id AS own_account_id, count(t.id) AS tx_count, max(t.booking_date) AS last_seen,
                   (SELECT t2.description FROM transactions t2 WHERE t2.counterparty_token = c.token
                    ORDER BY t2.booking_date DESC, t2.id LIMIT 1) AS sample
            FROM counterparties c
            LEFT JOIN accounts oa ON oa.iban_hash = c.token
            LEFT JOIN transactions t ON t.counterparty_token = c.token
            WHERE (CAST(:t AS varchar) IS NULL OR c.token = CAST(:t AS varchar))
            GROUP BY c.token, c.kind, c.user_label, c.is_own_account, oa.id
            ORDER BY max(t.booking_date) DESC NULLS LAST, c.token
            """.trimIndent(),
        ).param("t", token)
        return spec.query { rs, _ ->
            val ref = CounterpartyRef.from(rs)!!
            CounterpartyResponse(
                ref.token, ref.kind, ref.label, ref.isOwnAccount, ref.ownAccountId,
                rs.getLong("tx_count"), rs.getObject("last_seen", LocalDate::class.java), rs.getString("sample")?.let(cipher::decrypt),
            )
        }.list()
    }
}
