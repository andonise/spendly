package dev.spendly.it

import dev.spendly.account.domain.AccountService
import dev.spendly.account.persistence.AccountType
import dev.spendly.account.web.AccountCreate
import dev.spendly.categorization.llm.LlmMode
import dev.spendly.shared.crypto.HmacService
import dev.spendly.shared.messaging.ChangeCause
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.ImportedTransaction
import dev.spendly.shared.messaging.ParserInfo
import dev.spendly.shared.messaging.Producers
import dev.spendly.shared.messaging.TransactionsChangedPayload
import dev.spendly.shared.messaging.TransactionsImportedPayload
import dev.spendly.shared.privacy.Descriptions
import dev.spendly.shared.privacy.Sanitizer
import dev.spendly.statement.FileType
import dev.spendly.statement.importing.ImportService
import dev.spendly.statement.parsing.TxHashes
import dev.spendly.statement.persistence.StatementRepository
import dev.spendly.support.HashEmbeddingModel
import dev.spendly.support.IntegrationTest
import dev.spendly.support.ScriptedChatModel
import dev.spendly.support.TestIbans
import dev.spendly.support.eventually
import dev.spendly.transfer.domain.TransferMatcherService
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Phase 3 end to end on Testcontainers: TransactionsImported → StatementParsed (outbox → Kafka) → transfer detection
 * + categorization (scripted test LLM, recording every prompt) → questions → answers → resume → CATEGORIZED.
 *
 * Pair matching and transfer-question coverage look at ALL accounts, and the learning tables are global, so every
 * test uses its own months, amounts and merchant names (other test classes use August 2026).
 */
