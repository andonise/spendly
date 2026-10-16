package dev.spendly.categorization

import dev.spendly.categorization.domain.CategorizationEngine
import dev.spendly.categorization.domain.LlmBatch
import dev.spendly.categorization.domain.LlmItem
import dev.spendly.categorization.domain.LlmPrompts
import dev.spendly.categorization.domain.TxInput
import dev.spendly.categorization.llm.EmbeddingGateway
import dev.spendly.categorization.llm.LlmGateway
import dev.spendly.categorization.llm.LlmMode
import dev.spendly.categorization.llm.LlmUnavailableException
import dev.spendly.categorization.persistence.Alias
import dev.spendly.categorization.persistence.LearningRepository
import dev.spendly.categorization.persistence.Neighbor
import dev.spendly.shared.config.SpendlyProperties
import dev.spendly.shared.observability.CategorizationMetrics
import dev.spendly.transaction.Category
import dev.spendly.transaction.CategorySource
import dev.spendly.transaction.KeywordRules
import dev.spendly.transaction.MerchantNormalizer
import dev.spendly.transaction.TransferReason
import dev.spendly.transaction.TransferStatus
import dev.spendly.transfer.domain.MatchMethod
import dev.spendly.transfer.domain.PairMatcher
import dev.spendly.transfer.domain.TransferCoverage
import dev.spendly.transfer.domain.TransferPatterns
import dev.spendly.transfer.domain.TransferTx
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

class TransferRulesTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val matcher = PairMatcher(3, BigDecimal("0.02"))

    private fun tx(
        account: UUID,
        date: String,
        amount: String,
        status: TransferStatus = TransferStatus.NONE,
        reason: TransferReason? = null,
        currency: String = "RON",
        base: String? = amount,
        token: String? = null,
    ) = TransferTx(UUID.randomUUID(), account, LocalDate.parse(date), BigDecimal(amount), currency, base?.let(::BigDecimal), status, reason, token)

    @Test
    fun `AC-MA-2 an outgoing and an incoming transfer within 3 days in different accounts are paired`() {
        val out = tx(a, "2026-08-10", "-500.00")
        val inc = tx(b, "2026-08-11", "500.00", TransferStatus.CANDIDATE, TransferReason.PATTERN)
        val pairs = matcher.match(listOf(inc, out), emptyMap())
        assertEquals(1, pairs.size)
        assertEquals(out.id, pairs[0].outgoing.id)
        assertEquals(inc.id, pairs[0].incoming.id)
        assertEquals(MatchMethod.AMOUNT_DATE, pairs[0].method)
    }

    @Test
    fun `no pair without a transfer-like side, outside the window, in the same account, or for EXTERNAL and user decisions`() {
        assertTrue(matcher.match(listOf(tx(a, "2026-08-10", "-500.00"), tx(b, "2026-08-11", "500.00")), emptyMap()).isEmpty(), "neither side looks like a transfer")
        val cand = { acc: UUID, d: String, amt: String -> tx(acc, d, amt, TransferStatus.CANDIDATE, TransferReason.PATTERN) }
        assertTrue(matcher.match(listOf(cand(a, "2026-08-10", "-500.00"), cand(b, "2026-08-14", "500.00")), emptyMap()).isEmpty(), "4 days apart")
        assertTrue(matcher.match(listOf(cand(a, "2026-08-10", "-500.00"), cand(a, "2026-08-10", "500.00")), emptyMap()).isEmpty(), "same account")
        assertTrue(matcher.match(listOf(cand(a, "2026-08-10", "-500.00"), cand(b, "2026-08-10", "500.01")), emptyMap()).isEmpty(), "different amount")
        assertTrue(
            matcher.match(listOf(cand(a, "2026-08-10", "-500.00"), tx(b, "2026-08-10", "500.00", TransferStatus.EXTERNAL, TransferReason.USER)), emptyMap()).isEmpty(),
            "the user said it is not a transfer",
        )
    }

    @Test
    fun `different currencies pair within the FX tolerance on amount_base`() {
        val out = tx(a, "2026-08-10", "-500.00", TransferStatus.CANDIDATE, TransferReason.PATTERN, "RON", "-500.00")
        val near = tx(b, "2026-08-10", "100.00", currency = "EUR", base = "497.50") // 0.5% gap
        val far = tx(c, "2026-08-10", "100.00", currency = "EUR", base = "480.00") // 4% gap
        assertEquals(near.id, matcher.match(listOf(out, near), emptyMap()).single().incoming.id)
        assertTrue(matcher.match(listOf(out, far), emptyMap()).isEmpty())
    }

    @Test
    fun `an own-IBAN side only pairs with the account it names, and the closest date wins`() {
        val tokenB = "b".repeat(64)
        val out = tx(a, "2026-08-10", "-200.00", TransferStatus.INTERNAL, TransferReason.OWN_IBAN, token = tokenB)
        val inC = tx(c, "2026-08-10", "200.00", TransferStatus.CANDIDATE, TransferReason.PATTERN)
        val inBFar = tx(b, "2026-08-12", "200.00")
        val inBNear = tx(b, "2026-08-11", "200.00")
        val pairs = matcher.match(listOf(out, inC, inBFar, inBNear), mapOf(a to "a".repeat(64), b to tokenB, c to "c".repeat(64)))
        assertEquals(1, pairs.size)
        assertEquals(inBNear.id, pairs[0].incoming.id)
        assertEquals(MatchMethod.IBAN, pairs[0].method)
    }

    @Test
    fun `each transaction is paired at most once`() {
        val out1 = tx(a, "2026-08-10", "-100.00", TransferStatus.CANDIDATE, TransferReason.PATTERN)
        val out2 = tx(a, "2026-08-10", "-100.00", TransferStatus.CANDIDATE, TransferReason.PATTERN)
        val inc = tx(b, "2026-08-10", "100.00", TransferStatus.CANDIDATE, TransferReason.PATTERN)
        assertEquals(1, matcher.match(listOf(out1, out2, inc), emptyMap()).size)
    }

    @Test
    fun `AC-MA-4 coverage needs every other active account covered for date plus or minus 3 days`() {
        val date = LocalDate.parse("2026-08-15")
        val accounts = listOf(
            TransferCoverage.AccountSpan(a, LocalDate.parse("2026-01-01"), null),
            TransferCoverage.AccountSpan(b, LocalDate.parse("2026-01-01"), null),
            TransferCoverage.AccountSpan(c, LocalDate.parse("2027-01-01"), null), // not active yet
        )
        val partial = mapOf(b to listOf(LocalDate.parse("2026-08-01") to LocalDate.parse("2026-08-16")))
        assertFalse(TransferCoverage.covered(date, a, accounts, partial, 3))
        val full = mapOf(b to listOf(LocalDate.parse("2026-08-01") to LocalDate.parse("2026-08-16"), LocalDate.parse("2026-08-17") to LocalDate.parse("2026-08-31")))
        assertTrue(TransferCoverage.covered(date, a, accounts, full, 3))
    }

    @Test
    fun `transfer patterns`() {
        listOf("Top-Up by *3333", "TRANSFER INTERN RO**…6666", "PLATA CARD CREDIT RO**…9999", "Rambursare card credit", "To Savings vault")
            .forEach { assertTrue(TransferPatterns.matches(it), it) }
        listOf("PLATA CARD ****1111 KAUFLAND 3412 IASI RO", "TRANSFER CATRE [PERSON#a3f9c1d2]", "Spotify")
            .forEach { assertFalse(TransferPatterns.matches(it), it) }
    }
}

class MerchantAndRulesTest {
    @Test
    fun `merchant normalizer strips channel prefixes, masks, store numbers, cities and countries`() {
        assertEquals("Kaufland", MerchantNormalizer.normalize("PLATA CARD ****1111 KAUFLAND 3412 IASI RO")!!.display)
        assertEquals("ALFA TRADE SRL", MerchantNormalizer.normalize("PLATA CARD ****1111 ALFA TRADE SRL 12 IASI RO")!!.key)
        assertEquals("ALFA TRADE SRL", MerchantNormalizer.normalize("POS ALFA TRADE SRL CLUJ")!!.key)
        assertEquals("Mega Image", MerchantNormalizer.normalize("Mega Image")!!.display)
        assertNull(MerchantNormalizer.normalize("TRANSFER CATRE [PERSON#4d37c156]"), "person transfers have no merchant")
    }

