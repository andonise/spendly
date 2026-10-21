package dev.spendly.report.domain

import dev.spendly.shared.config.SpendlyProperties
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Spend at one merchant in one category (merchantId null = transactions without a recognised merchant). */
data class MerchantSpend(val merchantId: UUID?, val name: String, val category: String?, val amount: BigDecimal, val count: Long)

/** One outgoing, non-transfer transaction with a known merchant. */
data class SpendTx(val id: UUID, val date: LocalDate, val merchantId: UUID, val merchantName: String, val category: String?, val amount: BigDecimal)

/** Spend at one merchant in one calendar month. */
data class MonthlyMerchant(val merchantId: UUID, val name: String, val category: String?, val month: YearMonth, val amount: BigDecimal, val count: Long)

/**
 * Everything the rules read, taken from one snapshot. Amounts are absolute base-currency values; INTERNAL and
 * CANDIDATE transfers are already excluded. [baseline] holds only previous periods with COMPLETE coverage.
 */
data class InsightInput(
    val period: Period,
    val currency: String,
    val spend: BigDecimal,
    val income: BigDecimal,
    val savingsDeposits: BigDecimal,
    val spendByCategory: Map<String, BigDecimal>,
    val countByCategory: Map<String, Long>,
    val baseline: List<Map<String, BigDecimal>>,
    val enoughHistory: Boolean,
    val merchantSpend: List<MerchantSpend>,
    val previousMerchantSpend: List<MerchantSpend>?,
    val transactions: List<SpendTx>,
    val merchantHistory: Map<UUID, List<BigDecimal>>,
    val monthlyMerchantSpend: List<MonthlyMerchant>,
)

/** One insight rule: which scales it supports, whether it needs a baseline, and its findings. */
interface InsightRule {
    val type: InsightType
    val scales: Set<Scale>
    val needsBaseline: Boolean get() = false

    fun evaluate(input: InsightInput): List<Insight>
}

/** Runs the rules that apply to the period's scale; baseline rules only with enough history (cold start). */
class InsightEngine(val rules: List<InsightRule>) {

    fun evaluate(input: InsightInput): List<Insight> = rules
        .filter { input.period.scale in it.scales }
        .filter { !it.needsBaseline || input.enoughHistory }
        .flatMap { it.evaluate(input) }
        .sortedWith(compareBy<Insight> { it.severity != Severity.WARNING })

    companion object {
        fun standard(s: SpendlyProperties.Reports) = InsightEngine(
            listOf(
                SavingsRateRule(),
                CategorySpikeRule(s.spikeThreshold, s.spikeMinDelta),
                UnusualTransactionRule(s.unusualFactor, s.unusualMinAmount, s.unusualMinHistory),
                FeesAndInterestRule(),
                RecurringPaymentsRule(s.recurringMonths, s.recurringTolerance),
                DuplicateSubscriptionsRule(s.recurringMonths, s.recurringTolerance),
                MerchantConcentrationRule(s.concentrationShare, s.concentrationMinCategory),
            ),
        )
    }
}

internal object Text {
    fun category(name: String?): String = name?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Uncategorized"

    fun money(v: BigDecimal, currency: String) = "${Money.format(v)} $currency"

    fun pct(ratio: Double) = "${Math.round(ratio * 100)}%"
}

/** Category total vs the average of the previous equal periods; flagged above +threshold (DAY, WEEK, MONTH). */
class CategorySpikeRule(private val threshold: BigDecimal, private val minDelta: BigDecimal) : InsightRule {
    override val type = InsightType.CATEGORY_SPIKE
    override val scales = setOf(Scale.DAY, Scale.WEEK, Scale.MONTH)
    override val needsBaseline = true

    override fun evaluate(input: InsightInput): List<Insight> {
        val n = input.baseline.size.toBigDecimal()
        return input.spendByCategory.mapNotNull { (category, amount) ->
            val avg = input.baseline.sumOf { it[category] ?: BigDecimal.ZERO }.divide(n, 2, RoundingMode.HALF_EVEN)
            if (avg.signum() == 0) return@mapNotNull null
            val delta = amount - avg
            if (delta < minDelta || amount < avg * (BigDecimal.ONE + threshold)) return@mapNotNull null
            val change = delta.divide(avg, 3, RoundingMode.HALF_EVEN).toDouble()
            Insight(
                type, if (change >= 0.5) Severity.WARNING else Severity.INFO,
                "${Text.category(category)} spending is ${Text.pct(change)} above your usual " +
                    "(${Text.money(amount, input.currency)} vs ${Text.money(avg, input.currency)})",
                mapOf("category" to category, "amountBase" to Money.format(amount), "baselineBase" to Money.format(avg), "changePct" to change),
            ) to delta
        }.sortedByDescending { it.second }.map { it.first }
    }
}

