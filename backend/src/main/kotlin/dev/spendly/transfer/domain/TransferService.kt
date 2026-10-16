package dev.spendly.transfer.domain

import dev.spendly.shared.config.SpendlyProperties
import dev.spendly.shared.crypto.AesGcmCipher
import dev.spendly.shared.messaging.ChangeCause
import dev.spendly.shared.observability.CategorizationMetrics
import dev.spendly.shared.outbox.DomainEvents
import dev.spendly.transaction.Category
import dev.spendly.transaction.TransferReason
import dev.spendly.transaction.TransferStatus
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

/**
 * Internal-transfer detection (plan section 10). Every method runs inside the caller's DB transaction; pair
 * matching takes a transaction-scoped advisory lock so the categorizer, the transfer matcher and counterparty
 * labelling never pair the same transaction twice. A new pair is written together with both transfer statuses,
 * the transfer_pairs row and an outbox TransactionsChanged (TRANSFER_MATCHED, both accounts, both dates).
 */
@Service
class TransferService(
    private val jdbc: JdbcClient,
    private val cipher: AesGcmCipher,
    private val events: DomainEvents,
    private val metrics: CategorizationMetrics,
    private val jsonMapper: JsonMapper,
    properties: SpendlyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val windowDays = properties.transfers.dateWindowDays
    private val matcher = PairMatcher(windowDays, properties.transfers.fxTolerance)

    /** Steps 1, 3, 4 (and labelled third parties → EXTERNAL) for one statement, then pair matching around it. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun detectForStatement(statementId: UUID, periodStart: LocalDate, periodEnd: LocalDate, correlationId: String): Int {
        upsertCounterpartiesOfStatement(statementId)
        // Step 1: the counterparty IBAN is one of the user's accounts.
        jdbc.sql(
            """
            UPDATE transactions t SET transfer_status = 'INTERNAL', transfer_reason = 'OWN_IBAN'
            FROM accounts a
            WHERE t.statement_id = :s AND t.counterparty_token = a.iban_hash
              AND t.transfer_status IN ('NONE', 'CANDIDATE') AND t.transfer_reason IS DISTINCT FROM 'USER'
            """.trimIndent(),
        ).param("s", statementId).update()
        // Step 4: a counterparty the user marked as an own account.
        jdbc.sql(
            """
            UPDATE transactions t SET transfer_status = 'INTERNAL', transfer_reason = 'OWN_COUNTERPARTY'
            FROM counterparties c
            WHERE t.statement_id = :s AND t.counterparty_token = c.token AND c.is_own_account
              AND t.transfer_status IN ('NONE', 'CANDIDATE') AND t.transfer_reason IS DISTINCT FROM 'USER'
            """.trimIndent(),
        ).param("s", statementId).update()
        // A labelled third party (e.g. "Landlord") is a transfer to another person: EXTERNAL spending (AC-MA-7).
        jdbc.sql(
            """
            UPDATE transactions t SET transfer_status = 'EXTERNAL', transfer_reason = 'LABELLED'
            FROM counterparties c
            WHERE t.statement_id = :s AND t.counterparty_token = c.token AND NOT c.is_own_account
              AND c.user_label IS NOT NULL AND t.transfer_status = 'NONE'
            """.trimIndent(),
        ).param("s", statementId).update()
        // Step 3: known transfer patterns → CANDIDATE.
        val rows = jdbc.sql("SELECT id, description FROM transactions WHERE statement_id = :s AND transfer_status = 'NONE'")
            .param("s", statementId).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()
        val candidates = rows.filter { (_, enc) -> TransferPatterns.matches(cipher.decrypt(enc)) }.map { it.first }
        if (candidates.isNotEmpty()) {
            jdbc.sql("UPDATE transactions SET transfer_status = 'CANDIDATE', transfer_reason = 'PATTERN' WHERE id = ANY(CAST(:ids AS uuid[]))")
                .param("ids", candidates.map(UUID::toString).toTypedArray()).update()
        }
        // Step 2: pair matching around the statement's period.
        val pairs = matchPairs(periodStart.minusDays(windowDays), periodEnd.plusDays(windowDays), correlationId)
        markInternalCategories(statementId)
        return pairs
    }

    /** INTERNAL transactions get INTERNAL_TRANSFER (source TRANSFER_MATCH unless the user decided). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun markInternalCategories(statementId: UUID) {
        jdbc.sql(
            """
            UPDATE transactions SET category = 'INTERNAL_TRANSFER', category_source = 'TRANSFER_MATCH', confidence = 1
            WHERE statement_id = :s AND transfer_status = 'INTERNAL' AND category_source IS DISTINCT FROM 'USER'
              AND (category IS DISTINCT FROM 'INTERNAL_TRANSFER' OR category_source IS DISTINCT FROM 'TRANSFER_MATCH')
            """.trimIndent(),
        ).param("s", statementId).update()
    }

    /**
     * Pair matching over every unpaired transaction booked in [from, to] (all accounts) whose statement has been
     * through transfer detection (CATEGORIZING or later): a statement that is still PARSED is matched when its own
     * detection runs, after its own-IBAN step, so the more precise IBAN match wins. Returns pairs created.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun matchPairs(from: LocalDate, to: LocalDate, correlationId: String): Int {
        lock()
        val txs = jdbc.sql(
            """
            SELECT t.id, t.account_id, t.booking_date, t.amount, t.currency, t.amount_base, t.transfer_status,
                   t.transfer_reason, t.counterparty_token
            FROM transactions t JOIN statements s ON s.id = t.statement_id
            WHERE t.booking_date BETWEEN :from AND :to AND t.transfer_pair_id IS NULL
              AND t.transfer_status <> 'EXTERNAL' AND s.status IN ('CATEGORIZING', 'NEEDS_INPUT', 'CATEGORIZED')
            """.trimIndent(),
        ).param("from", from).param("to", to).query(::mapTx).list()
        if (txs.isEmpty()) return 0
        val tokens = jdbc.sql("SELECT id, iban_hash FROM accounts").query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list().toMap()
        val pairs = matcher.match(txs, tokens)
        pairs.forEach { save(it, correlationId) }
        if (pairs.isNotEmpty()) log.info("Matched {} transfer pair(s) between {} and {}", pairs.size, from, to)
        return pairs.size
    }

    private fun save(p: ProposedPair, correlationId: String) {
        val pairId = UUID.randomUUID()
        jdbc.sql("INSERT INTO transfer_pairs (id, outgoing_tx_id, incoming_tx_id, match_method) VALUES (:id, :o, :i, :m)")
            .param("id", pairId).param("o", p.outgoing.id).param("i", p.incoming.id).param("m", p.method.name).update()
        jdbc.sql(
            """
            UPDATE transactions SET transfer_status = 'INTERNAL', transfer_pair_id = :pair,
                transfer_reason = CASE WHEN transfer_reason IN ('OWN_IBAN', 'OWN_COUNTERPARTY') THEN transfer_reason ELSE 'PAIR' END,
                category = CASE WHEN category_source = 'USER' THEN category ELSE 'INTERNAL_TRANSFER' END,
                category_source = CASE WHEN category_source = 'USER' THEN category_source ELSE 'TRANSFER_MATCH' END,
                confidence = CASE WHEN category_source = 'USER' THEN confidence ELSE 1 END
            WHERE id IN (:o, :i)
            """.trimIndent(),
        ).param("pair", pairId).param("o", p.outgoing.id).param("i", p.incoming.id).update()
        // A TRANSFER question about either side is now answered by the match.
        jdbc.sql(
            "UPDATE questions SET status = 'ANSWERED', resolved_at = now() WHERE kind = 'TRANSFER' AND status = 'OPEN' AND transaction_id IN (:o, :i)",
        ).param("o", p.outgoing.id).param("i", p.incoming.id).update()
        val from = minOf(p.outgoing.bookingDate, p.incoming.bookingDate)
        val to = maxOf(p.outgoing.bookingDate, p.incoming.bookingDate)
        events.transactionsChanged(listOf(p.outgoing.accountId, p.incoming.accountId), from, to, ChangeCause.TRANSFER_MATCHED, null, correlationId)
        metrics.transfers("matched")
    }

    /**
     * Step 5 / AC-MA-4: a TRANSFER question for every unresolved CANDIDATE (whose statement is past categorization)
     * once every other active account has CATEGORIZED statements covering its date ±window. No duplicates:
     * a candidate that already has a TRANSFER question (any status) is skipped. Returns questions created.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createTransferQuestions(): Int {
        lock()
        data class Cand(val id: UUID, val accountId: UUID, val date: LocalDate, val category: String?)
        val cands = jdbc.sql(
            """
            SELECT t.id, t.account_id, t.booking_date, t.category
            FROM transactions t JOIN statements s ON s.id = t.statement_id
            WHERE t.transfer_status = 'CANDIDATE' AND t.transfer_pair_id IS NULL
              AND s.status IN ('NEEDS_INPUT', 'CATEGORIZED')
              AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.transaction_id = t.id AND q.kind = 'TRANSFER')
            """.trimIndent(),
        ).query { rs, _ -> Cand(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, LocalDate::class.java), rs.getString(4)) }.list()
        if (cands.isEmpty()) return 0
        val accounts = jdbc.sql("SELECT id, active_from, closed_at FROM accounts").query { rs, _ ->
            TransferCoverage.AccountSpan(rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java), rs.getObject(3, LocalDate::class.java))
        }.list()
        val periods = jdbc.sql("SELECT account_id, period_start, period_end FROM statements WHERE status = 'CATEGORIZED' AND period_start IS NOT NULL")
            .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java), rs.getObject(3, LocalDate::class.java)) }
            .list().groupBy({ it.first }, { it.second to it.third })
        var created = 0
        for (c in cands) {
            if (!TransferCoverage.covered(c.date, c.accountId, accounts, periods, windowDays)) continue
            val guess = Category.parse(c.category)?.takeIf { it != Category.OTHER && it != Category.INTERNAL_TRANSFER }
            val suggestions = listOfNotNull(Category.INTERNAL_TRANSFER, guess, Category.OTHER).distinct().take(3)
            created += jdbc.sql(
                """
                INSERT INTO questions (id, kind, statement_id, transaction_id, question_text, suggestions)
                VALUES (:id, 'TRANSFER', NULL, :tx, :text, CAST(:suggestions AS jsonb))
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("id", UUID.randomUUID()).param("tx", c.id).param("text", TRANSFER_QUESTION)
                .param("suggestions", jsonMapper.writeValueAsString(suggestions.map { it.name })).update()
        }
        metrics.transfers("asked", created)
        metrics.questionsCreated("TRANSFER", created)
        return created
    }

    /** A row per counterparty token of the statement (IBAN or person), kept in sync at import and categorization. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun upsertCounterpartiesOfStatement(statementId: UUID) {
        jdbc.sql(
            """
            INSERT INTO counterparties (token, kind)
            SELECT DISTINCT ON (counterparty_token) counterparty_token, counterparty_token_kind
            FROM transactions WHERE statement_id = :s AND counterparty_token IS NOT NULL
            ON CONFLICT (token) DO NOTHING
            """.trimIndent(),
        ).param("s", statementId).update()
    }

    private fun lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", ADVISORY_KEY).query { _, _ -> 0 }.list()
    }

    private fun mapTx(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = TransferTx(
        id = rs.getObject("id", UUID::class.java),
        accountId = rs.getObject("account_id", UUID::class.java),
        bookingDate = rs.getObject("booking_date", LocalDate::class.java),
        amount = rs.getBigDecimal("amount"),
        currency = rs.getString("currency"),
        amountBase = rs.getBigDecimal("amount_base"),
        status = TransferStatus.valueOf(rs.getString("transfer_status")),
        reason = rs.getString("transfer_reason")?.let(TransferReason::valueOf),
        counterpartyToken = rs.getString("counterparty_token"),
    )

    companion object {
        const val TRANSFER_QUESTION = "Is this a transfer to one of your accounts?"
        /** Advisory lock key for all pair matching ("spendly-transfers"). */
        const val ADVISORY_KEY = 0x5350_544EL
    }
}
