package dev.spendly.report

import dev.spendly.report.domain.AccountInfo
import dev.spendly.report.domain.AccountScope
import dev.spendly.report.domain.CategorySpikeRule
import dev.spendly.report.domain.CoverageCalculator
import dev.spendly.report.domain.CoverageStatus
import dev.spendly.report.domain.DateRange
import dev.spendly.report.domain.DuplicateSubscriptionsRule
import dev.spendly.report.domain.FeesAndInterestRule
import dev.spendly.report.domain.Granularity
import dev.spendly.report.domain.InsightEngine
import dev.spendly.report.domain.InsightInput
import dev.spendly.report.domain.InsightType
import dev.spendly.report.domain.MerchantConcentrationRule
import dev.spendly.report.domain.MerchantSpend
import dev.spendly.report.domain.MonthlyMerchant
import dev.spendly.report.domain.Period
import dev.spendly.report.domain.RecurringPaymentsRule
import dev.spendly.report.domain.SavingsRateRule
import dev.spendly.report.domain.Scale
import dev.spendly.report.domain.Severity
import dev.spendly.report.domain.SpendTx
import dev.spendly.report.domain.Unconfirmed
import dev.spendly.report.domain.UnusualTransactionRule
import dev.spendly.shared.config.SpendlyProperties
import dev.spendly.shared.web.BadRequestException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun d(s: String) = LocalDate.parse(s)
private fun r(from: String, to: String) = DateRange(d(from), d(to))
private fun bd(s: String) = BigDecimal(s)

class PeriodTest {

    @Test
    fun `day, week and month fill in the end date and validate the start`() {
        assertEquals(r("2026-08-12", "2026-08-12"), Period.of(Granularity.DAY, d("2026-08-12"), null, 366).range)
        assertEquals(r("2026-08-10", "2026-08-16"), Period.of(Granularity.WEEK, d("2026-08-10"), null, 366).range)
        assertEquals(r("2026-02-01", "2026-02-28"), Period.of(Granularity.MONTH, d("2026-02-01"), d("2026-02-28"), 366).range)
        assertThrows<BadRequestException> { Period.of(Granularity.WEEK, d("2026-08-11"), null, 366) } // a Tuesday
        assertThrows<BadRequestException> { Period.of(Granularity.MONTH, d("2026-08-02"), null, 366) }
        assertThrows<BadRequestException> { Period.of(Granularity.DAY, d("2026-08-12"), d("2026-08-13"), 366) }
        assertThrows<BadRequestException> { Period.of(Granularity.CUSTOM, d("2026-08-12"), null, 366) }
        assertThrows<BadRequestException> { Period.of(Granularity.CUSTOM, d("2026-08-12"), d("2026-08-11"), 366) }
        assertThrows<BadRequestException> { Period.of(Granularity.CUSTOM, d("2025-01-01"), d("2026-08-11"), 366) }
    }

    @Test
    fun `scale follows the length`() {
        assertEquals(Scale.DAY, Period.day(d("2026-08-12")).scale)
        assertEquals(Scale.WEEK, Period.of(Granularity.WEEK, d("2026-08-10"), null, 366).scale)
        assertEquals(Scale.WEEK, Period.of(Granularity.CUSTOM, d("2026-08-01"), d("2026-08-03"), 366).scale)
        assertEquals(Scale.MONTH, Period.month(YearMonth.of(2026, 2)).scale)
        assertEquals(Scale.MONTH, Period.of(Granularity.CUSTOM, d("2026-08-01"), d("2026-09-15"), 366).scale)
    }

    @Test
    fun `baselines are previous periods of the same kind`() {
        assertEquals(
            listOf(d("2026-08-05"), d("2026-07-29")),
            Period.day(d("2026-08-12")).baseline(2).map { it.start }, // same weekday
        )
        assertEquals(r("2026-08-03", "2026-08-09"), Period.of(Granularity.WEEK, d("2026-08-10"), null, 366).baseline(1).single().range)
        assertEquals(
            listOf(r("2026-02-01", "2026-02-28"), r("2026-01-01", "2026-01-31")),
            Period.month(YearMonth.of(2026, 3)).baseline(2).map { it.range },
        )
        assertEquals(r("2026-07-22", "2026-07-31"), Period.of(Granularity.CUSTOM, d("2026-08-01"), d("2026-08-10"), 366).baseline(1).single().range)
        assertEquals(r("2026-08-11", "2026-08-11"), Period.day(d("2026-08-12")).previous().range)
    }

    @Test
    fun `account scope keys are stable`() {
        val a = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        val b = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        assertEquals("ALL", AccountScope.of(null).key)
        assertEquals("ALL", AccountScope.of(emptyList()).key)
        assertEquals("$b,$a", AccountScope.of(listOf(a, b, a)).key)
        assertEquals(AccountScope.of(listOf(a, b)), AccountScope.parse("$b,$a"))
        assertTrue(AccountScope.all.includes(a))
    }
}