    @Test
    fun `AC-MA-6 keyword rules - interest, fees, cash, salary, rent - respect the sign`() {
        assertEquals(Category.CARD_INTEREST, KeywordRules.match("DOBANDA DEBITOARE", BigDecimal("-146.00")))
        assertEquals(Category.INCOME, KeywordRules.match("DOBANDA CREDITOARE", BigDecimal("3.10")))
        assertEquals(Category.BANK_FEES, KeywordRules.match("COMISION ADMINISTRARE CONT", BigDecimal("-15.00")))
        assertEquals(Category.BANK_FEES, KeywordRules.match("FEE Cash at Revolut ATM", BigDecimal("-5.00")))
        assertEquals(Category.CASH_WITHDRAWAL, KeywordRules.match("RETRAGERE NUMERAR ATM SPENDLY BANK IASI", BigDecimal("-400.00")))
        assertEquals(Category.INCOME, KeywordRules.match("INCASARE SALARIU ACME SOFTWARE SRL", BigDecimal("8500.00")))
        assertNull(KeywordRules.match("INCASARE SALARIU ACME SOFTWARE SRL", BigDecimal("-10.00")), "salary is never an outflow")
        assertEquals(Category.RENT_MORTGAGE, KeywordRules.match("TRANSFER CATRE [PERSON#4d37c156] RO**…0000 chirie august", BigDecimal("-2200.00")))
    }

    @Test
    fun `AC-MA-7 a counterparty labelled Landlord means rent`() {
        assertEquals(Category.RENT_MORTGAGE, KeywordRules.matchLabel("Landlord", BigDecimal("-2000.00")))
        assertEquals(Category.SPORT, KeywordRules.matchLabel("my gym", BigDecimal("-150.00")))
        assertNull(KeywordRules.matchLabel("Partner", BigDecimal("-100.00")))
    }

    @Test
    fun `AC-LLM-1 the LLM is on by default in application yml and in the properties`() {
        assertTrue(SpendlyProperties().llm.enabled)
        val yaml = YamlPropertySourceLoader().load("app", ClassPathResource("application.yml")).first()
        assertEquals("\${SPENDLY_LLM_ENABLED:true}", yaml.getProperty("spendly.llm.enabled").toString())
    }
}

/** The layers with mocked learning tables and models (no DB, no Ollama). */
class CategorizationEngineTest {
    private val properties = SpendlyProperties()
    private val learning = mockk<LearningRepository>(relaxed = true)
    private val embeddings = mockk<EmbeddingGateway>()
    private val llm = mockk<LlmGateway>()
    private val mode = LlmMode(properties)
    private val engine = CategorizationEngine(learning, embeddings, llm, mode, properties)

    private fun input(description: String, amount: String = "-10.00", candidate: Boolean = false, label: String? = null) =
        TxInput(UUID.randomUUID(), description, BigDecimal(amount), "RON", label, candidate)

    private fun llmReturns(vararg items: Triple<Category?, Double, String?>) {
        every { llm.entity(any(), any(), LlmBatch::class.java) } returns LlmBatch().apply {
            results = items.mapIndexed { i, (c, conf, q) ->
                LlmItem().apply { id = i + 1; category = c; confidence = conf; question = q; suggestions = listOf(Category.SPORT) }
            }
        }
    }

    init {
        every { learning.aliases() } returns listOf(Alias("KAUFLAND", UUID.randomUUID(), "Kaufland", Category.GROCERIES, "SYSTEM"))
        every { learning.merchantsMissingEmbedding(any()) } returns emptyList()
        every { learning.nearestMerchants(any(), any()) } returns emptyList()
        every { learning.nearestExamples(any(), any()) } returns emptyList()
        every { learning.hints(any()) } returns emptyList()
        every { learning.merchantIdsByKeys(any()) } returns emptyMap()
        every { embeddings.embed(any()) } answers { firstArg<List<String>>().map { FloatArray(768) } }
    }

    @Test
    fun `AC-LLM-1 rules, then embeddings, then the LLM`() {
        every { learning.nearestMerchants(any(), any()) } returns listOf(Neighbor("Zeta Bistro", Category.RESTAURANTS, 0.97, false))
        llmReturns(Triple(Category.SHOPPING, 0.95, null))
        val kaufland = input("PLATA CARD ****1111 KAUFLAND 3412 IASI RO")
        val result = engine.categorize(listOf(kaufland))
        assertEquals(CategorySource.RULE, result.decisions.single().source)

        // Second call: embeddings decide (similarity 0.97 >= 0.90) before the LLM is asked.
        val zeta = input("POS ZETA BISTRO IASI")
        assertEquals(CategorySource.EMBEDDING, engine.categorize(listOf(zeta)).decisions.single().source)
        verify(exactly = 0) { llm.entity(any(), any(), LlmBatch::class.java) }

        every { learning.nearestMerchants(any(), any()) } returns listOf(Neighbor("Zeta Bistro", Category.RESTAURANTS, 0.5, false))
        val orion = input("POS ORION BOOKS IASI")
        val d = engine.categorize(listOf(orion)).decisions.single()
        assertEquals(CategorySource.LLM, d.source)
        assertEquals(Category.SHOPPING, d.category)
    }

