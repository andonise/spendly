package dev.spendly.it

import dev.spendly.account.domain.AccountService
import dev.spendly.account.persistence.AccountType
import dev.spendly.account.web.AccountCreate
import dev.spendly.categorization.llm.LlmMode
import dev.spendly.report.domain.AccountScope
import dev.spendly.report.domain.DateRange
import dev.spendly.report.domain.ReportService
import dev.spendly.report.persistence.LedgerReader
import dev.spendly.report.persistence.ReportRepository
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.ImportedTransaction
import dev.spendly.shared.messaging.ParserInfo
import dev.spendly.shared.messaging.Producers
import dev.spendly.shared.messaging.ReportRequestedPayload
import dev.spendly.shared.messaging.TransactionsImportedPayload
import dev.spendly.shared.privacy.Descriptions
import dev.spendly.shared.privacy.Sanitizer
import dev.spendly.statement.FileType
import dev.spendly.statement.importing.ImportService
import dev.spendly.statement.parsing.TxHashes
import dev.spendly.statement.persistence.StatementRepository
import dev.spendly.support.IntegrationTest
import dev.spendly.support.ScriptedChatModel
import dev.spendly.support.TestIbans
import dev.spendly.support.eventually
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Phase 4 end to end on Testcontainers: CATEGORIZED statements → TransactionsChanged → aggregator (daily_aggregates,
 * stale reports, ReportRequested) → report builder → reports API. Combined (ALL) reports include every account in the
 * shared test database, so exact numbers are asserted on per-account scopes; each test uses its own months (2029-2030).
 */
class ReportIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var statements: StatementRepository
    @Autowired lateinit var importService: ImportService
    @Autowired lateinit var sanitizer: Sanitizer
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var chat: ScriptedChatModel
    @Autowired lateinit var mode: LlmMode
    @Autowired lateinit var reportService: ReportService
    @Autowired lateinit var reports: ReportRepository
    @Autowired lateinit var reader: LedgerReader

    data class Acc(val id: UUID, val iban: String)

    data class Row(val date: String, val amount: String, val raw: String, val currency: String = "RON")

    @BeforeEach
    fun reset() {
        chat.reset()
        mode.set(true)
    }

    // ------------------------------------------------------------------------------------------ helpers

    private fun account(type: AccountType = AccountType.CURRENT, currency: String = "RON", activeFrom: String = "2029-01-01"): Acc {
        val iban = TestIbans.random()
        val id = accounts.create(AccountCreate("R ${type.name} ${UUID.randomUUID()}", "Generic Bank", type, currency, iban, null, LocalDate.parse(activeFrom))).id
        return Acc(id, iban)
    }

    /** Imports sanitized rows directly (like a parser); the pipeline continues via Kafka. Returns the statement ID. */
    private fun import(account: Acc, periodStart: String, periodEnd: String, rows: List<Row>, currency: String = "RON"): UUID {
        val statementId = UUID.randomUUID()
        tx.executeWithoutResult {
            statements.insert(statementId, account.id, FileType.CSV, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""), "rp-$statementId")
        }
        val seen = HashMap<List<String>, Int>()
        val txs = rows.mapIndexed { i, r ->
            val s = sanitizer.sanitize(r.raw)
            val description = Descriptions.finalize(s.text)
            val occ = seen.merge(listOf(r.date, r.amount, description), 1, Int::plus)!! - 1
            val cp = s.counterparty()
            ImportedTransaction(
                TxHashes.hash(account.id, LocalDate.parse(r.date), r.amount, r.currency, description, occ),
                i, r.date, null, r.amount, r.currency, description, cp?.first, cp?.second?.name,
            )
        }
        val payload = TransactionsImportedPayload(
            statementId.toString(), account.id.toString(), ParserInfo("test-parser", "1.0.0"), periodStart, periodEnd, currency, txs.size, txs,
        )
        importService.importTransactions(EventEnvelope.create(EventTypes.TRANSACTIONS_IMPORTED, "rp-$statementId", payload).copy(producer = Producers.PDF_PARSER))
        return statementId
    }

    private fun body(path: String): JsonNode = json.readTree(mvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun awaitCategorized(vararg ids: UUID) = ids.forEach { id ->
        eventually(message = "statement $id did not reach CATEGORIZED") { body("/api/statements/$id").path("status").asString() == "CATEGORIZED" }
    }

    /** Waits until the aggregator has processed the account's dates (spend in daily_aggregates equals [spend]). */
    private fun awaitSpend(account: Acc, from: String, to: String, spend: String) = eventually(message = "aggregates of ${account.id} not $spend") {
        aggregateSum(account, from, to, "SPEND").compareTo(BigDecimal(spend)) == 0
    }

    private fun aggregateSum(account: Acc, from: String, to: String, kind: String): BigDecimal = jdbc.sql(
        "SELECT COALESCE(sum(amount_base), 0) FROM daily_aggregates WHERE account_id = :a AND kind = :k AND date BETWEEN CAST(:f AS date) AND CAST(:t AS date)",
    ).param("a", account.id).param("k", kind).param("f", from).param("t", to).query(BigDecimal::class.java).single()

    private fun reportPath(granularity: String, from: String, to: String? = null, vararg accs: Acc) =
        "/api/reports?granularity=$granularity&from=$from" + (to?.let { "&to=$it" } ?: "") + accs.joinToString("") { "&accountId=${it.id}" }

    /** GETs a report until it is READY and not stale (each GET requests the rebuild of a stale report). */
    private fun freshReport(path: String, condition: (JsonNode) -> Boolean = { true }): JsonNode {
        var last: JsonNode? = null
        eventually(message = "report $path not fresh: $last") {
            val r = body(path)
            last = r
            r.path("status").asString() == "READY" && !r.path("stale").asBoolean() && condition(r)
        }
        return last!!
    }

    private fun totals(r: JsonNode) = r.path("summary").path("totals")

    private fun txIdByText(statementId: UUID, text: String): String =
        body("/api/transactions?statementId=$statementId&size=200").path("items").toList()
            .single { it.path("description").asString().contains(text) }.path("id").asString()

    // ------------------------------------------------------------------------------------------ tests

    @Test
    fun `AC-RP-1 AC-MA-9 totals of any granularity reconcile with the aggregates and exclude internal transfers`() {
        val bt = account()
        val rev = account(AccountType.E_WALLET)
        val savings = account(AccountType.SAVINGS)
        val s1 = import(
            bt, "2029-01-01", "2029-01-31",
            listOf(
                Row("2029-01-05", "6000.00", "SALARIU IANUARIE ANGAJATOR SRL"),
                Row("2029-01-08", "-412.30", "PLATA CARD 4111 1111 1111 1111 KAUFLAND IASI RO"),
                Row("2029-01-10", "-500.00", "TRANSFER CATRE REVOLUT ${rev.iban}"),
                Row("2029-01-15", "-1000.00", "TRANSFER ECONOMII ${savings.iban}"),
                Row("2029-01-20", "-7.50", "COMISION ADMINISTRARE CONT"),
            ),
        )
        val s2 = import(
            rev, "2029-01-01", "2029-01-31",
            listOf(
                Row("2029-01-11", "500.00", "Top-up from ${bt.iban}"),
                Row("2029-01-12", "-120.00", "LIDL 501 IASI"),
                Row("2029-01-13", "-35.40", "UBER TRIP"),
            ),
        )
        awaitCategorized(s1, s2)
        awaitSpend(bt, "2029-01-01", "2029-01-31", "419.80")
        awaitSpend(rev, "2029-01-01", "2029-01-31", "155.40")

        val month = freshReport(reportPath("MONTH", "2029-01-01", null, bt, rev)) { totals(it).path("spendBase").asString() == "575.20" }
        assertEquals("6000.00", totals(month).path("incomeBase").asString()) // the top-up is internal, not income
        assertEquals("1000.00", totals(month).path("savingsDepositsBase").asString())
        assertEquals("5424.80", totals(month).path("netBase").asString())
        assertEquals(0.904, totals(month).path("savingsRate").asDouble())
        assertEquals("COMPLETE", month.path("summary").path("coverage").path("status").asString())
        assertEquals(listOf(bt.id.toString(), rev.id.toString()).sorted(), month.path("accountIds").toList().map { it.asString() })
        assertFalse(month.path("summary").path("byCategory").toList().any { it.path("category").asString() in setOf("INTERNAL_TRANSFER", "INCOME") })
        assertEquals(31, month.path("summary").path("trend").size())
        assertEquals(6, month.path("summary").path("history").size())
        assertEquals("2029-01-01", month.path("summary").path("history").last().path("from").asString())

        // AC-MA-9: combined spending = sum of the per-account external outflows
        val perAccount = listOf(bt, rev).map { a -> BigDecimal(totals(freshReport(reportPath("MONTH", "2029-01-01", null, a))).path("spendBase").asString()) }
        assertEquals(BigDecimal("575.20"), perAccount.reduce(BigDecimal::add))
        val byAccount = month.path("summary").path("byAccount").toList().associate { it.path("accountId").asString() to it.path("spendBase").asString() }
        assertEquals(mapOf(bt.id.toString() to "419.80", rev.id.toString() to "155.40"), byAccount)

        // AC-RP-1: day, week and custom totals = sums of daily_aggregates over the same range
        for ((g, from, to) in listOf(Triple("DAY", "2029-01-12", null), Triple("WEEK", "2029-01-08", null), Triple("CUSTOM", "2029-01-09", "2029-01-21"))) {
            val r = freshReport(reportPath(g, from, to, bt, rev))
            val end = to ?: r.path("periodEnd").asString()
            val expected = aggregateSum(bt, from, end, "SPEND") + aggregateSum(rev, from, end, "SPEND")
            assertEquals(0, BigDecimal(totals(r).path("spendBase").asString()).compareTo(expected), "$g $from")
        }
        val day = freshReport(reportPath("DAY", "2029-01-12", null, bt, rev))
        assertEquals(0, day.path("summary").path("trend").size())
        assertTrue(totals(day).path("savingsRate").isNull)

        // The month got its combined report automatically (MONTH_STARTED), built by the report builder.
        eventually(message = "monthly ALL report") {
            jdbc.sql("SELECT status FROM reports WHERE granularity = 'MONTH' AND period_start = '2029-01-01' AND account_scope = 'ALL'")
                .query(String::class.java).optional().orElse(null) in setOf("READY", "STALE")
        }
    }

    @Test
    fun `AC-RP-2 coverage lists the gap of an account that covers only part of the month`() {
        val bt = account()
        val rev = account(AccountType.E_WALLET)
        awaitCategorized(
            import(bt, "2029-03-01", "2029-03-31", listOf(Row("2029-03-05", "-50.00", "KAUFLAND 12"))),
            import(rev, "2029-03-15", "2029-03-31", listOf(Row("2029-03-20", "-20.00", "LIDL 7"))),
        )
        val c = body("/api/coverage?from=2029-03-01&to=2029-03-31&accountId=${bt.id}&accountId=${rev.id}")
        assertEquals("PARTIAL", c.path("status").asString())
        val revCoverage = c.path("accounts").toList().single { it.path("accountId").asString() == rev.id.toString() }
        assertEquals("2029-03-01", revCoverage.path("gaps")[0].path("from").asString())
        assertEquals("2029-03-14", revCoverage.path("gaps")[0].path("to").asString())
        assertEquals("COMPLETE", body("/api/coverage?from=2029-03-20&to=2029-03-20&accountId=${bt.id}&accountId=${rev.id}").path("status").asString())

        awaitSpend(rev, "2029-03-01", "2029-03-31", "20.00")
        val report = freshReport(reportPath("MONTH", "2029-03-01", null, bt, rev))
        assertEquals("PARTIAL", report.path("summary").path("coverage").path("status").asString())
        assertEquals("COMPLETE", freshReport(reportPath("DAY", "2029-03-20", null, bt, rev)).path("summary").path("coverage").path("status").asString())
    }

    @Test
    fun `AC-RP-3 AC-RP-4 a correction makes every overlapping report stale, the monthly one is rebuilt eagerly`() {
        val acc = account()
        val s = import(
            acc, "2029-04-01", "2029-04-30",
            listOf(Row("2029-04-12", "-80.00", "PLATA CARD 4111 1111 1111 1111 KAUFLAND 3"), Row("2029-04-20", "-30.00", "LIDL 9")),
        )
        awaitCategorized(s)
        awaitSpend(acc, "2029-04-01", "2029-04-30", "110.00")
        val month = freshReport(reportPath("MONTH", "2029-04-01", null, acc))
        val week = freshReport(reportPath("WEEK", "2029-04-09", null, acc))
        val day = freshReport(reportPath("DAY", "2029-04-12", null, acc))

        // AC-RP-4: the same request is served from `reports`, not rebuilt
        val again = body(reportPath("MONTH", "2029-04-01", null, acc))
        assertEquals(month.path("id"), again.path("id"))
        assertEquals(month.path("builtAt"), again.path("builtAt"))
        assertEquals(month.path("version"), again.path("version"))

        val txId = txIdByText(s, "KAUFLAND")
        mvc.perform(patch("/api/transactions/$txId/category").contentType(MediaType.APPLICATION_JSON).content("""{"category":"RESTAURANTS"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.category").value("RESTAURANTS")).andExpect(jsonPath("$.categorySource").value("USER"))

        val ids = listOf(month, week, day).map { UUID.fromString(it.path("id").asString()) }
        eventually(message = "reports not stale after the correction") { ids.all { reports.findById(it)!!.version > 1 } }
        // the month is rebuilt by the report builder without anyone viewing it
        eventually(message = "monthly report not rebuilt") {
            val r = reports.findById(ids[0])!!
            r.builtVersion == r.version
        }
        val rebuilt = body("/api/reports/${ids[0]}")
        assertEquals("80.00", rebuilt.path("summary").path("byCategory").toList().single { it.path("category").asString() == "RESTAURANTS" }.path("amountBase").asString())
        // the week and the day stay stale until viewed; the view serves the old numbers with stale = true
        assertEquals("STALE", reports.findById(ids[2])!!.status.name)
        val staleDay = body(reportPath("DAY", "2029-04-12", null, acc))
        assertTrue(staleDay.path("stale").asBoolean())
        assertEquals("GROCERIES", staleDay.path("summary").path("byCategory")[0].path("category").asString())
        val freshDay = freshReport("/api/reports/${ids[2]}")
        assertEquals("RESTAURANTS", freshDay.path("summary").path("byCategory")[0].path("category").asString())
        assertEquals("RESTAURANTS", freshReport(reportPath("WEEK", "2029-04-09", null, acc)).path("summary").path("byCategory")[0].path("category").asString())
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM reports WHERE granularity = 'DAY' AND period_start = '2029-04-12' AND account_scope = :s")
            .param("s", acc.id.toString()).query(Long::class.java).single())
    }

    @Test
    fun `AC-TX-3 a build that started before a change is discarded`() {
        val acc = account()
        awaitCategorized(import(acc, "2029-05-01", "2029-05-31", listOf(Row("2029-05-03", "-15.00", "KAUFLAND 1"))))
        awaitSpend(acc, "2029-05-01", "2029-05-31", "15.00")
        val r = freshReport(reportPath("MONTH", "2029-05-01", null, acc))
        val id = UUID.fromString(r.path("id").asString())
        val v = r.path("version").asInt()
        tx.executeWithoutResult { reports.markStale(id) } // a transaction change moves the report to v + 1
        assertFalse(reportService.buildAndSave(id, v)) // the v build must not be saved
        assertEquals(v, reports.findById(id)!!.builtVersion)
        assertTrue(reportService.buildAndSave(id, v + 1))
        assertEquals(v + 1, reports.findById(id)!!.builtVersion)
    }

    @Test
    fun `AC-TX-5 a build reads one REPEATABLE READ snapshot`() {
        val acc = account()
        awaitCategorized(import(acc, "2029-06-01", "2029-06-30", listOf(Row("2029-06-03", "-15.00", "KAUFLAND 2"))))
        awaitSpend(acc, "2029-06-01", "2029-06-30", "15.00")
        val scope = AccountScope.of(listOf(acc.id))
        val june = DateRange(LocalDate.parse("2029-06-01"), LocalDate.parse("2029-06-30"))
        val firstRead = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val reads = CompletableFuture.supplyAsync {
            reportService.inSnapshot {
                val level = reader.isolationLevel()
                val before = reader.aggregates(scope, june).sumOf { it.amount }
                firstRead.countDown()
                changed.await(10, TimeUnit.SECONDS)
                Triple(level, before, reader.aggregates(scope, june).sumOf { it.amount })
            }
        }
        assertTrue(firstRead.await(10, TimeUnit.SECONDS))
        // a concurrent aggregate rebuild commits new numbers in the middle of the build
        tx.executeWithoutResult {
            jdbc.sql("UPDATE daily_aggregates SET amount_base = amount_base + 100 WHERE account_id = :a").param("a", acc.id).update()
        }
        changed.countDown()
        val (level, before, after) = reads.get(10, TimeUnit.SECONDS)
        assertEquals("repeatable read", level)
        assertEquals(0, before.compareTo(after)) // all old numbers, never a mix
    }

    @Test
    fun `AC-RP-6 a EUR transaction counts in RON with the last BNR rate on or before its booking date`() {
        tx.executeWithoutResult {
            jdbc.sql("INSERT INTO fx_rates (date, currency, rate_to_base, source) VALUES ('2029-06-01', 'EUR', 4.9800, 'MANUAL') ON CONFLICT DO NOTHING").update()
        }
        val eur = account(AccountType.E_WALLET, currency = "EUR")
        val s = import(eur, "2029-06-01", "2029-06-30", listOf(Row("2029-06-03", "-10.00", "LIDL 44 BERLIN", currency = "EUR")), currency = "EUR") // a Sunday
        awaitCategorized(s)
        awaitSpend(eur, "2029-06-01", "2029-06-30", "49.80")
        val r = freshReport(reportPath("MONTH", "2029-06-01", null, eur))
        assertEquals("49.80", totals(r).path("spendBase").asString())
        assertEquals("RON", r.path("currency").asString())
    }

    @Test
    fun `insights for a month with history, fees, savings and recurring payments`() {
        val acc = account()
        val months = listOf("2029-07", "2029-08", "2029-09")
        val ids = months.mapIndexed { i, m ->
            val rows = mutableListOf(
                Row("$m-01", "5000.00", "SALARIU $m ANGAJATOR SRL"),
                Row("$m-03", "-59.99", "NETFLIX.COM"),
                Row("$m-04", "-49.99", "SPOTIFY P1"),
                Row("$m-05", "-300.00", "KAUFLAND ${i + 10}"),
                Row("$m-06", "-100.00", "LIDL ${i + 20}"),
                Row("$m-07", "-80.00", "STARBUCKS ${i + 30}"),
            )
            if (i == 2) {
                rows += Row("$m-08", "-400.00", "STARBUCKS 99") // restaurants spike
                rows += Row("$m-09", "-146.00", "DOBANDA CARD CREDIT")
            }
            import(acc, "$m-01", "$m-${if (m == "2029-09") "30" else "31"}", rows)
        }
        awaitCategorized(*ids.toTypedArray())
        awaitSpend(acc, "2029-09-01", "2029-09-30", "1135.98")

        val r = freshReport(reportPath("MONTH", "2029-09-01", null, acc)) { it.path("insights").size() > 0 }
        val baseline = r.path("summary").path("baseline")
        assertEquals(2, baseline.path("periodsUsed").asInt()) // July and August are COMPLETE, June has no statement
        assertTrue(baseline.path("enoughHistory").asBoolean())
        val types = r.path("insights").toList().map { it.path("type").asString() }.toSet()
        assertTrue(types.containsAll(setOf("CATEGORY_SPIKE", "FEES_AND_INTEREST", "SAVINGS_RATE", "RECURRING_PAYMENTS", "DUPLICATE_SUBSCRIPTIONS", "MERCHANT_CONCENTRATION")), "$types")
        assertEquals("WARNING", r.path("insights")[0].path("severity").asString())
        val spike = r.path("insights").toList().single { it.path("type").asString() == "CATEGORY_SPIKE" }
        assertEquals("RESTAURANTS", spike.path("data").path("category").asString())
        assertEquals(listOf("Starbucks", "Kaufland"), r.path("summary").path("topMerchants").toList().take(2).map { it.path("name").asString() })

        // cold start: July has no complete history before it
        val july = freshReport(reportPath("MONTH", "2029-07-01", null, acc))
        assertFalse(july.path("summary").path("baseline").path("enoughHistory").asBoolean())
        assertFalse(july.path("insights").toList().any { it.path("type").asString() == "CATEGORY_SPIKE" })
    }

    @Test
    fun `category correction rules, learning and the report API validation`() {
        val bt = account()
        val rev = account(AccountType.E_WALLET)
        val s = import(
            bt, "2030-01-01", "2030-01-31",
            listOf(Row("2030-01-04", "-61.00", "POS ZETA PET CORNER IASI"), Row("2030-01-05", "-200.00", "TRANSFER CATRE REVOLUT ${rev.iban}")),
        )
        awaitCategorized(s)
        val shop = txIdByText(s, "ZETA PET")
        val transfer = txIdByText(s, "TRANSFER CATRE REVOLUT")

        fun correct(id: String, body: String) = mvc.perform(patch("/api/transactions/$id/category").contentType(MediaType.APPLICATION_JSON).content(body))
        correct(shop, """{"category":"INTERNAL_TRANSFER"}""").andExpect(status().isBadRequest)
        correct(shop, """{"category":"NOPE"}""").andExpect(status().isBadRequest)
        correct(UUID.randomUUID().toString(), """{"category":"PETS"}""").andExpect(status().isNotFound)
        correct(transfer, """{"category":"SHOPPING"}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("TRANSACTION_IS_TRANSFER"))
        jdbc.sql("UPDATE statements SET status = 'NEEDS_INPUT' WHERE id = :id").param("id", s).update()
        correct(shop, """{"category":"PETS"}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("STATEMENT_NOT_CATEGORIZED"))
        jdbc.sql("UPDATE statements SET status = 'CATEGORIZED' WHERE id = :id").param("id", s).update()

        correct(shop, """{"category":"PETS","applyToFuture":true}""").andExpect(status().isOk).andExpect(jsonPath("$.category").value("PETS"))
        assertEquals(
            "PETS",
            jdbc.sql("SELECT m.default_category FROM merchant_aliases a JOIN merchants m ON m.id = a.merchant_id WHERE a.pattern LIKE '%ZETA PET CORNER%' AND a.created_by = 'USER'")
                .query(String::class.java).single(),
        )
        assertTrue(jdbc.sql("SELECT payload::text FROM outbox WHERE event_type = 'TransactionsChanged' ORDER BY seq DESC LIMIT 5")
            .query(String::class.java).list().filterNotNull().any { it.contains("MANUAL_CORRECTION") && it.contains(bt.id.toString()) })

        mvc.perform(get("/api/reports?granularity=WEEK&from=2030-01-07&to=2030-01-14")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/reports?granularity=WEEK&from=2030-01-08")).andExpect(status().isBadRequest) // a Tuesday
        mvc.perform(get("/api/reports?granularity=YEAR&from=2030-01-01")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/reports?granularity=MONTH&from=2030-01-01&accountId=${UUID.randomUUID()}")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/reports/${UUID.randomUUID()}")).andExpect(status().isNotFound)
        mvc.perform(get("/api/coverage?from=2030-01-01&to=2031-06-01")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/coverage?from=2030-01-02&to=2030-01-01")).andExpect(status().isBadRequest)
    }

    @Test
    fun `a dead-lettered build marks the report FAILED and the next view rebuilds it, a new account makes it stale`() {
        val acc = account()
        awaitCategorized(import(acc, "2030-02-01", "2030-02-28", listOf(Row("2030-02-03", "-25.00", "LIDL 3"))))
        awaitSpend(acc, "2030-02-01", "2030-02-28", "25.00")
        val r = freshReport(reportPath("WEEK", "2030-02-25", null, acc))
        val id = UUID.fromString(r.path("id").asString())
        tx.executeWithoutResult { reports.markStale(id) }
        val v = reports.findById(id)!!.version
        val env = EventEnvelope.create(
            EventTypes.REPORT_REQUESTED, "rp-dlq",
            ReportRequestedPayload(id.toString(), v, "WEEK", "2030-02-25", "2030-03-03", acc.id.toString(), "STALE_VIEWED"),
        )
        reportService.failed(env, "RETRIES_EXHAUSTED")
        reportService.failed(env, "RETRIES_EXHAUSTED") // duplicate delivery is harmless
        assertEquals("FAILED", reports.findById(id)!!.status.name)
        assertEquals("READY", body(reportPath("WEEK", "2030-02-25", null, acc)).path("status").asString())

        // the combined view depends on the set of accounts: a new account makes a READY ALL report stale on its next view
        val all = freshReport(reportPath("DAY", "2030-02-03"))
        val before = all.path("version").asInt()
        account()
        val after = body(reportPath("DAY", "2030-02-03"))
        assertNotEquals(before, after.path("version").asInt())
        assertTrue(after.path("stale").asBoolean())
    }
}