class CoverageCalculatorTest {
    private val bt = AccountInfo(UUID.randomUUID(), "BT", "CURRENT", d("2026-01-01"), null)
    private val revolut = AccountInfo(UUID.randomUUID(), "Revolut", "E_WALLET", d("2026-01-01"), null)

    @Test
    fun `AC-RP-2 August is PARTIAL with the Revolut gap, 20 August is COMPLETE`() {
        val statements = mapOf(bt.id to listOf(r("2026-08-01", "2026-08-31")), revolut.id to listOf(r("2026-08-15", "2026-08-31")))
        val august = CoverageCalculator.compute(r("2026-08-01", "2026-08-31"), listOf(bt, revolut), statements, Unconfirmed.NONE)
        assertEquals(CoverageStatus.PARTIAL, august.status)
        assertEquals(emptyList<DateRange>(), august.accounts.first { it.account == bt }.gaps)
        assertEquals(listOf(r("2026-08-01", "2026-08-14")), august.accounts.first { it.account == revolut }.gaps)

        val day = CoverageCalculator.compute(r("2026-08-20", "2026-08-20"), listOf(bt, revolut), statements, Unconfirmed.NONE)
        assertEquals(CoverageStatus.COMPLETE, day.status)
    }

    @Test
    fun `unconfirmed transfers keep a period PARTIAL`() {
        val statements = mapOf(bt.id to listOf(r("2026-08-01", "2026-08-31")))
        val c = CoverageCalculator.compute(r("2026-08-01", "2026-08-31"), listOf(bt), statements, Unconfirmed(1, bd("500")))
        assertEquals(CoverageStatus.PARTIAL, c.status)
    }

    @Test
    fun `only active days count`() {
        val late = AccountInfo(UUID.randomUUID(), "New", "CURRENT", d("2026-08-20"), null)
        val closed = AccountInfo(UUID.randomUUID(), "Old", "CURRENT", d("2025-01-01"), d("2026-07-31"))
        val c = CoverageCalculator.compute(
            r("2026-08-01", "2026-08-31"), listOf(late, closed), mapOf(late.id to listOf(r("2026-08-20", "2026-08-31"))), Unconfirmed.NONE,
        )
        assertEquals(listOf(late.id), c.accounts.map { it.account.id }) // the closed account is not active in August
        assertEquals(CoverageStatus.COMPLETE, c.status)
    }

    @Test
    fun `gaps between overlapping and adjacent statements`() {
        val gaps = CoverageCalculator.subtract(
            r("2026-08-01", "2026-08-31"),
            listOf(r("2026-08-05", "2026-08-10"), r("2026-08-08", "2026-08-12"), r("2026-08-13", "2026-08-15"), r("2026-08-20", "2026-09-30")),
        )
        assertEquals(listOf(r("2026-08-01", "2026-08-04"), r("2026-08-16", "2026-08-19")), gaps)
        assertEquals(listOf(r("2026-08-01", "2026-08-31")), CoverageCalculator.subtract(r("2026-08-01", "2026-08-31"), emptyList()))
        assertEquals(emptyList<DateRange>(), CoverageCalculator.subtract(r("2026-08-01", "2026-08-31"), listOf(r("2026-07-01", "2026-09-01"))))
    }

    @Test
    fun `fingerprint changes when an account is closed`() {
        val before = CoverageCalculator.fingerprint(listOf(bt, revolut))
        assertEquals(before, CoverageCalculator.fingerprint(listOf(revolut, bt)))
        assertTrue(before != CoverageCalculator.fingerprint(listOf(bt, revolut.copy(closedAt = d("2026-09-01")))))
    }
}

class InsightRulesTest {
    private val settings = SpendlyProperties.Reports()
    private val kaufland = UUID.randomUUID()
    private val lidl = UUID.randomUUID()
    private val netflix = UUID.randomUUID()
    private val hbo = UUID.randomUUID()

    private fun input(
        period: Period = Period.month(YearMonth.of(2026, 8)),
        spend: String = "0",
        income: String = "0",
        savings: String = "0",
        byCategory: Map<String, String> = emptyMap(),
        baseline: List<Map<String, String>> = emptyList(),
        enoughHistory: Boolean = baseline.size >= 2,
        merchants: List<MerchantSpend> = emptyList(),
        previous: List<MerchantSpend>? = null,
        transactions: List<SpendTx> = emptyList(),
        history: Map<UUID, List<String>> = emptyMap(),
        monthly: List<MonthlyMerchant> = emptyList(),
    ) = InsightInput(
        period, "RON", bd(spend), bd(income), bd(savings),
        byCategory.mapValues { bd(it.value) }, byCategory.mapValues { 1L },
        baseline.map { m -> m.mapValues { bd(it.value) } }, enoughHistory,
        merchants, previous, transactions, history.mapValues { (_, v) -> v.map(::bd) }, monthly,
    )

