package dev.spendly.it

import dev.spendly.account.AccountCreate
import dev.spendly.account.AccountService
import dev.spendly.account.AccountType
import dev.spendly.crypto.AesGcmCipher
import dev.spendly.events.EventEnvelope
import dev.spendly.events.EventTypes
import dev.spendly.events.ImportedTransaction
import dev.spendly.events.ParserInfo
import dev.spendly.events.Producers
import dev.spendly.events.StatementUploadedPayload
import dev.spendly.events.TransactionsImportedPayload
import dev.spendly.fx.FxService
import dev.spendly.importing.ImportService
import dev.spendly.importing.TransactionRepository
import dev.spendly.outbox.OutboxRelay
import dev.spendly.outbox.OutboxService
import dev.spendly.outbox.ProcessedEventsRepository
import dev.spendly.parsing.TxHashes
import dev.spendly.statement.FileType
import dev.spendly.statement.ProcessingStep
import dev.spendly.statement.StatementRepository
import dev.spendly.statement.StatementStateMachine
import dev.spendly.statement.StatementStatus
import dev.spendly.statement.StatusConflictException
import dev.spendly.support.IntegrationTest
import dev.spendly.support.TestIbans
import dev.spendly.support.eventually
import io.mockk.every
import io.mockk.spyk
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ConsistencyIT : IntegrationTest() {
    companion object {
        /** After import the categorizer (Phase 3) moves the statement on asynchronously. */
        val PARSED_OR_LATER = setOf(StatementStatus.PARSED, StatementStatus.CATEGORIZING, StatementStatus.NEEDS_INPUT, StatementStatus.CATEGORIZED)
    }

    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var statements: StatementRepository
    @Autowired lateinit var stateMachine: StatementStateMachine
    @Autowired lateinit var outbox: OutboxService
    @Autowired lateinit var relay: OutboxRelay
    @Autowired lateinit var importService: ImportService
    @Autowired lateinit var processed: ProcessedEventsRepository
    @Autowired lateinit var transactions: TransactionRepository
    @Autowired lateinit var fx: FxService
    @Autowired lateinit var cipher: AesGcmCipher
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var namedJdbc: NamedParameterJdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var domainEvents: dev.spendly.outbox.DomainEvents

    private fun account(currency: String = "RON"): UUID = accounts.create(
        AccountCreate("Acc ${UUID.randomUUID()}", "Generic Bank", AccountType.CURRENT, currency, TestIbans.random(), null, LocalDate.of(2026, 1, 1)),
    ).id

    /** A statement row in UPLOADED, inserted directly (no file, no event), for handler-level tests. */
    private fun uploadedStatement(accountId: UUID, fileType: FileType = FileType.PDF): UUID {
        val id = UUID.randomUUID()
        tx.executeWithoutResult { statements.insert(id, accountId, fileType, UUID.randomUUID().toString().replace("-", "").repeat(2), "test-corr") }
        return id
    }

    private fun importedEvent(
        statementId: UUID,
        accountId: UUID,
        rows: List<Triple<String, String, String>> = listOf(
            Triple("2026-08-02", "-187.43", "PLATA CARD ****1111 KAUFLAND 3412 IASI RO"),
            Triple("2026-08-03", "-12.50", "PLATA CARD ****1111 STARBUCKS PALAS IASI"),
            Triple("2026-08-03", "-12.50", "PLATA CARD ****1111 STARBUCKS PALAS IASI"),
        ),
        currency: String = "RON",
        producer: String = Producers.PDF_PARSER,
    ): EventEnvelope<TransactionsImportedPayload> {
        val seen = HashMap<Triple<String, String, String>, Int>()
        val txs = rows.mapIndexed { i, r ->
            val occ = seen.merge(r, 1, Int::plus)!! - 1
            ImportedTransaction(
                TxHashes.hash(accountId, LocalDate.parse(r.first), r.second, currency, r.third, occ),
                i, r.first, null, r.second, currency, r.third, null, null,
            )
        }
        val payload = TransactionsImportedPayload(
            statementId.toString(), accountId.toString(), ParserInfo("python-pdf-pdfplumber", "0.1.0"),
            "2026-08-01", "2026-08-31", currency, txs.size, txs,
        )
        return EventEnvelope.create(EventTypes.TRANSACTIONS_IMPORTED, "test-corr", payload).copy(producer = producer)
    }

    private fun count(sql: String, id: UUID): Long = jdbc.sql(sql).param("id", id).query(Long::class.java).single()

    private fun producer() = KafkaProducer<String, String>(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ),
    )

    /** Reads a whole topic from the beginning (manual assignment, no consumer group, so no rebalance delay). */
    private fun readTopic(topic: String, waitMs: Long = 2000): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> {
        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).use { consumer ->
            val partitions = consumer.partitionsFor(topic).map { org.apache.kafka.common.TopicPartition(topic, it.partition()) }
            consumer.assign(partitions)
            consumer.seekToBeginning(partitions)
            val end = consumer.endOffsets(partitions)
            val out = ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline && partitions.any { consumer.position(it) < end.getValue(it) }) {
                consumer.poll(Duration.ofMillis(200)).forEach { out += it }
            }
            return out
        }
    }

    // ------------------------------------------------------------------------------------------ AC-TX-1

    @Test
    fun `AC-TX-1 a committed outbox row is published exactly once even if the app dies before publishing`() {
        val accountId = account()
        val aggregate = UUID.randomUUID()
        val envelope = EventEnvelope.create(
            EventTypes.STATEMENT_UPLOADED, "tx1-corr",
            StatementUploadedPayload(aggregate.toString(), accountId.toString(), "Generic Bank", "RON", "PDF", "$aggregate.pdf", "a".repeat(64)),
        )
        relay.enabled.set(false) // "crash": the relay never runs after this commit
        try {
            tx.executeWithoutResult { outbox.append(envelope, "spendly.test-outbox", accountId.toString(), aggregate) }
            Thread.sleep(600)
            assertEquals(1L, count("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND published_at IS NULL", aggregate))
            assertTrue(readTopic("spendly.test-outbox", 1500).none { it.headers().lastHeader("eventId")?.value()?.let(::String) == envelope.eventId })
        } finally {
            relay.enabled.set(true) // "restart"
        }
        relay.publishPending()
        relay.publishPending() // a second run must not re-send
        eventually(5000) { count("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND published_at IS NOT NULL", aggregate) == 1L }

        val published = readTopic("spendly.test-outbox").filter { String(it.headers().lastHeader("eventId").value()) == envelope.eventId }
        assertEquals(1, published.size)
        val record = published.single()
        assertEquals(accountId.toString(), record.key())
        assertEquals("StatementUploaded", String(record.headers().lastHeader("eventType").value()))
        assertEquals("1", String(record.headers().lastHeader("schemaVersion").value()))
        assertEquals("tx1-corr", String(record.headers().lastHeader("correlationId").value()))
        assertEquals(envelope.eventId, json.readTree(record.value()).path("eventId").asString())
    }

    @Test
    fun `AC-TX-1 a rolled-back transaction leaves no outbox row`() {
        val accountId = account()
        val statementId = UUID.randomUUID()
        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                statements.insert(statementId, accountId, FileType.CSV, "b".repeat(64), "rollback")
                outbox.append(
                    EventEnvelope.create(EventTypes.STATEMENT_UPLOADED, "rollback",
                        StatementUploadedPayload(statementId.toString(), accountId.toString(), "B", "RON", "CSV", "$statementId.csv", "b".repeat(64))),
                    "spendly.test-outbox", accountId.toString(), statementId,
                )
                throw IllegalStateException("boom")
            }
        }
        assertEquals(0L, count("SELECT count(*) FROM outbox WHERE aggregate_id = :id", statementId))
        assertNull(statements.findById(statementId))
    }

    @Test
    fun `outbox refuses events that violate the contract`() {
        val bad = EventEnvelope.create(EventTypes.STATEMENT_UPLOADED, "x",
            StatementUploadedPayload("not-a-uuid", "x", "B", "RON", "CSV", "../x.csv", "short"))
        assertThrows<IllegalStateException> { tx.executeWithoutResult { outbox.append(bad, "spendly.test-outbox", "k", UUID.randomUUID()) } }
    }

    // ------------------------------------------------------------------------------------------ AC-TX-2

    @Test
    fun `AC-TX-2 delivering the same TransactionsImported twice changes nothing`() {
        val accountId = account()
        val statementId = uploadedStatement(accountId)
        val event = importedEvent(statementId, accountId)

        importService.importTransactions(event)
        val snapshot = { jdbc.sql("SELECT id, tx_hash, amount FROM transactions WHERE statement_id = :id ORDER BY sequence").param("id", statementId).query().listOfRows() }
        val before = snapshot()
        val statusBefore = statements.findById(statementId)!!
        assertEquals(StatementStatus.PARSED, statusBefore.status)
        assertEquals(3, before.size)

        importService.importTransactions(event) // duplicate delivery
        // The same content with a NEW eventId (e.g. the pdf-parser re-publishing) is stopped by the status guard
        importService.importTransactions(event.copy(eventId = UUID.randomUUID().toString()))

        assertEquals(before, snapshot())
        // The statement may meanwhile move on (the categorizer runs asynchronously), but never because of the duplicates:
        // exactly one StatementParsed was written for it.
        assertTrue(statements.findById(statementId)!!.status in PARSED_OR_LATER)
        assertEquals(1L, count("SELECT count(*) FROM processed_events WHERE event_id = :id", UUID.fromString(event.eventId)))
        assertEquals(1L, count("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND event_type = 'StatementParsed'", statementId))
    }

    @Test
    fun `AC-TX-2 duplicate Kafka delivery of an event from the pdf-parser is harmless`() {
        val accountId = account()
        val statementId = uploadedStatement(accountId)
        val event = importedEvent(statementId, accountId)
        val value = json.writeValueAsString(event)
        producer().use { p ->
            repeat(2) {
                val r = ProducerRecord<String, String>("spendly.transactions-imported", accountId.toString(), value)
                r.headers().add(RecordHeader("eventId", event.eventId.toByteArray()))
                p.send(r).get()
            }
        }
        eventually { statements.findById(statementId)!!.status in PARSED_OR_LATER }
        Thread.sleep(1500)
        assertEquals(3L, transactions.countByStatementId(statementId))
        assertEquals(LocalDate.of(2026, 8, 1), statements.findById(statementId)!!.periodStart)
    }

    @Test
    fun `an event for an unknown statement is skipped without crashing or looping`() {
        val accountId = account()
        val ghost = UUID.randomUUID()
        val event = importedEvent(ghost, accountId)
        importService.importTransactions(event)
        assertEquals(0L, transactions.countByStatementId(ghost))
        assertEquals(1L, count("SELECT count(*) FROM processed_events WHERE event_id = :id", UUID.fromString(event.eventId)))
    }

    // ------------------------------------------------------------------------------------------ AC-TX-4

    @Test
    fun `AC-TX-4 an exception inside the import transaction leaves the DB unchanged`() {
        val accountId = account()
        val statementId = uploadedStatement(accountId)
        val event = importedEvent(statementId, accountId)
        val failing = spyk(stateMachine)
        every { failing.transition(any(), any(), any(), any(), any()) } throws IllegalStateException("forced failure after inserting transactions")
        val faulty = ImportService(processed, statements, failing, fx, cipher, namedJdbc, tx, domainEvents)

        val outboxBefore = jdbc.sql("SELECT count(*) FROM outbox").query(Long::class.java).single()
        assertThrows<IllegalStateException> { faulty.importTransactions(event) }

        assertEquals(0L, transactions.countByStatementId(statementId))
        val st = statements.findById(statementId)!!
        assertEquals(StatementStatus.UPLOADED, st.status)
        assertNull(st.periodStart)
        assertEquals(ProcessingStep.UPLOAD, st.lastCompletedStep)
        assertFalse(processed.exists(UUID.fromString(event.eventId), ImportService.CONSUMER))
        assertEquals(outboxBefore, jdbc.sql("SELECT count(*) FROM outbox").query(Long::class.java).single())

        // The real handler then succeeds with the same event (nothing half-applied blocks it)
        importService.importTransactions(event)
        assertEquals(StatementStatus.PARSED, statements.findById(statementId)!!.status)
    }

    // ------------------------------------------------------------------------------------------ status guard

    @Test
    fun `the SQL status guard rejects a second transition, also under concurrency`() {
        val accountId = account()
        val statementId = uploadedStatement(accountId)
        tx.executeWithoutResult { stateMachine.transition(statementId, StatementStatus.UPLOADED, StatementStatus.PARSED, ProcessingStep.PARSE) }
        assertThrows<StatusConflictException> {
            tx.executeWithoutResult { stateMachine.transition(statementId, StatementStatus.UPLOADED, StatementStatus.PARSED, ProcessingStep.PARSE) }
        }
        assertThrows<IllegalStateException> { // not an allowed edge at all
            tx.executeWithoutResult { stateMachine.transition(statementId, StatementStatus.PARSED, StatementStatus.UPLOADED) }
        }

        val raced = uploadedStatement(accountId)
        val pool = Executors.newFixedThreadPool(8)
        val results = pool.invokeAll((1..8).map {
            Callable {
                runCatching {
                    tx.executeWithoutResult { stateMachine.transition(raced, StatementStatus.UPLOADED, StatementStatus.PARSED, ProcessingStep.PARSE) }
                }.isSuccess
            }
        }).map { it.get() }
        pool.shutdown()
        assertEquals(1, results.count { it }, "exactly one concurrent transition wins")
    }

    // ------------------------------------------------------------------------------------------ AC-SD-7

    @Test
    fun `AC-SD-7 description is ciphertext in raw SQL but plaintext through the repository`() {
        val accountId = account()
        val statementId = uploadedStatement(accountId)
        importService.importTransactions(importedEvent(statementId, accountId))
        val raw = jdbc.sql("SELECT description FROM transactions WHERE statement_id = :id ORDER BY sequence").param("id", statementId)
            .query(String::class.java).list()
        assertEquals(3, raw.size)
        raw.forEach {
            assertTrue(it!!.startsWith("v1:"))
            assertFalse(it.contains("KAUFLAND") || it.contains("STARBUCKS"))
        }
        assertTrue(raw[1] != raw[2], "random IV: identical plaintexts encrypt differently")
        val entities = transactions.findByStatementIdOrderBySequence(statementId)
        assertEquals("PLATA CARD ****1111 KAUFLAND 3412 IASI RO", entities[0].description)
        assertEquals("PLATA CARD ****1111 STARBUCKS PALAS IASI", entities[2].description)
    }

    // ------------------------------------------------------------------------------------------ DLQ

    @Test
    fun `invalid JSON goes straight to the DLQ with a reason header and no retries`() {
        producer().use { p ->
            p.send(ProducerRecord("spendly.transactions-imported", "k-invalid-json", "{not json")).get()
        }
        eventually(20_000, message = "message not dead-lettered") {
            readTopic("spendly.transactions-imported.dlq", 1500).any { it.key() == "k-invalid-json" && String(it.headers().lastHeader("dlqReason").value()) == "INVALID_JSON" }
        }
        val dlq = readTopic("spendly.transactions-imported.dlq", 1000).first { it.key() == "k-invalid-json" }
        assertEquals("{not json", dlq.value())
        assertEquals("spendly-backend", String(dlq.headers().lastHeader("dlqSource").value()))
        assertNotNull(dlq.headers().lastHeader("dlqFailedAt"))
    }

    @Test
    fun `a schema-invalid event with a known statementId is dead-lettered and the DLQ consumer fails the statement`() {
        val accountId = account()
        val statementId = uploadedStatement(accountId)
        val event = importedEvent(statementId, accountId)
        val tree = json.valueToTree<tools.jackson.databind.node.ObjectNode>(event)
        (tree.path("payload") as tools.jackson.databind.node.ObjectNode).put("openingBalance", "5000.00")
        producer().use { it.send(ProducerRecord("spendly.transactions-imported", accountId.toString(), json.writeValueAsString(tree))).get() }

        eventually(30_000, message = "statement not FAILED by the DLQ consumer") { statements.findById(statementId)!!.status == StatementStatus.FAILED }
        val error = statements.findById(statementId)!!.error!!
        assertEquals("DLQ", error.code)
        assertTrue(error.message.contains("SCHEMA_VALIDATION"))
        assertEquals(0L, transactions.countByStatementId(statementId))
    }

    @Test
    fun `transient failures are retried with backoff, then dead-lettered as RETRIES_EXHAUSTED and the statement FAILS`() {
        // Schema-valid, but periodEnd < periodStart violates a DB CHECK inside the import transaction:
        // an error the handler cannot classify, so it is retried (3 retries) and then dead-lettered.
        val accountId = account()
        val statementId = uploadedStatement(accountId)
        val event = importedEvent(statementId, accountId)
        val broken = event.copy(payload = event.payload.copy(periodStart = "2026-08-31", periodEnd = "2026-08-01"))
        val value = json.writeValueAsString(broken)
        val started = System.currentTimeMillis()
        producer().use { it.send(ProducerRecord("spendly.transactions-imported", accountId.toString(), value)).get() }

        eventually(30_000, message = "not dead-lettered") {
            readTopic("spendly.transactions-imported.dlq", 1500).any {
                it.value() == value && String(it.headers().lastHeader("dlqReason").value()) == "RETRIES_EXHAUSTED"
            }
        }
        // test backoff is 100 ms x2: at least 100 + 200 + 400 ms passed before giving up
        assertTrue(System.currentTimeMillis() - started >= 700)
        eventually(20_000) { statements.findById(statementId)!!.status == StatementStatus.FAILED }
        assertEquals(0L, transactions.countByStatementId(statementId))
        assertTrue(statements.findById(statementId)!!.error!!.message.contains("RETRIES_EXHAUSTED"))
    }

    // ------------------------------------------------------------------------------------------ FX

    @Test
    fun `foreign currency uses the BNR rate of the booking date or the last earlier one, missing rate fails then retry resumes`() {
        val accountId = account("EUR")
        val statementId = uploadedStatement(accountId)
        val rows = listOf(Triple("2026-08-08", "-20.00", "COFFEE SATURDAY"), Triple("2026-08-10", "-10.00", "LUNCH MONDAY"))
        val xml = """
            <DataSet xmlns="http://www.bnr.ro/xsd"><Body><OrigCurrency>RON</OrigCurrency>
              <Cube date="2026-08-07"><Rate currency="EUR">4.9750</Rate></Cube>
            </Body></DataSet>
        """.trimIndent()
        mvc.perform(multipart("/api/fx-rates/import").file(MockMultipartFile("file", "rates.xml", "application/xml", xml.toByteArray())))
            .andExpect(status().isOk).andExpect(jsonPath("$.imported").value(1))

        importService.importTransactions(importedEvent(statementId, accountId, rows, currency = "EUR"))
        // 2026-08-10 has no rate within the lookback? It does (08-07 is 3 days earlier) → both converted.
        val st = statements.findById(statementId)!!
        assertTrue(st.status in PARSED_OR_LATER)
        val txs = transactions.findByStatementIdOrderBySequence(statementId)
        assertEquals(java.math.BigDecimal("-99.50"), txs[0].amountBase)
        assertEquals(java.math.BigDecimal("-49.75"), txs[1].amountBase)

        // A USD row with no rate at all → FAILED (FX_RATE_MISSING, step PARSE, transactions kept)
        val usdAccount = account("USD")
        val usdStatement = uploadedStatement(usdAccount)
        importService.importTransactions(importedEvent(usdStatement, usdAccount, listOf(Triple("2026-08-10", "-5.00", "APP STORE")), currency = "USD"))
        val failed = statements.findById(usdStatement)!!
        assertEquals(StatementStatus.FAILED, failed.status)
        assertEquals("FX_RATE_MISSING", failed.error!!.code)
        assertEquals(ProcessingStep.PARSE, failed.lastCompletedStep)
        assertNull(transactions.findByStatementIdOrderBySequence(usdStatement).single().amountBase)

        mvc.perform(post("/api/statements/$usdStatement/retry")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("FX_RATE_MISSING"))
        val usdXml = """<DataSet xmlns="http://www.bnr.ro/xsd"><Body><Cube date="2026-08-10"><Rate currency="USD">4.2801</Rate></Cube></Body></DataSet>"""
        mvc.perform(multipart("/api/fx-rates/import").file(MockMultipartFile("file", "usd.xml", "application/xml", usdXml.toByteArray())))
            .andExpect(status().isOk)
        mvc.perform(post("/api/statements/$usdStatement/retry")).andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("PARSED"))
        assertEquals(java.math.BigDecimal("-21.40"), transactions.findByStatementIdOrderBySequence(usdStatement).single().amountBase)
        assertNull(statements.findById(usdStatement)!!.error)
    }
}