    @Test
    fun `low LLM confidence becomes a question with the LLM's text and suggestions`() {
        llmReturns(Triple(Category.SPORT, 0.4, "Is SC ALFA TRADE SRL a gym, a supplier, or a person?"))
        val d = engine.categorize(listOf(input("POS ALFA TRADE SRL IASI"))).decisions.single()
        assertNull(d.category)
        assertEquals("Is SC ALFA TRADE SRL a gym, a supplier, or a person?", d.question)
        assertTrue(Category.SPORT in d.suggestions)
    }

    @Test
    fun `AC-LLM-2 LLM off - no call, generic question with embedding suggestions`() {
        every { learning.nearestMerchants(any(), any()) } returns listOf(Neighbor("Alfa", Category.SPORT, 0.6, false))
        val d = engine.categorize(listOf(input("POS OMEGA ATELIER IASI")), llmEnabled = false).decisions.single()
        assertEquals("Which category is \"Omega Atelier\"?", d.question)
        assertEquals(listOf(Category.SPORT), d.suggestions)
        verify(exactly = 0) { llm.entity(any(), any(), LlmBatch::class.java) }
    }

    @Test
    fun `AC-LLM-2 LLM and embeddings down - rules still apply, the rest become questions, nothing throws`() {
        every { embeddings.embed(any()) } returns null
        every { llm.entity(any(), any(), LlmBatch::class.java) } throws LlmUnavailableException("down")
        val result = engine.categorize(listOf(input("PLATA CARD ****1111 KAUFLAND 3412 IASI RO"), input("POS OMEGA ATELIER IASI")))
        assertEquals(CategorySource.RULE, result.decisions[0].source)
        assertTrue(result.decisions[1].question!!.startsWith("Which category is"))
        assertFalse(result.llmAvailable)
        assertFalse(result.embeddingsAvailable)
    }

    @Test
    fun `a transfer candidate is never a CATEGORY question, and a counterparty label decides as a rule`() {
        llmReturns(Triple(null, 0.1, "What is this?"))
        val cand = engine.categorize(listOf(input("Top-Up by *3333", "500.00", candidate = true))).decisions.single()
        assertNull(cand.question)
        val landlord = engine.categorize(listOf(input("TRANSFER CATRE [PERSON#4d37c156] RO**…0000", "-2000.00", label = "Landlord"))).decisions.single()
        assertEquals(Category.RENT_MORTGAGE, landlord.category)
        assertEquals(CategorySource.RULE, landlord.source)
    }

    @Test
    fun `the prompt carries only the transaction descriptions, the enum, hints and examples`() {
        val user = LlmPrompts.user(listOf(input("PLATA CARD ****1111 KAUFLAND 3412 IASI RO", "-187.43")), emptyMap())
        assertTrue(user.contains("1 | out | 187.43 RON | PLATA CARD ****1111 KAUFLAND 3412 IASI RO"))
        val system = LlmPrompts.system(listOf("Alfa Trade is my gym"), listOf(Neighbor("Zeta Bistro", Category.RESTAURANTS, 0.8, true)))
        Category.forLlm.forEach { assertTrue(system.contains("- ${it.name}:")) }
        assertFalse(system.contains("- INTERNAL_TRANSFER:"))
        assertTrue(system.contains("Alfa Trade is my gym"))
        assertTrue(system.contains("\"Zeta Bistro\" -> RESTAURANTS"))
    }

    @Nested
    inner class Metrics {
        @Test
        fun `metrics count transactions per layer`() {
            val registry = SimpleMeterRegistry()
            val metrics = CategorizationMetrics(registry)
            metrics.categorized(CategorySource.RULE.name, 3)
            metrics.transfers("matched")
            assertEquals(3.0, registry.get("spendly.categorization.transactions").tag("layer", "RULE").counter().count())
            assertEquals(1.0, registry.get("spendly.transfers").tag("outcome", "matched").counter().count())
        }
    }
}