    @Test
    fun `category spike above 20 percent of the baseline average, for each scale`() {
        val rule = CategorySpikeRule(settings.spikeThreshold, settings.spikeMinDelta)
        val baseline = listOf(mapOf("RESTAURANTS" to "500"), mapOf("RESTAURANTS" to "600"), mapOf("GROCERIES" to "900"))
        val month = rule.evaluate(input(byCategory = mapOf("RESTAURANTS" to "812.40", "GROCERIES" to "310"), baseline = baseline)).single()
        assertEquals(InsightType.CATEGORY_SPIKE, month.type)
        assertEquals("RESTAURANTS", month.data["category"])
        assertEquals("366.67", month.data["baselineBase"]) // (500 + 600 + 0) / 3
        assertEquals(Severity.WARNING, month.severity)
        assertTrue(month.message.startsWith("Restaurants spending is 122% above your usual"))

        // +20% but below the minimum absolute difference: not flagged
        assertTrue(rule.evaluate(input(byCategory = mapOf("FUEL" to "120"), baseline = listOf(mapOf("FUEL" to "90"), mapOf("FUEL" to "110")))).isEmpty())
        // below +20%: not flagged
        assertTrue(rule.evaluate(input(byCategory = mapOf("FUEL" to "650"), baseline = listOf(mapOf("FUEL" to "600"), mapOf("FUEL" to "600")))).isEmpty())

        val day = rule.evaluate(input(period = Period.day(d("2026-08-12")), byCategory = mapOf("TRANSPORT" to "160"), baseline = listOf(mapOf("TRANSPORT" to "40"), mapOf("TRANSPORT" to "60"))))
        assertEquals(Severity.WARNING, day.single().severity)
        val week = rule.evaluate(input(period = Period.of(Granularity.WEEK, d("2026-08-10"), null, 366), byCategory = mapOf("SHOPPING" to "390"), baseline = listOf(mapOf("SHOPPING" to "300"), mapOf("SHOPPING" to "300"))))
        assertEquals(Severity.INFO, week.single().severity)
    }

    @Test
    fun `unusual transaction against the merchant median`() {
        val rule = UnusualTransactionRule(settings.unusualFactor, settings.unusualMinAmount, settings.unusualMinHistory)
        val big = SpendTx(UUID.randomUUID(), d("2026-08-12"), kaufland, "Kaufland", "GROCERIES", bd("640"))
        val normal = SpendTx(UUID.randomUUID(), d("2026-08-13"), kaufland, "Kaufland", "GROCERIES", bd("150"))
        val unknown = SpendTx(UUID.randomUUID(), d("2026-08-13"), lidl, "Lidl", "GROCERIES", bd("900"))
        val found = rule.evaluate(
            input(transactions = listOf(big, normal, unknown), history = mapOf(kaufland to listOf("150", "180", "120", "210"), lidl to listOf("100"))),
        )
        assertEquals(1, found.size) // Lidl has too little history
        assertEquals(big.id.toString(), found.single().data["transactionId"])
        assertEquals("165.00", found.single().data["typicalBase"])
        // every scale, including DAY
        assertTrue(InsightEngine(listOf(rule)).evaluate(input(period = Period.day(d("2026-08-12")), transactions = listOf(big), history = mapOf(kaufland to listOf("150", "180", "120")))).isNotEmpty())
        assertEquals(bd("2.50"), UnusualTransactionRule.median(listOf(bd("1"), bd("4"), bd("2"), bd("3"))))
    }

    @Test
    fun `merchant concentration with the previous share`() {
        val rule = MerchantConcentrationRule(settings.concentrationShare, settings.concentrationMinCategory)
        val now = listOf(
            MerchantSpend(kaufland, "Kaufland", "GROCERIES", bd("800"), 6),
            MerchantSpend(lidl, "Lidl", "GROCERIES", bd("200"), 2),
            MerchantSpend(netflix, "Netflix", "SUBSCRIPTIONS", bd("60"), 1), // category too small
        )
        val prev = listOf(MerchantSpend(kaufland, "Kaufland", "GROCERIES", bd("300"), 3), MerchantSpend(lidl, "Lidl", "GROCERIES", bd("300"), 3))
        val found = rule.evaluate(input(merchants = now, previous = prev)).single()
        assertEquals(0.8, found.data["share"])
        assertEquals(0.5, found.data["previousShare"])
        assertTrue(found.message.contains("Kaufland is 80% of your groceries spending (50% in the previous period)"))
        assertNull(rule.evaluate(input(merchants = now)).single().data["previousShare"])
        // a single merchant in the category is not a "concentration"
        assertTrue(rule.evaluate(input(merchants = listOf(MerchantSpend(kaufland, "Kaufland", "GROCERIES", bd("900"), 6)))).isEmpty())
    }

