package dev.spendly.report.domain

import dev.spendly.report.persistence.AggregateRow
import dev.spendly.report.persistence.LedgerReader
import dev.spendly.shared.config.SpendlyProperties
import java.math.BigDecimal
import java.time.YearMonth
import org.springframework.stereotype.Component

/**
 * Computes a report from the ledger: coverage, totals, breakdowns, trend, history and insights. It only reads, and
 * must run inside one REPEATABLE READ transaction ([ReportService] provides it) so all numbers share one snapshot.
 * Every total is a sum over `daily_aggregates` rows of the period (AC-RP-1).
 */
@Component
class ReportBuilder(private val reader: LedgerReader, properties: SpendlyProperties) {
    private val settings = properties.reports
    private val currency = properties.baseCurrency.uppercase()
    private val engine = InsightEngine.standard(settings)

    fun build(period: Period, scope: AccountScope): BuiltReport {
        val accounts = reader.accounts(scope)
        val baselinePeriods = period.baseline(period.baselineSize(settings))
        val previous = period.previous()
        // History (MONTH) and recurring payments (month scale) look back 6 months; baselines further back for long ranges.
        val historyStart = if (period.scale == Scale.MONTH) YearMonth.from(period.end).minusMonths(HISTORY_MONTHS - 1L).atDay(1) else period.start
        val wide = DateRange(
            minOf(period.start, previous.start, baselinePeriods.minOfOrNull { it.start } ?: period.start, historyStart),
            period.end,
        )
        val rows = reader.aggregates(scope, wide)
        val ranges = reader.categorizedRanges(scope, wide)

        fun coverageOf(range: DateRange): Coverage {
            val unconfirmed = rows.filter { it.kind == UNCONFIRMED && range.contains(it.date) }
            return CoverageCalculator.compute(
                range, accounts, ranges,
                Unconfirmed(unconfirmed.sumOf { it.count }, unconfirmed.sumOf { it.amount }),
            )
        }

        val coverage = coverageOf(period.range)
        val current = rows.filter { period.range.contains(it.date) }
        val spendRows = current.filter { it.kind == SPEND }
        val incomeRows = current.filter { it.kind == INCOME }
        val spend = spendRows.sumOf { it.amount }
        val income = incomeRows.sumOf { it.amount }
        val savings = current.filter { it.kind == SAVINGS }.sumOf { it.amount }
        val monthScale = period.scale == Scale.MONTH

        val totals = TotalsView(
            spendBase = Money.format(spend),
            incomeBase = Money.format(income),
            netBase = Money.format(income - spend),
            savingsDepositsBase = Money.format(savings),
            savingsRate = if (monthScale) SavingsRateRule.savingsRate(income, spend) else null,
            transactionCount = spendRows.sumOf { it.count } + incomeRows.sumOf { it.count },
        )
        val spendByCategory = spendRows.groupBy { it.category ?: OTHER }.mapValues { (_, r) -> r.sumOf { it.amount } }
        val countByCategory = spendRows.groupBy { it.category ?: OTHER }.mapValues { (_, r) -> r.sumOf { it.count } }
        val byCategory = spendByCategory.entries.sortedWith(compareByDescending<Map.Entry<String, BigDecimal>> { it.value }.thenBy { it.key })
            .map { (c, amount) -> CategoryTotalView(c, Money.format(amount), countByCategory.getValue(c), Money.ratio(amount, spend)) }

        val labels = accounts.associate { it.id to it.label }
        val byAccount = (coverage.accounts.map { it.account.id } + current.map { it.accountId }).distinct().map { id ->
            val own = current.filter { it.accountId == id }
            AccountTotalView(
                id, labels[id] ?: id.toString(),
                Money.format(own.filter { it.kind == SPEND }.sumOf { it.amount }),
                Money.format(own.filter { it.kind == INCOME }.sumOf { it.amount }),
            )
        }

        val merchantSpend = merchantSpend(spendRows)
        val topMerchants = merchantSpend.filter { it.merchantId != null }.groupBy { it.merchantId }.map { (id, list) ->
            MerchantTotalView(id, list.first().name, list.maxBy { it.amount }.category, Money.format(list.sumOf { it.amount }), list.sumOf { it.count })
        }.sortedWith(compareByDescending<MerchantTotalView> { BigDecimal(it.amountBase) }.thenBy { it.name }).take(TOP_MERCHANTS)

        val trend = if (period.range.days == 1L) emptyList() else period.range.dates().map { d ->
            val day = current.filter { it.date == d }
            TrendPointView(d, d, Money.format(day.filter { it.kind == SPEND }.sumOf { it.amount }), Money.format(day.filter { it.kind == INCOME }.sumOf { it.amount }))
        }.toList()

        val history = if (period.granularity != Granularity.MONTH) emptyList() else (HISTORY_MONTHS - 1 downTo 0).map { k ->
            val month = YearMonth.from(period.start).minusMonths(k.toLong())
            val range = DateRange(month.atDay(1), month.atEndOfMonth())
            val inMonth = rows.filter { range.contains(it.date) }
            TrendPointView(
                range.from, range.to,
                Money.format(inMonth.filter { it.kind == SPEND }.sumOf { it.amount }),
                Money.format(inMonth.filter { it.kind == INCOME }.sumOf { it.amount }),
                coverageOf(range).status,
            )
        }

        // Baselines: only previous periods with COMPLETE coverage count (cold start: "Not enough history yet").
        val completeBaselines = baselinePeriods.filter { coverageOf(it.range).status == CoverageStatus.COMPLETE }
        val baselineTotals = completeBaselines.map { p ->
            rows.filter { it.kind == SPEND && p.range.contains(it.date) }.groupBy { it.category ?: OTHER }.mapValues { (_, r) -> r.sumOf { it.amount } }
        }
        val enoughHistory = completeBaselines.size >= settings.minBaselinePeriods
        val previousSpend = if (coverageOf(previous.range).status == CoverageStatus.COMPLETE) {
            merchantSpend(rows.filter { it.kind == SPEND && previous.range.contains(it.date) })
        } else {
            null
        }

        val transactions = if (period.range.days <= MAX_UNUSUAL_DAYS) reader.spendTransactions(scope, period.range) else emptyList()
        val merchantHistory = reader.merchantPayments(
            scope, transactions.map { it.merchantId }.toSet(),
            DateRange(period.start.minusDays(settings.unusualLookbackDays), period.start.minusDays(1)),
        )
        val monthly = if (!monthScale) emptyList() else rows.filter { it.kind == SPEND && it.merchantId != null }
            .groupBy { it.merchantId!! to YearMonth.from(it.date) }
            .map { (key, list) ->
                MonthlyMerchant(key.first, list.first().merchantName ?: "?", list.maxBy { it.amount }.category, key.second, list.sumOf { it.amount }, list.sumOf { it.count })
            }

        val insights = engine.evaluate(
            InsightInput(
                period, currency, spend, income, savings, spendByCategory, countByCategory, baselineTotals, enoughHistory,
                merchantSpend, previousSpend, transactions, merchantHistory, monthly,
            ),
        )
        val unconfirmed = UnconfirmedView(coverage.unconfirmed.count, Money.format(coverage.unconfirmed.amountBase))
        val summary = ReportSummary(
            coverage = CoverageView.of(coverage),
            totals = totals,
            unconfirmedTransfers = unconfirmed,
            byCategory = byCategory,
            byAccount = byAccount,
            topMerchants = topMerchants,
            trend = trend,
            history = history,
            baseline = BaselineView(baselinePeriods.size, settings.minBaselinePeriods, completeBaselines.size, enoughHistory),
        )
        return BuiltReport(summary, insights, coverage, CoverageCalculator.fingerprint(accounts))
    }

    /** Coverage alone (GET /api/coverage), from the same rules as reports. */
    fun coverage(range: DateRange, scope: AccountScope): Coverage =
        CoverageCalculator.compute(range, reader.accounts(scope), reader.categorizedRanges(scope, range), reader.unconfirmed(scope, range))

    private fun merchantSpend(spendRows: List<AggregateRow>): List<MerchantSpend> =
        spendRows.groupBy { it.merchantId to (it.category ?: OTHER) }.map { (key, list) ->
            MerchantSpend(key.first, list.first().merchantName ?: UNKNOWN_MERCHANT, key.second, list.sumOf { it.amount }, list.sumOf { it.count })
        }

    companion object {
        const val SPEND = "SPEND"
        const val INCOME = "INCOME"
        const val SAVINGS = "SAVINGS_DEPOSIT"
        const val UNCONFIRMED = "UNCONFIRMED_TRANSFER"
        const val OTHER = "OTHER"
        const val UNKNOWN_MERCHANT = "Other merchants"
        const val TOP_MERCHANTS = 10
        const val HISTORY_MONTHS = 6

        /** The unusual-transaction rule reads single transactions; above this length it is skipped (only aggregates). */
        const val MAX_UNUSUAL_DAYS = 92L
    }
}
