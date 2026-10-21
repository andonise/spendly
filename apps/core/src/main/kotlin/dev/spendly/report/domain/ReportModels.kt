package dev.spendly.report.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReportStatus { PENDING, READY, STALE, FAILED }

enum class InsightType {
    CATEGORY_SPIKE, UNUSUAL_TRANSACTION, MERCHANT_CONCENTRATION, FEES_AND_INTEREST, RECURRING_PAYMENTS,
    DUPLICATE_SUBSCRIPTIONS, SAVINGS_RATE,
}

enum class Severity { INFO, WARNING }

/** One finding of an insight rule; [data] holds plain JSON values (amounts as 2-decimal strings). */
data class Insight(val type: InsightType, val severity: Severity, val message: String, val data: Map<String, Any?>)

/** A `reports` row (without summary and insights). */
data class ReportRecord(
    val id: UUID,
    val period: Period,
    val scope: AccountScope,
    val status: ReportStatus,
    val version: Int,
    val requestedVersion: Int,
    val builtVersion: Int?,
    val accountsFingerprint: String?,
    val builtAt: Instant?,
)

// ------------------------------------------------------------------- summary (stored as JSON, served as is)

data class RangeView(val from: LocalDate, val to: LocalDate)

data class UnconfirmedView(val count: Long, val amountBase: String)

data class AccountCoverageView(
    val accountId: UUID,
    val accountLabel: String,
    val activeFrom: LocalDate,
    val closedAt: LocalDate?,
    val status: CoverageStatus,
    val gaps: List<RangeView>,
)

data class CoverageView(
    val from: LocalDate,
    val to: LocalDate,
    val status: CoverageStatus,
    val accounts: List<AccountCoverageView>,
    val unconfirmedTransfers: UnconfirmedView,
) {
    companion object {
        fun of(c: Coverage) = CoverageView(
            c.range.from, c.range.to, c.status,
            c.accounts.map { a ->
                AccountCoverageView(a.account.id, a.account.label, a.account.activeFrom, a.account.closedAt, a.status, a.gaps.map { RangeView(it.from, it.to) })
            },
            UnconfirmedView(c.unconfirmed.count, Money.format(c.unconfirmed.amountBase)),
        )
    }
}

data class TotalsView(
    val spendBase: String,
    val incomeBase: String,
    val netBase: String,
    val savingsDepositsBase: String,
    val savingsRate: Double?,
    val transactionCount: Long,
)

data class CategoryTotalView(val category: String, val amountBase: String, val count: Long, val share: Double)

data class AccountTotalView(val accountId: UUID, val accountLabel: String, val spendBase: String, val incomeBase: String)

data class MerchantTotalView(val merchantId: UUID?, val name: String, val category: String?, val amountBase: String, val count: Long)

data class TrendPointView(val from: LocalDate, val to: LocalDate, val spendBase: String, val incomeBase: String, val coverage: CoverageStatus? = null)

data class BaselineView(val periodsWanted: Int, val periodsRequired: Int, val periodsUsed: Int, val enoughHistory: Boolean)

data class ReportSummary(
    val coverage: CoverageView,
    val totals: TotalsView,
    val unconfirmedTransfers: UnconfirmedView,
    val byCategory: List<CategoryTotalView>,
    val byAccount: List<AccountTotalView>,
    val topMerchants: List<MerchantTotalView>,
    val trend: List<TrendPointView>,
    val history: List<TrendPointView>,
    val baseline: BaselineView,
)

/** Everything one build produced from one snapshot. */
data class BuiltReport(val summary: ReportSummary, val insights: List<Insight>, val coverage: Coverage, val accountsFingerprint: String)

object Money {
    fun format(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    fun ratio(part: BigDecimal, whole: BigDecimal): Double =
        if (whole.signum() == 0) 0.0 else part.divide(whole, 3, RoundingMode.HALF_EVEN).toDouble()
}
