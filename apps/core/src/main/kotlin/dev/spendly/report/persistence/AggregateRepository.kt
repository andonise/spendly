package dev.spendly.report.persistence

import dev.spendly.report.domain.DateRange
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** `daily_aggregates`: rebuilt per account and date range by the aggregator, always inside its transaction. */
@Repository
class AggregateRepository(private val jdbc: JdbcClient) {

    /** Serializes rebuilds of the same account (plan section 7, rule 3). Lock accounts in a fixed order. */
    fun lockAccount(accountId: UUID) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('spendly-aggregates:' || CAST(:a AS text), 0))")
            .param("a", accountId).query { _, _ -> 1 }.list()
    }

    /** Delete + insert the aggregates of one account for [range] (naturally idempotent). Returns the rows inserted. */
    fun rebuild(accountId: UUID, range: DateRange): Int {
        jdbc.sql("DELETE FROM daily_aggregates WHERE account_id = :a AND date BETWEEN :from AND :to")
            .param("a", accountId).param("from", range.from).param("to", range.to).update()
        return jdbc.sql(REBUILD).param("a", accountId).param("from", range.from).param("to", range.to).update()
    }

    /** Months of [range] in which at least one account has a CATEGORIZED statement day. */
    fun coveredMonths(range: DateRange): List<YearMonth> = jdbc.sql(
        """
        SELECT DISTINCT CAST(m AS date)
        FROM statements s
        CROSS JOIN LATERAL generate_series(
            date_trunc('month', CAST(GREATEST(s.period_start, CAST(:from AS date)) AS timestamp)),
            date_trunc('month', CAST(LEAST(s.period_end, CAST(:to AS date)) AS timestamp)),
            interval '1 month') m
        WHERE s.status = 'CATEGORIZED' AND s.period_start <= :to AND s.period_end >= :from
        ORDER BY 1
        """.trimIndent(),
    ).param("from", range.from).param("to", range.to)
        .query { rs, _ -> YearMonth.from(rs.getObject(1, LocalDate::class.java)) }.list()

    /** Every account with CATEGORIZED statements, and the date span they cover (startup backfill). */
    fun categorizedSpans(): List<Pair<UUID, DateRange>> = jdbc.sql(
        """
        SELECT account_id, min(period_start), max(period_end) FROM statements
        WHERE status = 'CATEGORIZED' AND period_start IS NOT NULL GROUP BY account_id
        """.trimIndent(),
    ).query { rs, _ ->
        rs.getObject(1, UUID::class.java) to DateRange(rs.getObject(2, LocalDate::class.java), rs.getObject(3, LocalDate::class.java))
    }.list()

    fun isEmpty(): Boolean = jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM daily_aggregates)").query(Boolean::class.java).single()

    companion object {
        /**
         * Only transactions of CATEGORIZED statements count (plan section 5). INTERNAL transfers are left out, except
         * outgoing ones into a SAVINGS account (its pair's account, or the own account its counterparty IBAN belongs to),
         * which become SAVINGS_DEPOSIT. CANDIDATE transfers become UNCONFIRMED_TRANSFER.
         */
        private val REBUILD = """
            INSERT INTO daily_aggregates (date, account_id, kind, category, merchant_id, amount_base, tx_count)
            SELECT x.booking_date, x.account_id, x.kind, x.category, x.merchant_id, sum(x.amount), count(*)
            FROM (
                SELECT t.booking_date, t.account_id,
                       CASE
                           WHEN t.transfer_status = 'CANDIDATE' THEN 'UNCONFIRMED_TRANSFER'
                           WHEN t.transfer_status = 'INTERNAL' THEN 'SAVINGS_DEPOSIT'
                           WHEN t.amount < 0 THEN 'SPEND'
                           ELSE 'INCOME'
                       END AS kind,
                       CASE WHEN t.transfer_status IN ('CANDIDATE', 'INTERNAL') THEN NULL ELSE t.category END AS category,
                       CASE WHEN t.transfer_status IN ('CANDIDATE', 'INTERNAL') THEN NULL ELSE t.merchant_id END AS merchant_id,
                       abs(t.amount_base) AS amount
                FROM transactions t
                JOIN statements s ON s.id = t.statement_id AND s.status = 'CATEGORIZED'
                LEFT JOIN transfer_pairs p ON p.id = t.transfer_pair_id AND p.outgoing_tx_id = t.id
                LEFT JOIN transactions pt ON pt.id = p.incoming_tx_id
                LEFT JOIN accounts pa ON pa.id = pt.account_id
                LEFT JOIN accounts oa ON oa.iban_hash = t.counterparty_token
                WHERE t.account_id = :a AND t.booking_date BETWEEN :from AND :to AND t.amount_base IS NOT NULL
                  AND t.amount <> 0
                  AND (t.transfer_status <> 'INTERNAL'
                       OR (t.amount < 0 AND COALESCE(pa.type, oa.type) = 'SAVINGS' AND COALESCE(pa.id, oa.id) <> t.account_id))
            ) x
            GROUP BY x.booking_date, x.account_id, x.kind, x.category, x.merchant_id
        """.trimIndent()
    }
}