class Phase3IT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var statements: StatementRepository
    @Autowired lateinit var importService: ImportService
    @Autowired lateinit var sanitizer: Sanitizer
    @Autowired lateinit var hmac: HmacService
    @Autowired lateinit var chat: ScriptedChatModel
    @Autowired lateinit var embeddings: HashEmbeddingModel
    @Autowired lateinit var mode: LlmMode
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var matcher: TransferMatcherService

    data class Acc(val id: UUID, val iban: String)

    data class Row(val date: String, val amount: String, val raw: String)

    @BeforeEach
    fun reset() {
        chat.reset()
        embeddings.down = false
        mode.set(true)
    }

    @AfterEach
    fun restore() {
        chat.reset()
        embeddings.down = false
        mode.set(true)
    }

    // ------------------------------------------------------------------------------------------ helpers

    private fun account(type: AccountType = AccountType.CURRENT, activeFrom: String = "2026-01-01", closedAt: String? = null): Acc {
        val iban = TestIbans.random()
        val id = accounts.create(AccountCreate("P3 ${type.name} ${UUID.randomUUID()}", "Generic Bank", type, "RON", iban, null, LocalDate.parse(activeFrom))).id
        if (closedAt != null) jdbc.sql("UPDATE accounts SET closed_at = :c WHERE id = :id").param("c", LocalDate.parse(closedAt)).param("id", id).update()
        return Acc(id, iban)
    }

    private fun ibanToken(iban: String) = hmac.ibanToken(iban.filterNot { it == ' ' })

    /** Sanitizes raw lines like a parser does, then imports them directly (no file); the pipeline continues via Kafka. */
    private fun import(account: Acc, periodStart: String, periodEnd: String, rows: List<Row>): UUID {
        val statementId = UUID.randomUUID()
        tx.executeWithoutResult {
            statements.insert(statementId, account.id, FileType.CSV, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""), "p3-$statementId")
        }
        val seen = HashMap<List<String>, Int>()
        val txs = rows.mapIndexed { i, r ->
            val s = sanitizer.sanitize(r.raw)
            val description = Descriptions.finalize(s.text)
            val occ = seen.merge(listOf(r.date, r.amount, description), 1, Int::plus)!! - 1
            val cp = s.counterparty()
            ImportedTransaction(
                TxHashes.hash(account.id, LocalDate.parse(r.date), r.amount, "RON", description, occ),
                i, r.date, null, r.amount, "RON", description, cp?.first, cp?.second?.name,
            )
        }
        val payload = TransactionsImportedPayload(
            statementId.toString(), account.id.toString(), ParserInfo("test-parser", "1.0.0"), periodStart, periodEnd, "RON", txs.size, txs,
        )
        importService.importTransactions(EventEnvelope.create(EventTypes.TRANSACTIONS_IMPORTED, "p3-$statementId", payload).copy(producer = Producers.PDF_PARSER))
        return statementId
    }

    private fun body(path: String): JsonNode = json.readTree(mvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun statement(id: UUID) = body("/api/statements/$id")

    private fun awaitStatus(id: UUID, expected: String, timeoutMs: Long = 30_000): JsonNode {
        var last: JsonNode? = null
        eventually(timeoutMs, message = "statement $id did not reach $expected") {
            val current = statement(id)
            last = current
            current.path("status").asString() == expected
        }
        return last!!
    }

    private fun transactions(statementId: UUID): List<JsonNode> =
        body("/api/transactions?statementId=$statementId&size=200&sort=bookingDate,asc").path("items").toList()

    private fun txByText(statementId: UUID, text: String): JsonNode =
        transactions(statementId).single { it.path("description").asString().contains(text) }

    private fun questions(statementId: UUID): List<JsonNode> = body("/api/statements/$statementId/questions").toList()

    private fun submit(statementId: UUID, answers: String) = mvc.perform(
        post("/api/statements/$statementId/answers").contentType(MediaType.APPLICATION_JSON).content("""{"answers":[$answers]}"""),
    ).andReturn().response

    private fun answer(questionId: String, payload: String) = mvc.perform(
        post("/api/questions/$questionId/answer").contentType(MediaType.APPLICATION_JSON).content(payload),
    ).andReturn().response

    /** Outbox envelopes of [type] whose JSON mentions [needle] (an ID). */
    private fun outbox(type: String, needle: String): List<JsonNode> =
        jdbc.sql("SELECT payload::text FROM outbox WHERE event_type = :t ORDER BY seq").param("t", type).query(String::class.java).list().filterNotNull()
            .filter { it.contains(needle) }.map { json.readTree(it) }

    private fun dbString(sql: String, id: UUID): String? = jdbc.sql(sql).param("id", id).query(String::class.java).optional().orElse(null)

    private fun lowConfidenceFor(vararg needles: String) {
        chat.responder = { row ->
            val n = needles.firstOrNull { row.description.uppercase().contains(it) }
            if (n != null) ScriptedChatModel.Answer(null, 0.3, "Is $n a gym, a supplier, or a person?", listOf("SPORT", "SHOPPING"))
            else ScriptedChatModel.defaultAnswer(row)
        }
    }

    // ------------------------------------------------------------------------------------------ transfers

    @Test
    fun `AC-MA-1 a transfer to the IBAN of another registered account is INTERNAL and excluded from spending`() {
        val bt = account()
        val rev = account(AccountType.E_WALLET)
        val s = import(
            bt, "2027-03-01", "2027-03-31",
            listOf(
                Row("2027-03-05", "-321.17", "TRANSFER CATRE REVOLUT ${rev.iban.chunked(4).joinToString(" ")}"),
                Row("2027-03-06", "-40.00", "PLATA CARD 4111 1111 1111 1111 LIDL 12 IASI RO"),
            ),
        )
        val done = awaitStatus(s, "CATEGORIZED")
        val transfer = txByText(s, "TRANSFER CATRE REVOLUT")
        assertEquals("INTERNAL", transfer.path("transferStatus").asString())
        assertEquals("INTERNAL_TRANSFER", transfer.path("category").asString())
        assertEquals("TRANSFER_MATCH", transfer.path("categorySource").asString())
        assertEquals(rev.id.toString(), transfer.path("counterparty").path("ownAccountId").asString())
        assertTrue(transfer.path("counterparty").path("isOwnAccount").asBoolean())
        assertEquals("OWN_IBAN", dbString("SELECT transfer_reason FROM transactions WHERE id = :id", UUID.fromString(transfer.path("id").asString())))
        assertEquals("40.00", done.path("summary").path("spendBase").asString())
        assertEquals(1, done.path("summary").path("internalTransferCount").asInt())
        assertFalse(done.path("summary").path("byCategory").toList().any { it.path("category").asString() == "INTERNAL_TRANSFER" })

        // A registered account's token is always an own account (contract clarification 4).
        mvc.perform(put("/api/counterparties/${ibanToken(rev.iban)}").contentType(MediaType.APPLICATION_JSON).content("""{"label":"Rev","isOwnAccount":false}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COUNTERPARTY_IS_REGISTERED_ACCOUNT"))
        mvc.perform(put("/api/counterparties/NOT-A-TOKEN").contentType(MediaType.APPLICATION_JSON).content("""{"isOwnAccount":true}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(put("/api/counterparties/${"0".repeat(64)}").contentType(MediaType.APPLICATION_JSON).content("""{"isOwnAccount":true}"""))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `AC-MA-2 and AC-MA-3 the pair is matched whichever statement arrives first, both sides INTERNAL`() {
        val bt = account()
        val rev = account(AccountType.E_WALLET)
        // Revolut first: its top-up is only a CANDIDATE, excluded from income.
        val revS = import(rev, "2027-02-01", "2027-02-28", listOf(Row("2027-02-11", "500.00", "Top-Up by *3333"), Row("2027-02-12", "-120.00", "Kaufland")))
        val revDone = awaitStatus(revS, "CATEGORIZED")
        assertEquals("CANDIDATE", txByText(revS, "Top-Up").path("transferStatus").asString())
        assertEquals("0.00", revDone.path("summary").path("incomeBase").asString(), "a CANDIDATE is excluded from income")
        assertEquals(0, revDone.path("summary").path("internalTransferCount").asInt())

        // The BT statement arrives later: -500 on 10 Feb pairs with +500 on 11 Feb.
        val btS = import(bt, "2027-02-01", "2027-02-28", listOf(Row("2027-02-10", "-500.00", "TRANSFER CATRE REVOLUT"), Row("2027-02-13", "-50.00", "PLATA CARD ****1111 KAUFLAND 3412 IASI RO")))
        val btDone = awaitStatus(btS, "CATEGORIZED")
        val out = txByText(btS, "TRANSFER CATRE REVOLUT")
        val inc = txByText(revS, "Top-Up")
        assertEquals("INTERNAL", out.path("transferStatus").asString())
        assertEquals("INTERNAL", inc.path("transferStatus").asString())
        assertEquals(out.path("transferPairId").asString(), inc.path("transferPairId").asString())
        assertEquals(inc.path("id").asString(), out.path("pairedTransactionId").asString())
        assertEquals(out.path("id").asString(), inc.path("pairedTransactionId").asString())
        assertEquals("AMOUNT_DATE", dbString("SELECT match_method FROM transfer_pairs WHERE id = :id", UUID.fromString(out.path("transferPairId").asString())))

        // Neither side counts as spending or income.
        assertEquals("50.00", btDone.path("summary").path("spendBase").asString())
        val revNow = statement(revS).path("summary")
        assertEquals("0.00", revNow.path("incomeBase").asString())
        assertEquals("120.00", revNow.path("spendBase").asString())
        assertEquals(1, revNow.path("internalTransferCount").asInt())

        // One TransactionsChanged (TRANSFER_MATCHED) for both accounts and both dates: the trigger for report staleness (Phase 4).
        val matched = outbox(EventTypes.TRANSACTIONS_CHANGED, bt.id.toString()).map { it.path("payload") }
            .filter { it.path("cause").asString() == ChangeCause.TRANSFER_MATCHED.name }
        assertEquals(1, matched.size)
        assertEquals(setOf(bt.id.toString(), rev.id.toString()), matched[0].path("accountIds").toList().map { it.asString() }.toSet())
        assertEquals("2027-02-10", matched[0].path("fromDate").asString())
        assertEquals("2027-02-11", matched[0].path("toDate").asString())

        // Filters on the transactions API.
        val internal = body("/api/transactions?accountId=${rev.id}&transferStatus=INTERNAL").path("items")
        assertEquals(1, internal.size())
    }

    @Test
    fun `the transfer matcher re-matches on TransactionsChanged and a redelivered event is harmless`() {
        val a = account()
        val b = account(AccountType.SAVINGS)
        val aS = import(a, "2027-11-01", "2027-11-30", listOf(Row("2027-11-20", "-733.00", "DEPUNERE ECONOMII")))
        awaitStatus(aS, "CATEGORIZED")
        val bS = import(b, "2027-11-01", "2027-11-30", listOf(Row("2027-11-21", "733.00", "Depunere")))
        awaitStatus(bS, "CATEGORIZED")
        eventually(message = "pair matched") { txByText(aS, "DEPUNERE").path("transferStatus").asString() == "INTERNAL" }
        assertEquals("INTERNAL", txByText(bS, "Depunere").path("transferStatus").asString())

        val env = EventEnvelope.create(
            EventTypes.TRANSACTIONS_CHANGED, "p3-redeliver",
            TransactionsChangedPayload(listOf(a.id.toString()), "2027-11-20", "2027-11-20", ChangeCause.MANUAL_CORRECTION.name, null),
        )
        val pairsBefore = jdbc.sql("SELECT count(*) FROM transfer_pairs").query(Long::class.java).single()
        matcher.handle(env)
        matcher.handle(env)
        assertEquals(pairsBefore, jdbc.sql("SELECT count(*) FROM transfer_pairs").query(Long::class.java).single())
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id").param("id", UUID.fromString(env.eventId)).query(Long::class.java).single())
        assertEquals(txByText(bS, "Depunere").path("id").asString(), txByText(aS, "DEPUNERE").path("pairedTransactionId").asString())
    }

    @Test
    fun `AC-MA-4 an unmatched candidate becomes a TRANSFER question once the other accounts cover the date, and it can be answered`() {
        // Accounts active only in 2025, so accounts of other tests (active from 2026) do not affect coverage.
        val rev = account(AccountType.E_WALLET, "2025-01-01", "2025-12-31")
        val cur = account(AccountType.CURRENT, "2025-01-01", "2025-12-31")
        val thirdA = TestIbans.random()
        val thirdB = TestIbans.random()
        val revS = import(
            rev, "2025-03-01", "2025-03-31",
            listOf(
                Row("2025-03-15", "250.00", "Top-Up by *9999"),
                Row("2025-03-16", "-77.00", "TRANSFER INTERN $thirdA"),
                Row("2025-03-17", "-88.00", "TRANSFER INTERN $thirdB"),
                Row("2025-03-18", "-10.00", "Kaufland"),
            ),
        )
        val revDone = awaitStatus(revS, "CATEGORIZED")
        assertEquals("CANDIDATE", txByText(revS, "Top-Up").path("transferStatus").asString())
        assertEquals("10.00", revDone.path("summary").path("spendBase").asString(), "unconfirmed transfers are excluded from totals")
        assertEquals("0.00", revDone.path("summary").path("incomeBase").asString())
        val topUpId = txByText(revS, "Top-Up").path("id").asString()
        assertTrue(body("/api/questions").toList().none { it.path("transaction").path("id").asString() == topUpId }, "not asked before the other account is covered")

        // The current account's statement covers 15 Mar ± 3 days and has no matching amounts.
        val curS = import(cur, "2025-03-01", "2025-03-31", listOf(Row("2025-03-14", "-12.34", "PLATA CARD ****1111 KAUFLAND 3412 IASI RO")))
        awaitStatus(curS, "CATEGORIZED")
        val asked = HashMap<String, JsonNode>()
        eventually(message = "TRANSFER questions created") {
            body("/api/questions").toList().filter { it.path("kind").asString() == "TRANSFER" && it.path("transaction").path("accountId").asString() == rev.id.toString() }
                .forEach { asked[it.path("transaction").path("description").asString()] = it }
            asked.size == 3
        }
        val topUpQ = asked.entries.single { it.key.contains("Top-Up") }.value
        assertEquals("Is this a transfer to one of your accounts?", topUpQ.path("questionText").asString())
        assertTrue(topUpQ.path("statementId").isNull)
        assertEquals("INTERNAL_TRANSFER", topUpQ.path("suggestions").get(0).asString())
        assertTrue(topUpQ.path("transaction").path("counterparty").isNull)
        val qA = asked.entries.single { it.key.contains("TRANSFER INTERN") && it.value.path("transaction").path("amount").asString() == "-77.00" }.value
        val qB = asked.entries.single { it.value.path("transaction").path("amount").asString() == "-88.00" }.value
        assertEquals(ibanToken(thirdA), qA.path("transaction").path("counterparty").path("token").asString())

        // Yes → INTERNAL. A second answer → 409 QUESTION_NOT_OPEN.
        assertEquals(202, answer(topUpQ.path("id").asString(), """{"category":"INTERNAL_TRANSFER","applyToFuture":true}""").status)
        assertEquals("INTERNAL", txByText(revS, "Top-Up").path("transferStatus").asString())
        val again = answer(topUpQ.path("id").asString(), """{"skip":true}""")
        assertEquals(409, again.status)
        assertTrue(again.contentAsString.contains("QUESTION_NOT_OPEN"))
        // No with applyToFuture → EXTERNAL with that category; the counterparty is NOT marked own (clarification 2).
        assertEquals(202, answer(qA.path("id").asString(), """{"category":"SHOPPING","applyToFuture":true}""").status)
        val a = transactions(revS).single { it.path("amount").asString() == "-77.00" }
        assertEquals("EXTERNAL", a.path("transferStatus").asString())
        assertEquals("SHOPPING", a.path("category").asString())
        assertEquals("USER", a.path("categorySource").asString())
        assertFalse(a.path("counterparty").path("isOwnAccount").asBoolean())
        // Yes with applyToFuture and a counterparty token → that counterparty becomes an own account.
        assertEquals(202, answer(qB.path("id").asString(), """{"category":"INTERNAL_TRANSFER","applyToFuture":true}""").status)
        val b = transactions(revS).single { it.path("amount").asString() == "-88.00" }
        assertEquals("INTERNAL", b.path("transferStatus").asString())
        assertTrue(b.path("counterparty").path("isOwnAccount").asBoolean())
        val cps = body("/api/counterparties").toList()
        assertTrue(cps.single { it.path("token").asString() == ibanToken(thirdB) }.path("isOwnAccount").asBoolean())

        val summary = statement(revS).path("summary")
        assertEquals("87.00", summary.path("spendBase").asString(), "Kaufland 10 + the external 77")
        assertEquals(2, summary.path("internalTransferCount").asInt())
        assertTrue(outbox(EventTypes.TRANSACTIONS_CHANGED, rev.id.toString()).any { it.path("payload").path("cause").asString() == "TRANSFER_ANSWERED" })
        assertEquals(400, answer(UUID.randomUUID().toString(), """{"category":"NOPE"}""").status)
        assertEquals(404, answer(UUID.randomUUID().toString(), """{"skip":true}""").status)
    }

    @Test
    fun `AC-MA-5 and AC-MA-6 a credit card repayment is INTERNAL, purchases and card interest are spending`() {
        val cur = account()
        val card = account(AccountType.CREDIT_CARD)
        val cardS = import(
            card, "2027-04-01", "2027-04-30",
            listOf(
                Row("2027-04-03", "-500.00", "PLATA CARD ****5556 ALTEX ROMANIA 1511 IASI RO"),
                Row("2027-04-04", "-300.00", "PLATA CARD ****5556 DEDEMAN IASI"),
                Row("2027-04-16", "800.00", "RAMBURSARE CARD CREDIT"),
                Row("2027-04-30", "-146.00", "DOBANDA DEBITOARE"),
            ),
        )
        val curS = import(cur, "2027-04-01", "2027-04-30", listOf(Row("2027-04-15", "-800.00", "PLATA CARD CREDIT ${card.iban}"), Row("2027-04-20", "-20.00", "Kaufland")))
        awaitStatus(cardS, "CATEGORIZED")
        val curDone = awaitStatus(curS, "CATEGORIZED")
        eventually(message = "repayment paired") { txByText(cardS, "RAMBURSARE").path("transferStatus").asString() == "INTERNAL" }
        val repayment = txByText(curS, "PLATA CARD CREDIT")
        assertEquals("INTERNAL", repayment.path("transferStatus").asString())
        assertEquals(txByText(cardS, "RAMBURSARE").path("id").asString(), repayment.path("pairedTransactionId").asString())
        assertEquals("IBAN", dbString("SELECT match_method FROM transfer_pairs WHERE id = :id", UUID.fromString(repayment.path("transferPairId").asString())))

        val cardSummary = statement(cardS).path("summary")
        assertEquals("946.00", cardSummary.path("spendBase").asString(), "800 purchases + 146 interest, the repayment excluded")
        assertEquals("0.00", cardSummary.path("incomeBase").asString())
        assertEquals("20.00", curDone.path("summary").path("spendBase").asString())
        val interest = cardSummary.path("byCategory").toList().single { it.path("category").asString() == "CARD_INTEREST" }
        assertEquals("146.00", interest.path("amountBase").asString())
        assertEquals("CARD_INTEREST", txByText(cardS, "DOBANDA").path("category").asString())
        assertEquals("RULE", txByText(cardS, "DOBANDA").path("categorySource").asString())
    }

    @Test
    fun `AC-MA-7 a transfer to a counterparty labelled Landlord is EXTERNAL rent, also for future statements`() {
        val cur = account()
        val landlordIban = TestIbans.random()
        val token = ibanToken(landlordIban)
        val first = import(cur, "2027-05-01", "2027-05-31", listOf(Row("2027-05-05", "-2000.00", "TRANSFER CATRE ION LANDLORDESCU $landlordIban")))
        awaitStatus(first, "CATEGORIZED")
        val before = txByText(first, "TRANSFER CATRE")
        assertEquals(token, before.path("counterparty").path("token").asString())
        assertFalse(before.path("description").asString().contains("LANDLORDESCU"))

        mvc.perform(put("/api/counterparties/$token").contentType(MediaType.APPLICATION_JSON).content("""{"label":"Landlord","isOwnAccount":false}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.label").value("Landlord")).andExpect(jsonPath("$.isOwnAccount").value(false))
            .andExpect(jsonPath("$.transactionCount").value(1))
        val relabelled = txByText(first, "TRANSFER CATRE")
        assertEquals("EXTERNAL", relabelled.path("transferStatus").asString())
        assertEquals("RENT_MORTGAGE", relabelled.path("category").asString())
        assertEquals("Landlord", relabelled.path("counterparty").path("label").asString())
        assertTrue(outbox(EventTypes.TRANSACTIONS_CHANGED, cur.id.toString()).any { it.path("payload").path("cause").asString() == "COUNTERPARTY_LABELLED" })

        // Next month: decided by the label rule, without asking the LLM.
        chat.reset()
        val second = import(cur, "2027-06-01", "2027-06-30", listOf(Row("2027-06-05", "-2000.00", "TRANSFER CATRE ION LANDLORDESCU $landlordIban")))
        val done = awaitStatus(second, "CATEGORIZED")
        val t = txByText(second, "TRANSFER CATRE")
        assertEquals("EXTERNAL", t.path("transferStatus").asString())
        assertEquals("RENT_MORTGAGE", t.path("category").asString())
        assertEquals("RULE", t.path("categorySource").asString())
        assertEquals(0, chat.calls.get())
        assertEquals("2000.00", done.path("summary").path("spendBase").asString())

        val cp = body("/api/counterparties").toList().single { it.path("token").asString() == token }
        assertEquals(2, cp.path("transactionCount").asInt())
        assertEquals("2027-06-05", cp.path("lastSeen").asString())
        assertFalse(cp.path("sampleDescription").asString().contains(landlordIban))

        // Own account on → INTERNAL; off again → back to EXTERNAL rent.
        mvc.perform(put("/api/counterparties/$token").contentType(MediaType.APPLICATION_JSON).content("""{"label":"Landlord","isOwnAccount":true}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.isOwnAccount").value(true))
        assertEquals("INTERNAL", txByText(second, "TRANSFER CATRE").path("transferStatus").asString())
        mvc.perform(put("/api/counterparties/$token").contentType(MediaType.APPLICATION_JSON).content("""{"label":"Landlord","isOwnAccount":false}"""))
            .andExpect(status().isOk)
        assertEquals("EXTERNAL", txByText(second, "TRANSFER CATRE").path("transferStatus").asString())
        assertEquals("RENT_MORTGAGE", txByText(second, "TRANSFER CATRE").path("category").asString())
    }

    // ------------------------------------------------------------------------------------------ human in the loop

    @Test
    fun `AC-HIL-1 to AC-HIL-4 questions, answers, resume, learning`() {
        val acc = account()
        lowConfidenceFor("ALFA TRADE", "BETA SERV")
        val s = import(
            acc, "2027-07-01", "2027-07-31",
            listOf(
                Row("2027-07-02", "-30.00", "PLATA CARD ****1111 KAUFLAND 3412 IASI RO"),
                Row("2027-07-03", "-150.00", "PLATA CARD ****1111 ALFA TRADE SRL 12 IASI RO"),
                Row("2027-07-04", "-60.00", "POS BETA SERV IASI"),
            ),
        )
        // AC-HIL-1: low confidence → NEEDS_INPUT + InputRequired; no summary (nothing counts) until CATEGORIZED.
        val waiting = awaitStatus(s, "NEEDS_INPUT")
        assertTrue(waiting.path("summary").isNull)
        assertEquals(2, waiting.path("progress").path("questionsOpen").asInt())
        assertEquals("CATEGORIZE", waiting.path("lastCompletedStep").asString())
        val inputRequired = outbox(EventTypes.INPUT_REQUIRED, s.toString())
        assertEquals(1, inputRequired.size)
        assertEquals(2, inputRequired[0].path("payload").path("questionCount").asInt())
        assertFalse(chat.transactionActiveDuringCall.get(), "the LLM was called inside a DB transaction")

        // AC-HIL-3: the questions live in the DB; asking again (new browser session) returns the same ones.
        val qs = questions(s)
        assertEquals(qs, questions(s))
        assertEquals(2, qs.size)
        val alfa = qs.single { it.path("transaction").path("description").asString().contains("ALFA") }
        val beta = qs.single { it.path("transaction").path("description").asString().contains("BETA") }
        assertEquals("CATEGORY", alfa.path("kind").asString())
        assertEquals("Is ALFA TRADE a gym, a supplier, or a person?", alfa.path("questionText").asString())
        assertEquals(listOf("SPORT", "SHOPPING"), alfa.path("suggestions").toList().map { it.asString() }.take(2), "the LLM's suggestions first")
        assertEquals("-150.00", alfa.path("transaction").path("amount").asString())
        assertTrue(body("/api/questions").toList().any { it.path("id") == alfa.path("id") })
        // CATEGORY questions are answered with the statement's answers endpoint.
        assertEquals(400, answer(alfa.path("id").asString(), """{"category":"SPORT"}""").status)

        // Incomplete or invalid submissions are rejected without changing anything.
        assertEquals(400, submit(s, """{"questionId":"${alfa.path("id").asString()}","category":"SPORT"}""").status)
        assertEquals(400, submit(s, """{"questionId":"${alfa.path("id").asString()}","category":"SPORT","skip":true},{"questionId":"${beta.path("id").asString()}","skip":true}""").status)
        assertEquals("NEEDS_INPUT", statement(s).path("status").asString())

        // AC-HIL-2: two concurrent submits → exactly one 202, one 409; a later submit → 409 too.
        val answers = """{"questionId":"${alfa.path("id").asString()}","category":"SPORT","applyToFuture":true,"hint":"Alfa Trade is my gym, IBAN RO49AAAA1B31007593840000"},""" +
            """{"questionId":"${beta.path("id").asString()}","skip":true}"""
        val pool = Executors.newFixedThreadPool(2)
        val codes = try {
            pool.invokeAll(List(2) { Callable { submit(s, answers).status } }).map { it.get() }.sorted()
        } finally {
            pool.shutdown()
        }
        assertEquals(listOf(202, 409), codes)
        val late = submit(s, answers)
        assertEquals(409, late.status)
        assertTrue(late.contentAsString.contains("STATEMENT_NOT_IN_NEEDS_INPUT"))

        // The resume handler applies the answers and completes the statement.
        val done = awaitStatus(s, "CATEGORIZED")
        assertEquals("APPLY_ANSWERS", done.path("lastCompletedStep").asString())
        assertEquals("240.00", done.path("summary").path("spendBase").asString())
        val alfaTx = txByText(s, "ALFA")
        assertEquals("SPORT", alfaTx.path("category").asString())
        assertEquals("USER", alfaTx.path("categorySource").asString())
        assertEquals("OTHER", txByText(s, "BETA").path("category").asString(), "skip → OTHER")
        assertEquals(listOf("ANSWERED", "SKIPPED"), questions(s).map { it.path("status").asString() }.sorted())
        assertEquals(2, outbox(EventTypes.ANSWERS_SUBMITTED, s.toString()).single().path("payload").path("answerCount").asInt())
        assertTrue(outbox(EventTypes.TRANSACTIONS_CHANGED, s.toString()).any { it.path("payload").path("cause").asString() == "STATEMENT_CATEGORIZED" })

        // Learning: a USER alias, a sanitized + encrypted hint, a few-shot example.
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM merchant_aliases WHERE created_by = 'USER' AND pattern = 'ALFA TRADE SRL'").query(Long::class.java).single())
        val hints = jdbc.sql("SELECT text FROM merchant_hints").query(String::class.java).list().filterNotNull()
        assertTrue(hints.isNotEmpty() && hints.all { it.startsWith("v1:") }, "hints are encrypted at rest")
        assertTrue(hints.none { it.contains("RO49AAAA") })

        // AC-HIL-4: next month the same merchant (another store and city) is decided by the alias, no question.
        chat.reset()
        lowConfidenceFor("ALFA TRADE")
        val next = import(acc, "2027-08-01", "2027-08-31", listOf(Row("2027-08-03", "-150.00", "PLATA CARD ****1111 ALFA TRADE SRL 99 CLUJ RO")))
        awaitStatus(next, "CATEGORIZED")
        val t = txByText(next, "ALFA")
        assertEquals("SPORT", t.path("category").asString())
        assertEquals("RULE", t.path("categorySource").asString())
        assertEquals(0, questions(next).size)
        assertEquals(0, chat.calls.get())
    }

    // ------------------------------------------------------------------------------------------ LLM mode

    @Test
    fun `AC-LLM-1 categorization uses rules, embeddings and the LLM`() {
        assertTrue(mode.isEnabled())
        // A known merchant without an alias: only the embedding layer can match it (embeddings are filled lazily).
        jdbc.sql("INSERT INTO merchants (id, normalized_name, match_key, default_category) VALUES (:id, 'Zeta Bistro Central', 'ZETA BISTRO CENTRAL', 'RESTAURANTS')")
            .param("id", UUID.randomUUID()).update()
        chat.responder = { row -> if (row.description.contains("ORION")) ScriptedChatModel.Answer("SHOPPING", 0.95) else ScriptedChatModel.defaultAnswer(row) }
        val s = import(
            account(), "2027-12-01", "2027-12-31",
            listOf(
                Row("2027-12-02", "-31.00", "PLATA CARD ****1111 KAUFLAND 3412 IASI RO"),
                Row("2027-12-03", "-45.00", "POS ZETA BISTRO CENTRAL IASI"),
                Row("2027-12-04", "-99.00", "POS ORION BOOKS IASI"),
            ),
        )
        awaitStatus(s, "CATEGORIZED")
        assertEquals("RULE", txByText(s, "KAUFLAND").path("categorySource").asString())
        val zeta = txByText(s, "ZETA")
        assertEquals("EMBEDDING", zeta.path("categorySource").asString())
        assertEquals("RESTAURANTS", zeta.path("category").asString())
        val orion = txByText(s, "ORION")
        assertEquals("LLM", orion.path("categorySource").asString())
        assertEquals("SHOPPING", orion.path("category").asString())
        assertEquals(1, chat.calls.get())
    }

    @Test
    fun `AC-LLM-2 with the LLM off, or Ollama and embeddings down, statements still reach CATEGORIZED through questions`() {
        // LLM switched off: no call at all, generic question.
        mode.set(false)
        val off = import(account(), "2028-01-01", "2028-01-31", listOf(Row("2028-01-02", "-31.00", "Kaufland"), Row("2028-01-03", "-80.00", "POS OMEGA ATELIER IASI")))
        awaitStatus(off, "NEEDS_INPUT")
        val q = questions(off).single()
        assertEquals("Which category is \"Omega Atelier\"?", q.path("questionText").asString())
        assertEquals(0, chat.calls.get())
        assertEquals(202, submit(off, """{"questionId":"${q.path("id").asString()}","category":"SHOPPING"}""").status)
        awaitStatus(off, "CATEGORIZED")
        assertEquals("SHOPPING", txByText(off, "OMEGA").path("category").asString())

        // LLM on but Ollama (and the embedding model) unreachable: retried, then questions; never FAILED.
        mode.set(true)
        chat.down = true
        embeddings.down = true
        val down = import(account(), "2028-02-01", "2028-02-28", listOf(Row("2028-02-02", "-31.00", "Kaufland"), Row("2028-02-03", "-81.00", "POS SIGMA DECOR IASI")))
        awaitStatus(down, "NEEDS_INPUT")
        assertTrue(chat.calls.get() >= 2, "the LLM call is retried")
        val q2 = questions(down).single()
        assertTrue(q2.path("questionText").asString().startsWith("Which category is"))
        assertEquals("RULE", txByText(down, "Kaufland").path("categorySource").asString())
        assertEquals(202, submit(down, """{"questionId":"${q2.path("id").asString()}","skip":true}""").status)
        awaitStatus(down, "CATEGORIZED")
        assertEquals("OTHER", txByText(down, "SIGMA").path("category").asString())
    }

    @Test
    fun `AC-LLM-3 totals and transfers are identical with the LLM on or off`() {
        val target = account(AccountType.SAVINGS)
        val rows = { month: String ->
            listOf(
                Row("$month-02", "4100.00", "INCASARE SALARIU ACME SOFTWARE SRL"),
                Row("$month-03", "-31.50", "PLATA CARD ****1111 KAUFLAND 3412 IASI RO"),
                Row("$month-04", "-77.25", "POS KAPPA HOBBY IASI"),
                Row("$month-05", "-600.00", "TRANSFER CATRE ECONOMII ${target.iban}"),
                Row("$month-06", "-15.00", "COMISION ADMINISTRARE CONT"),
            )
        }
        val onS = import(account(), "2028-03-01", "2028-03-31", rows("2028-03"))
        val on = awaitStatus(onS, "CATEGORIZED")
        assertEquals("LLM", txByText(onS, "KAPPA").path("categorySource").asString())

        mode.set(false)
        val offS = import(account(), "2028-04-01", "2028-04-30", rows("2028-04"))
        awaitStatus(offS, "NEEDS_INPUT")
        val q = questions(offS).single()
        assertEquals(202, submit(offS, """{"questionId":"${q.path("id").asString()}","category":"SHOPPING"}""").status)
        val off = awaitStatus(offS, "CATEGORIZED")

        for (field in listOf("transactionCount", "spendBase", "incomeBase", "internalTransferCount")) {
            assertEquals(on.path("summary").path(field), off.path("summary").path(field), field)
        }
        assertEquals(
            transactions(onS).map { it.path("transferStatus").asString() },
            transactions(offS).map { it.path("transferStatus").asString() },
        )
    }

    // ------------------------------------------------------------------------------------------ sensitive data, API

    @Test
    fun `AC-SD-2 every prompt sent to the LLM contains only sanitized descriptions`() {
        val acc = account()
        val raw = listOf(
            Row("2028-05-02", "-11.00", "PLATA CARD 4111 1111 1111 1111 OBSCURE SHOP 1 IASI RO"),
            Row("2028-05-03", "-22.00", "TRANSFER CATRE MARIA EXEMPLU RO49AAAA1B31007593840000 ceva"),
            Row("2028-05-04", "-33.00", "Factura trimisa la ion.popescu@example.com, tel 0722 123 456"),
            Row("2028-05-05", "-44.00", "Plata impozit CNP 1960523460032 catre PRIMARIE SECTOR"),
        )
        val s = import(acc, "2028-05-01", "2028-05-31", raw)
        awaitStatus(s, "CATEGORIZED")
        assertTrue(chat.prompts.isNotEmpty())
        val sanitized = transactions(s).map { it.path("description").asString() }.toSet()
        val sent = chat.prompts.flatMap { ScriptedChatModel.parseRows(it) }.map { it.description }
        assertEquals(sanitized, sent.toSet(), "the prompt rows are exactly the stored (sanitized) descriptions")
        val forbidden = listOf(
            "4111 1111 1111 1111", "4111111111111111", "RO49AAAA1B31007593840000", "MARIA", "EXEMPLU",
            "ion.popescu@example.com", "0722 123 456", "1960523460032",
        )
        for (prompt in chat.prompts) forbidden.forEach { assertFalse(prompt.contains(it), "raw PII in a prompt: $it") }
        assertFalse(chat.transactionActiveDuringCall.get())
        assertFalse(embeddings.transactionActiveDuringCall.get())

        // Descriptions are ciphertext at rest.
        val stored = jdbc.sql("SELECT description FROM transactions WHERE statement_id = :s").param("s", s).query(String::class.java).list().filterNotNull()
        assertTrue(stored.all { it.startsWith("v1:") })
    }

    @Test
    fun `transactions API pages, filters, sorts and validates`() {
        val acc = account()
        val s = import(acc, "2028-06-01", "2028-06-30", (1..7).map { Row("2028-06-%02d".format(it), "-%d.00".format(it * 10), "PLATA CARD ****1111 KAUFLAND 3412 IASI RO") })
        awaitStatus(s, "CATEGORIZED")
        val page = body("/api/transactions?accountId=${acc.id}&size=3&page=1&sort=amount,asc")
        assertEquals(7, page.path("totalItems").asInt())
        assertEquals(3, page.path("totalPages").asInt())
        assertEquals(1, page.path("page").asInt())
        assertEquals(listOf("-40.00", "-30.00", "-20.00"), page.path("items").toList().map { it.path("amount").asString() })
        val ranged = body("/api/transactions?accountId=${acc.id}&from=2028-06-03&to=2028-06-04&category=GROCERIES")
        assertEquals(2, ranged.path("totalItems").asInt())
        val one = ranged.path("items").get(0)
        assertEquals("2028-06-04", one.path("bookingDate").asString(), "default sort is bookingDate,desc")
        assertEquals("NONE", one.path("transferStatus").asString())
        assertEquals(one, body("/api/transactions/${one.path("id").asString()}"))
        mvc.perform(get("/api/transactions/${UUID.randomUUID()}")).andExpect(status().isNotFound)
        mvc.perform(get("/api/transactions?size=0")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/transactions?sort=description,asc")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/transactions?category=NOPE")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/questions?status=WHATEVER")).andExpect(status().isBadRequest)
    }

    @Test
    fun `a categorization result is discarded when the statement changed during the LLM call`() {
        val acc = account()
        chat.onCall = {
            // Simulates a concurrent change while the (slow) LLM runs: the statement is no longer CATEGORIZING.
            jdbc.sql("UPDATE statements SET status = 'FAILED' WHERE status = 'CATEGORIZING' AND account_id = :a").param("a", acc.id).update()
        }
        val id = import(acc, "2028-07-01", "2028-07-31", listOf(Row("2028-07-02", "-12.00", "POS LAMBDA STUDIO IASI")))
        eventually(message = "LLM called") { chat.calls.get() > 0 }
        Thread.sleep(1500)
        assertEquals("FAILED", statement(id).path("status").asString())
        assertTrue(transactions(id).single().path("category").isNull, "the stale LLM result was not saved")
    }
}