/** A payment far above what that merchant usually costs (needs some history at the merchant). All scales. */
class UnusualTransactionRule(private val factor: BigDecimal, private val minAmount: BigDecimal, private val minHistory: Int) : InsightRule {
    override val type = InsightType.UNUSUAL_TRANSACTION
    override val scales = Scale.entries.toSet()

    override fun evaluate(input: InsightInput): List<Insight> = input.transactions.mapNotNull { tx ->
        val history = input.merchantHistory[tx.merchantId].orEmpty()
        if (history.size < minHistory || tx.amount < minAmount) return@mapNotNull null
        val typical = median(history)
        if (typical.signum() == 0 || tx.amount < typical * factor) return@mapNotNull null
        Insight(
            type, Severity.WARNING,
            "${tx.merchantName} charged ${Text.money(tx.amount, input.currency)} on ${tx.date}, " +
                "usually about ${Text.money(typical, input.currency)}",
            mapOf(
                "transactionId" to tx.id.toString(), "date" to tx.date.toString(), "merchant" to tx.merchantName,
                "category" to tx.category, "amountBase" to Money.format(tx.amount), "typicalBase" to Money.format(typical),
            ),
        ) to tx.amount.divide(typical, MathContext.DECIMAL64)
    }.sortedByDescending { it.second }.take(5).map { it.first }

    companion object {
        fun median(values: List<BigDecimal>): BigDecimal {
            val s = values.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]).divide(BigDecimal(2), 2, RoundingMode.HALF_EVEN)
        }
    }
}

/** One merchant takes most of a category (with 2+ merchants), plus the change vs the previous period. WEEK, MONTH. */
class MerchantConcentrationRule(private val minShare: BigDecimal, private val minCategory: BigDecimal) : InsightRule {
    override val type = InsightType.MERCHANT_CONCENTRATION
    override val scales = setOf(Scale.WEEK, Scale.MONTH)

    override fun evaluate(input: InsightInput): List<Insight> {
        val previous = input.previousMerchantSpend?.let(::shares)
        return input.merchantSpend.filter { it.category != null }.groupBy { it.category!! }.mapNotNull { (category, rows) ->
            val total = rows.sumOf { it.amount }
            val known = rows.filter { it.merchantId != null }
            if (total < minCategory || known.size < 2) return@mapNotNull null
            val top = known.maxBy { it.amount }
            val share = top.amount.divide(total, 3, RoundingMode.HALF_EVEN)
            if (share < minShare) return@mapNotNull null
            val prev = previous?.get(category to top.merchantId)
            val change = prev?.let { " (${Text.pct(it)} in the previous period)" } ?: ""
            Insight(
                type, Severity.INFO,
                "${top.name} is ${Text.pct(share.toDouble())} of your ${Text.category(category).lowercase()} spending$change",
                mapOf(
                    "merchant" to top.name, "category" to category, "amountBase" to Money.format(top.amount),
                    "share" to share.toDouble(), "previousShare" to prev,
                ),
            ) to top.amount
        }.sortedByDescending { it.second }.take(3).map { it.first }
    }

    private fun shares(rows: List<MerchantSpend>): Map<Pair<String, UUID?>, Double> =
        rows.filter { it.category != null }.groupBy { it.category!! }.flatMap { (category, list) ->
            val total = list.sumOf { it.amount }
            list.map { (category to it.merchantId) to Money.ratio(it.amount, total) }
        }.toMap()
}

/** Bank fees, card interest and FX fees (ATM fees are BANK_FEES). WEEK, MONTH. */
class FeesAndInterestRule : InsightRule {
    override val type = InsightType.FEES_AND_INTEREST
    override val scales = setOf(Scale.WEEK, Scale.MONTH)

    override fun evaluate(input: InsightInput): List<Insight> {
        val items = FEE_CATEGORIES.mapNotNull { c ->
            val amount = input.spendByCategory[c] ?: return@mapNotNull null
            if (amount.signum() == 0) null else Triple(c, amount, input.countByCategory[c] ?: 0)
        }
        if (items.isEmpty()) return emptyList()
        val total = items.sumOf { it.second }
        val interest = items.any { it.first == "CARD_INTEREST" }
        val parts = items.joinToString(", ") { "${Text.category(it.first).lowercase()} ${Text.money(it.second, input.currency)}" }
        return listOf(
            Insight(
                type, if (interest) Severity.WARNING else Severity.INFO,
                "You paid ${Text.money(total, input.currency)} in fees and interest ($parts)",
                mapOf(
                    "totalBase" to Money.format(total),
                    "items" to items.map { mapOf("category" to it.first, "amountBase" to Money.format(it.second), "count" to it.third) },
                ),
            ),
        )
    }

    companion object {
        val FEE_CATEGORIES = listOf("BANK_FEES", "CARD_INTEREST", "FX_FEES")
    }
}

