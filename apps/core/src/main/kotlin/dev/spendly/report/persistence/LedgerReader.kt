package dev.spendly.report.persistence

import dev.spendly.report.domain.AccountInfo
import dev.spendly.report.domain.AccountScope
import dev.spendly.report.domain.DateRange
import dev.spendly.report.domain.SpendTx
import dev.spendly.report.domain.Unconfirmed
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** One `daily_aggregates` row with its merchant's name. */
data class AggregateRow(
    val date: LocalDate,
    val accountId: UUID,
    val kind: String,
    val category: String?,
    val merchantId: UUID?,
    val merchantName: String?,
    val amount: BigDecimal,
    val count: Long,
)

/**
 * Read side of the report builder. Reads the ledger tables (owned by the statement and transaction modules)
 * read-only, always inside the builder's REPEATABLE READ transaction, so every number comes from one snapshot.
 * `:scope` is `ALL` or the comma-separated account IDs.
 */
@Repository
class LedgerReader(private val jdbc: JdbcClient) {

    fun accounts(scope: AccountScope): List<AccountInfo> = jdbc.sql(
        """
        SELECT id, label, type, active_from, closed_at FROM accounts
        WHERE $SCOPE_ID ORDER BY label, id
        """.trimIndent(),
    ).param("scope", scope.key).query { rs, _ ->
        AccountInfo(
            rs.getObject("id", UUID::class.java), rs.getString("label"), rs.getString("type"),
            rs.getObject("active_from", LocalDate::class.java), rs.getObject("closed_at", LocalDate::class.java),
        )
    }.list()

    /** Date ranges of CATEGORIZED statements overlapping [range], per account. */
    fun categorizedRanges(scope: AccountScope, range: DateRange): Map<UUID, List<DateRange>> = jdbc.sql(
        """
        SELECT account_id, period_start, period_end FROM statements
        WHERE status = 'CATEGORIZED' AND period_start <= :to AND period_end >= :from
          AND ${scopeOn("account_id")}
        """.trimIndent(),
    ).param("scope", scope.key).param("from", range.from).param("to", range.to).query { rs, _ ->
        rs.getObject(1, UUID::class.java) to DateRange(rs.getObject(2, LocalDate::class.java), rs.getObject(3, LocalDate::class.java))
    }.list().groupBy({ it.first }, { it.second })

    fun unconfirmed(scope: AccountScope, range: DateRange): Unconfirmed = jdbc.sql(
        """
        SELECT COALESCE(sum(tx_count), 0), COALESCE(sum(amount_base), 0) FROM daily_aggregates
        WHERE kind = 'UNCONFIRMED_TRANSFER' AND date BETWEEN :from AND :to AND ${scopeOn("account_id")}
        """.trimIndent(),
    ).param("scope", scope.key).param("from", range.from).param("to", range.to)
        .query { rs, _ -> Unconfirmed(rs.getLong(1), rs.getBigDecimal(2)) }.single()

    fun aggregates(scope: AccountScope, range: DateRange): List<AggregateRow> = jdbc.sql(
        """
        SELECT d.date, d.account_id, d.kind, d.category, d.merchant_id, m.normalized_name, d.amount_base, d.tx_count
        FROM daily_aggregates d LEFT JOIN merchants m ON m.id = d.merchant_id
        WHERE d.date BETWEEN :from AND :to AND ${scopeOn("d.account_id")}
        """.trimIndent(),
    ).param("scope", scope.key).param("from", range.from).param("to", range.to).query { rs, _ ->
        AggregateRow(
            rs.getObject(1, LocalDate::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4),
            rs.getObject(5, UUID::class.java), rs.getString(6), rs.getBigDecimal(7), rs.getLong(8),
        )
    }.list()

    /** Outgoing, non-transfer transactions with a merchant, from CATEGORIZED statements (the same rows as SPEND aggregates). */
    fun spendTransactions(scope: AccountScope, range: DateRange): List<SpendTx> = jdbc.sql(
        """
        SELECT t.id, t.booking_date, t.merchant_id, m.normalized_name, t.category, -t.amount_base
        FROM transactions t
        JOIN statements s ON s.id = t.statement_id AND s.status = 'CATEGORIZED'
        JOIN merchants m ON m.id = t.merchant_id
        WHERE t.booking_date BETWEEN :from AND :to AND t.amount < 0 AND t.amount_base IS NOT NULL
          AND t.transfer_status IN ('NONE', 'EXTERNAL') AND ${scopeOn("t.account_id")}
        """.trimIndent(),
    ).param("scope", scope.key).param("from", range.from).param("to", range.to).query { rs, _ ->
        SpendTx(
            rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java), rs.getObject(3, UUID::class.java),
            rs.getString(4), rs.getString(5), rs.getBigDecimal(6),
        )
    }.list()

    /** Individual payment amounts at [merchantIds] in [range] (the "typical amount" for the unusual-transaction rule). */
    fun merchantPayments(scope: AccountScope, merchantIds: Collection<UUID>, range: DateRange): Map<UUID, List<BigDecimal>> {
        if (merchantIds.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT t.merchant_id, -t.amount_base
            FROM transactions t
            JOIN statements s ON s.id = t.statement_id AND s.status = 'CATEGORIZED'
            WHERE t.merchant_id IN (:merchants) AND t.booking_date BETWEEN :from AND :to AND t.amount < 0
              AND t.amount_base IS NOT NULL AND t.transfer_status IN ('NONE', 'EXTERNAL') AND ${scopeOn("t.account_id")}
            """.trimIndent(),
        ).param("merchants", merchantIds).param("scope", scope.key).param("from", range.from).param("to", range.to)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getBigDecimal(2) }
            .list().groupBy({ it.first }, { it.second })
    }

    /** The current transaction isolation level (tests assert the snapshot is REPEATABLE READ). */
    fun isolationLevel(): String = jdbc.sql("SHOW transaction_isolation").query(String::class.java).single()

    companion object {
        private val SCOPE_ID = scopeOn("id")

        /** Compared as text: casting 'ALL' to uuid[] could fail even when the first branch is true (constant folding). */
        fun scopeOn(column: String) =
            "(CAST(:scope AS text) = 'ALL' OR CAST($column AS text) = ANY(string_to_array(CAST(:scope AS text), ',')))"
    }
}