    @Test
    fun `fees and interest`() {
        val rule = FeesAndInterestRule()
        val found = rule.evaluate(input(byCategory = mapOf("BANK_FEES" to "12.50", "CARD_INTEREST" to "146", "GROCERIES" to "500"))).single()
        assertEquals("158.50", found.data["totalBase"])
        assertEquals(Severity.WARNING, found.severity) // card interest
        assertEquals(Severity.INFO, rule.evaluate(input(byCategory = mapOf("FX_FEES" to "3.20"))).single().severity)
        assertTrue(rule.evaluate(input(byCategory = mapOf("GROCERIES" to "500"))).isEmpty())
    }

    private fun monthly(id: UUID, name: String, category: String, vararg amounts: Pair<String, String>) =
        amounts.map { (month, amount) -> MonthlyMerchant(id, name, category, YearMonth.parse(month), bd(amount), 1) }

    @Test
    fun `recurring payments and duplicate subscriptions`() {
        val rows = monthly(netflix, "Netflix", "SUBSCRIPTIONS", "2026-06" to "59.99", "2026-07" to "59.99", "2026-08" to "62.99") +
            monthly(hbo, "HBO Max", "SUBSCRIPTIONS", "2026-05" to "40", "2026-06" to "40", "2026-07" to "40", "2026-08" to "40") +
            monthly(kaufland, "Kaufland", "GROCERIES", "2026-06" to "400", "2026-07" to "900", "2026-08" to "450") + // varies too much
            monthly(lidl, "Lidl", "GROCERIES", "2026-07" to "100", "2026-08" to "100") // only 2 months
        val recurring = RecurringPaymentsRule(settings.recurringMonths, settings.recurringTolerance).evaluate(input(monthly = rows)).single()
        @Suppress("UNCHECKED_CAST")
        val items = recurring.data["items"] as List<Map<String, Any?>>
        assertEquals(listOf("Netflix", "HBO Max"), items.map { it["merchant"] })
        assertEquals(4, items.last()["months"])
        assertEquals("100.99", recurring.data["totalBase"])

        val duplicates = DuplicateSubscriptionsRule(settings.recurringMonths, settings.recurringTolerance).evaluate(input(monthly = rows)).single()
        assertEquals(listOf("Netflix", "HBO Max"), duplicates.data["merchants"])
        // two payments in one month break the streak
        val twice = rows + MonthlyMerchant(hbo, "HBO Max", "SUBSCRIPTIONS", YearMonth.of(2026, 8), bd("80"), 2)
        assertTrue(DuplicateSubscriptionsRule(settings.recurringMonths, settings.recurringTolerance).evaluate(input(monthly = twice.filterNot { it.merchantId == hbo && it.count == 1L && it.month == YearMonth.of(2026, 8) })).isEmpty())
    }

    @Test
    fun `savings rate`() {
        val rule = SavingsRateRule()
        val found = rule.evaluate(input(spend = "4100", income = "5000", savings = "500")).single()
        assertEquals(0.18, found.data["rate"])
        assertTrue(found.message.contains("You kept 18% of your income (900.00 RON); 500.00 RON went to savings"))
        assertEquals(Severity.WARNING, rule.evaluate(input(spend = "5200", income = "5000")).single().severity)
        assertTrue(rule.evaluate(input(spend = "100")).isEmpty()) // no income
    }

    @Test
    fun `AC-RP-5 a daily report only runs DAY rules, and baseline rules need history`() {
        val engine = InsightEngine.standard(settings)
        val day = input(
            period = Period.day(d("2026-08-12")), spend = "500", income = "5000",
            byCategory = mapOf("CARD_INTEREST" to "146", "RESTAURANTS" to "300"),
            baseline = listOf(mapOf("RESTAURANTS" to "50"), mapOf("RESTAURANTS" to "60")),
        )
        assertEquals(setOf(InsightType.CATEGORY_SPIKE), engine.evaluate(day).map { it.type }.toSet())
        assertEquals(
            setOf(InsightType.CATEGORY_SPIKE, InsightType.FEES_AND_INTEREST, InsightType.SAVINGS_RATE),
            engine.evaluate(day.copy(period = Period.month(YearMonth.of(2026, 8)))).map { it.type }.toSet(),
        )
        // cold start: one complete baseline period is not enough
        val cold = day.copy(baseline = day.baseline.take(1), enoughHistory = false)
        assertTrue(engine.evaluate(cold).isEmpty())
        assertEquals(Severity.WARNING, engine.evaluate(day.copy(period = Period.month(YearMonth.of(2026, 8)))).first().severity)
    }
}
