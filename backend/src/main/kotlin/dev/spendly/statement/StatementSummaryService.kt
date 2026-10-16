package dev.spendly.statement

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class CategoryTotal(val category: String, val amountBase: String, val count: Long)

/**
 * contracts `StatementSummary`: absolute base-currency amounts; spendBase, incomeBase and byCategory exclude
 * INTERNAL and CANDIDATE (unconfirmed) transfers; internalTransferCount counts INTERNAL only.
 */
data class StatementSummary(
    val transactionCount: Long,
    val spendBase: String,
    val incomeBase: String,
    val internalTransferCount: Long,
    val byCategory: List<CategoryTotal>,
)

@Service
class StatementSummaryService(private val jdbc: JdbcClient) {

    fun progress(statementId: UUID): StatementProgress = jdbc.sql(
        """
        SELECT (SELECT count(*) FROM transactions WHERE statement_id = :s),
               (SELECT count(*) FROM transactions WHERE statement_id = :s AND category IS NOT NULL),
               (SELECT count(*) FROM questions WHERE statement_id = :s AND status = 'OPEN')
        """.trimIndent(),
    ).param("s", statementId).query { rs, _ -> StatementProgress(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.single()

    fun summary(statementId: UUID): StatementSummary {
        data class Totals(val count: Long, val spend: BigDecimal, val income: BigDecimal, val internal: Long)
        val t = jdbc.sql(
            """
            SELECT count(*),
                   COALESCE(sum(-amount_base) FILTER (WHERE amount < 0 AND transfer_status NOT IN ('INTERNAL', 'CANDIDATE')), 0),
                   COALESCE(sum(amount_base) FILTER (WHERE amount > 0 AND transfer_status NOT IN ('INTERNAL', 'CANDIDATE')), 0),
                   count(*) FILTER (WHERE transfer_status = 'INTERNAL')
            FROM transactions WHERE statement_id = :s
            """.trimIndent(),
        ).param("s", statementId).query { rs, _ -> Totals(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getLong(4)) }.single()
        val byCategory = jdbc.sql(
            """
            SELECT category, abs(COALESCE(sum(amount_base), 0)) AS total, count(*)
            FROM transactions
            WHERE statement_id = :s AND category IS NOT NULL AND category <> 'INTERNAL_TRANSFER'
              AND transfer_status NOT IN ('INTERNAL', 'CANDIDATE')
            GROUP BY category ORDER BY total DESC, category
            """.trimIndent(),
        ).param("s", statementId).query { rs, _ -> CategoryTotal(rs.getString(1), money(rs.getBigDecimal(2)), rs.getLong(3)) }.list()
        return StatementSummary(t.count, money(t.spend), money(t.income), t.internal, byCategory)
    }

    private fun money(v: BigDecimal): String = v.abs().setScale(2, RoundingMode.HALF_EVEN).toPlainString()
}