/** Shared by the two recurring rules: one similar payment per month at a merchant, N months in a row up to the period's end. */
internal object Recurring {
    data class Item(val merchantId: UUID, val name: String, val category: String?, val average: BigDecimal, val months: Int)

    fun detect(input: InsightInput, minMonths: Int, tolerance: BigDecimal): List<Item> {
        val last = YearMonth.from(input.period.end)
        return input.monthlyMerchantSpend.groupBy { it.merchantId }.mapNotNull { (merchantId, rows) ->
            val byMonth = rows.associateBy { it.month }
            val streak = generateSequence(last) { it.minusMonths(1) }
                .takeWhile { m -> byMonth[m]?.count == 1L }
                .map { byMonth.getValue(it) }
                .toList()
            if (streak.size < minMonths) return@mapNotNull null
            val window = streak.take(minMonths)
            val avg = window.sumOf { it.amount }.divide(window.size.toBigDecimal(), 2, RoundingMode.HALF_EVEN)
            if (avg.signum() == 0 || window.any { (it.amount - avg).abs() > avg * tolerance }) return@mapNotNull null
            val latest = streak.first()
            Item(merchantId, latest.name, latest.category, avg, streak.size)
        }.sortedByDescending { it.average }
    }
}

/** Same merchant, similar amount, N+ consecutive months. MONTH. */
class RecurringPaymentsRule(private val minMonths: Int, private val tolerance: BigDecimal) : InsightRule {
    override val type = InsightType.RECURRING_PAYMENTS
    override val scales = setOf(Scale.MONTH)

    override fun evaluate(input: InsightInput): List<Insight> {
        val items = Recurring.detect(input, minMonths, tolerance)
        if (items.isEmpty()) return emptyList()
        val total = items.sumOf { it.average }
        return listOf(
            Insight(
                type, Severity.INFO,
                "${items.size} recurring payment${if (items.size == 1) "" else "s"} cost about ${Text.money(total, input.currency)} a month",
                mapOf(
                    "totalBase" to Money.format(total),
                    "items" to items.map {
                        mapOf("merchant" to it.name, "category" to it.category, "amountBase" to Money.format(it.average), "months" to it.months)
                    },
                ),
            ),
        )
    }
}

/** Two or more recurring payments in the same subscription-like category. MONTH. */
class DuplicateSubscriptionsRule(private val minMonths: Int, private val tolerance: BigDecimal) : InsightRule {
    override val type = InsightType.DUPLICATE_SUBSCRIPTIONS
    override val scales = setOf(Scale.MONTH)

    override fun evaluate(input: InsightInput): List<Insight> =
        Recurring.detect(input, minMonths, tolerance)
            .filter { it.category in CATEGORIES }
            .groupBy { it.category!! }
            .filterValues { it.size >= 2 }
            .map { (category, items) ->
                val total = items.sumOf { it.average }
                Insight(
                    type, Severity.WARNING,
                    "${items.size} ${Text.category(category).lowercase()} payments every month " +
                        "(${items.joinToString(", ") { it.name }}), ${Text.money(total, input.currency)} together",
                    mapOf("category" to category, "merchants" to items.map { it.name }, "totalBase" to Money.format(total)),
                )
            }

    companion object {
        val CATEGORIES = setOf("SUBSCRIPTIONS", "ENTERTAINMENT", "SPORT", "EDUCATION")
    }
}

/** (income - spend) / income, with savings deposits alongside. MONTH. */
class SavingsRateRule : InsightRule {
    override val type = InsightType.SAVINGS_RATE
    override val scales = setOf(Scale.MONTH)

    override fun evaluate(input: InsightInput): List<Insight> {
        val rate = savingsRate(input.income, input.spend) ?: return emptyList()
        val kept = input.income - input.spend
        val message = if (rate < 0) {
            "You spent ${Text.money(kept.negate(), input.currency)} more than you earned"
        } else {
            "You kept ${Text.pct(rate)} of your income (${Text.money(kept, input.currency)})"
        }
        val deposits = if (input.savingsDeposits.signum() > 0) "; ${Text.money(input.savingsDeposits, input.currency)} went to savings" else ""
        return listOf(
            Insight(
                type, if (rate < 0) Severity.WARNING else Severity.INFO, message + deposits,
                mapOf(
                    "rate" to rate, "incomeBase" to Money.format(input.income), "spendBase" to Money.format(input.spend),
                    "savingsDepositsBase" to Money.format(input.savingsDeposits),
                ),
            ),
        )
    }

    companion object {
        fun savingsRate(income: BigDecimal, spend: BigDecimal): Double? =
            if (income.signum() <= 0) null else (income - spend).divide(income, 3, RoundingMode.HALF_EVEN).toDouble()
    }
}
