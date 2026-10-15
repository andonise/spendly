package dev.spendly.it

import dev.spendly.importing.TransactionRepository
import dev.spendly.statement.StatementEventsService
import dev.spendly.support.IntegrationTest
import dev.spendly.support.TestIbans
import dev.spendly.support.TestPaths
import dev.spendly.support.eventually
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** Accounts API + upload → Kafka → CSV parser → import → PARSED, end to end on Testcontainers. */
class IngestionIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transactions: TransactionRepository
    @Autowired lateinit var sse: StatementEventsService
    @LocalServerPort var port: Int = 0

    private fun body(result: org.springframework.test.web.servlet.MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun createAccount(iban: String = TestIbans.random(), label: String = "Test ${UUID.randomUUID()}", currency: String = "RON"): JsonNode {
        val res = mvc.perform(
            post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                """{"label":"$label","bank":"Generic Bank","type":"CURRENT","currency":"$currency","iban":"$iban","activeFrom":"2026-01-01"}""",
            ),
        ).andExpect(status().isCreated).andReturn()
        return body(res)
    }

    private fun upload(accountId: String, name: String, bytes: ByteArray, correlationId: String? = null) =
        mvc.perform(
            multipart("/api/statements").file(MockMultipartFile("file", name, "text/csv", bytes)).param("accountId", accountId)
                .apply { if (correlationId != null) header("X-Correlation-Id", correlationId) },
        ).andReturn()

    private fun statement(id: String): JsonNode = body(mvc.perform(get("/api/statements/$id")).andExpect(status().isOk).andReturn())

    private fun awaitStatus(id: String, expected: String, timeoutMs: Long = 60_000): JsonNode {
        var last: JsonNode? = null
        eventually(timeoutMs, message = "statement $id did not reach $expected") {
            last = statement(id); last!!.path("status").asString() == expected
        }
        return last!!
    }

    @Test
    fun `accounts CRUD with IBAN stored only as HMAC plus last 4`() {
        val iban = TestIbans.withCheckDigits("RO", "SPND0000" + (10000000..99999999).random())
        val spaced = iban.chunked(4).joinToString(" ")
        val created = createAccount(spaced)
        val id = created.path("id").asString()
        assertEquals("RO**…" + iban.takeLast(4), created.path("ibanMasked").asString())
        assertTrue(created.path("iban").isMissingNode, "IBAN is write-only")

        val row = jdbc.sql("SELECT * FROM accounts WHERE id = :id").param("id", UUID.fromString(id)).query().singleRow()
        assertFalse(row.values.any { it?.toString()?.contains(iban.substring(4, 12)) == true }, "IBAN digits stored in clear")
        assertEquals(64, (row["iban_hash"] as String).length)

        // Duplicate IBAN (compact form this time) → 409
        mvc.perform(
            post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                """{"label":"Dup","bank":"B","type":"CURRENT","currency":"RON","iban":"$iban","activeFrom":"2026-01-01"}""",
            ),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_IBAN"))
        // Invalid IBAN → 400 ProblemDetail
        mvc.perform(
            post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(
                """{"label":"Bad","bank":"B","type":"CURRENT","currency":"RON","iban":"RO00AAAA1B31007593840000","activeFrom":"2026-01-01"}""",
            ),
        ).andExpect(status().isBadRequest).andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
            .andExpect(jsonPath("$.code").value("INVALID_IBAN"))
        // Missing fields → 400 with field errors
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("""{"label":""}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))

        mvc.perform(patch("/api/accounts/$id").contentType(MediaType.APPLICATION_JSON).content("""{"label":"Renamed","cardLast4":"1111","closedAt":"2026-12-31"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.label").value("Renamed")).andExpect(jsonPath("$.cardLast4").value("1111"))
            .andExpect(jsonPath("$.closedAt").value("2026-12-31"))
        mvc.perform(patch("/api/accounts/$id").contentType(MediaType.APPLICATION_JSON).content("""{"closedAt":null}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.closedAt").isEmpty).andExpect(jsonPath("$.cardLast4").value("1111"))
        mvc.perform(get("/api/accounts")).andExpect(status().isOk)
        mvc.perform(get("/api/accounts/${UUID.randomUUID()}")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))

        // Delete with statements → 409; without → 204
        upload(id, "x.csv", Files.readAllBytes(TestPaths.sample("revolut-2026-08.csv")))
        mvc.perform(delete("/api/accounts/$id")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT_HAS_STATEMENTS"))
        val empty = createAccount().path("id").asString()
        mvc.perform(delete("/api/accounts/$empty")).andExpect(status().isNoContent)
    }

    @Test
    fun `end to end - generic CSV reaches PARSED with 18 transactions, re-upload is a duplicate (AC-SD-3, AC-SD-6)`() {
        val accountId = createAccount().path("id").asString()
        val bytes = Files.readAllBytes(TestPaths.sample("generic-bank-2026-08.csv"))

        val first = upload(accountId, "generic-bank-2026-08.csv", bytes, correlationId = "upl-e2e-1")
        assertEquals(202, first.response.status)
        assertEquals("upl-e2e-1", first.response.getHeader("X-Correlation-Id"))
        val accepted = body(first)
        val id = accepted.path("statementId").asString()
        assertEquals("/api/statements/$id", first.response.getHeader("Location"))
        assertFalse(accepted.path("duplicate").asBoolean())

        val parsed = awaitStatus(id, "PARSED")
        assertEquals(18, parsed.path("progress").path("transactionsImported").asInt())
        assertEquals("PARSE", parsed.path("lastCompletedStep").asString())
        assertEquals("2026-08-01", parsed.path("periodStart").asString())
        assertEquals("2026-08-31", parsed.path("periodEnd").asString())
        assertTrue(parsed.path("summary").isNull)
        assertTrue(parsed.path("error").isNull)

        // AC-SD-3: the original is deleted after a successful parse (retention PT0S)
        eventually(10_000, message = "temp file still present") { !Files.exists(uploadDir.resolve("$id.csv")) }

        // Same file again → 200 duplicate, still 18 transactions, no new statement
        val second = upload(accountId, "again.csv", bytes)
        assertEquals(200, second.response.status)
        assertTrue(body(second).path("duplicate").asBoolean())
        assertEquals(id, body(second).path("statementId").asString())
        Thread.sleep(1500)
        assertEquals(18L, transactions.countByAccountId(UUID.fromString(accountId)))
        val list = body(mvc.perform(get("/api/statements").param("accountId", accountId)).andReturn())
        assertEquals(1, list.size())

        // amounts and base amounts (RON → rate 1); descriptions sanitized
        val rows = transactions.findByStatementIdOrderBySequence(UUID.fromString(id))
        assertEquals(BigDecimal("-6393.56"), rows.filter { it.amount.signum() < 0 }.sumOf { it.amount })
        assertTrue(rows.all { it.amountBase == it.amount && it.fxRate!!.compareTo(BigDecimal.ONE) == 0 })
        assertEquals("PLATA CARD ****1111 KAUFLAND 3412 IASI RO", rows[1].description)

        // AC-SD-6: no balance column anywhere, no balance value in statements/transactions/outbox
        val balanceColumns = jdbc.sql(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND (column_name ILIKE '%balance%' OR column_name ILIKE '%sold%')",
        ).query(Long::class.java).single()
        assertEquals(0L, balanceColumns)
        val amounts = jdbc.sql("SELECT amount FROM transactions WHERE statement_id = :id").param("id", UUID.fromString(id))
            .query(BigDecimal::class.java).list()
        for (balance in listOf("5000.00", "7256.44", "13500.00", "9173.73")) {
            assertFalse(amounts.any { it!!.compareTo(BigDecimal(balance)) == 0 }, "balance $balance persisted as a transaction")
        }
        val outboxText = jdbc.sql("SELECT string_agg(payload::text, ' ') FROM outbox WHERE aggregate_id = :id")
            .param("id", UUID.fromString(id)).query(String::class.java).single()
        assertFalse(outboxText.contains("7256.44") || outboxText.contains("5000.00") || outboxText.contains("alance"))
        assertFalse(outboxText.contains("4111 1111") || outboxText.contains("RO38TEST") || outboxText.contains("POPESCU"))
        // Every outbox row was published
        eventually(10_000) {
            jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND published_at IS NULL")
                .param("id", UUID.fromString(id)).query(Long::class.java).single() == 0L
        }
    }

    @Test
    fun `revolut CSV reaches PARSED with 8 transactions including the fee row`() {
        val accountId = createAccount().path("id").asString()
        val res = upload(accountId, "revolut.csv", Files.readAllBytes(TestPaths.sample("revolut-2026-08.csv")))
        val id = body(res).path("statementId").asString()
        val parsed = awaitStatus(id, "PARSED")
        assertEquals(8, parsed.path("progress").path("transactionsImported").asInt())
        assertEquals("2026-08-11", parsed.path("periodStart").asString())
        assertEquals("2026-08-28", parsed.path("periodEnd").asString())
    }

    @Test
    fun `a balance mismatch moves the statement to FAILED, keeps the file, and retry re-runs the parse`() {
        val accountId = createAccount().path("id").asString()
        val tampered = Files.readString(TestPaths.sample("generic-bank-2026-08.csv")).replace("LIDL DISCOUNT IASI;96,40", "LIDL DISCOUNT IASI;96,41")
        val id = body(upload(accountId, "bad.csv", tampered.toByteArray())).path("statementId").asString()
        val failed = awaitStatus(id, "FAILED")
        assertEquals("BALANCE_MISMATCH", failed.path("error").path("code").asString())
        assertFalse(failed.path("error").path("retryable").asBoolean())
        assertEquals("UPLOAD", failed.path("lastCompletedStep").asString())
        assertTrue(Files.exists(uploadDir.resolve("$id.csv")), "original kept for retry")

        // Retry → UPLOADED again → parsed again → FAILED again (same file)
        mvc.perform(post("/api/statements/$id/retry")).andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("UPLOADED"))
        awaitStatus(id, "FAILED")
        // Retry of a non-FAILED statement → 409
        val okId = body(upload(accountId, "ok.csv", Files.readAllBytes(TestPaths.sample("revolut-2026-08.csv")))).path("statementId").asString()
        awaitStatus(okId, "PARSED")
        mvc.perform(post("/api/statements/$okId/retry")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("STATEMENT_NOT_FAILED"))
    }

    @Test
    fun `XLSX is a clear UNSUPPORTED_LAYOUT failure, PDF is left to the pdf-parser, bad files are rejected`() {
        val accountId = createAccount().path("id").asString()
        val xlsx = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64)
        val xId = body(upload(accountId, "s.xlsx", xlsx)).path("statementId").asString()
        assertEquals("UNSUPPORTED_LAYOUT", awaitStatus(xId, "FAILED").path("error").path("code").asString())

        val pdfId = body(upload(accountId, "s.pdf", Files.readAllBytes(TestPaths.sample("credit-card-2026-08.pdf")))).path("statementId").asString()
        Thread.sleep(2000)
        assertEquals("UPLOADED", statement(pdfId).path("status").asString(), "the Kotlin CSV parser must skip PDFs")

        assertEquals(415, upload(accountId, "s.pdf", "not a pdf".toByteArray()).response.status)
        assertEquals(415, upload(accountId, "s.exe", "MZ".toByteArray()).response.status)
        assertEquals(404, upload(UUID.randomUUID().toString(), "a.csv", "a;b".toByteArray()).response.status)
        mvc.perform(get("/api/statements/${UUID.randomUUID()}")).andExpect(status().isNotFound)
        mvc.perform(get("/api/statements/$pdfId/questions")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `SSE sends the current status, then each change, then completes on a terminal status`() {
        val accountId = createAccount().path("id").asString()
        val tampered = Files.readString(TestPaths.sample("revolut-2026-08.csv")).replace("Spotify,-26.99", "Spotify,-26.98")
        // Pause nothing: subscribe immediately after upload; the stream must end with FAILED either way.
        val id = body(upload(accountId, "r.csv", tampered.toByteArray())).path("statementId").asString()
        val lines = CopyOnWriteArrayList<String>()
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/statements/$id/events"))
            .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(60)).build()
        val done: CompletableFuture<HttpResponse<Void>> = client.sendAsync(request, HttpResponse.BodyHandlers.fromLineSubscriber(
            object : java.util.concurrent.Flow.Subscriber<String> {
                override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) = s.request(Long.MAX_VALUE)
                override fun onNext(item: String) { lines += item }
                override fun onError(throwable: Throwable) {}
                override fun onComplete() {}
            },
        ))
        val response = done.get(60, java.util.concurrent.TimeUnit.SECONDS) // completes because FAILED is terminal
        assertEquals(200, response.statusCode())
        val events = lines.filter { it.startsWith("data:") }.map { json.readTree(it.removePrefix("data:").trim()) }
        assertTrue(events.isNotEmpty())
        assertEquals("FAILED", events.last().path("status").asString())
        assertEquals("BALANCE_MISMATCH", events.last().path("error").path("code").asString())
        assertTrue(lines.any { it == "event:status" || it == "event: status" })
        assertTrue(lines.any { it.startsWith("id:") })
        assertNotNull(events.first().path("occurredAt").asString(null))
        assertEquals(id, events.first().path("statementId").asString())
        assertEquals(0, sse.subscriberCount(UUID.fromString(id)))
    }

    @Test
    fun `health shows db, kafka and ollama UP`() {
        val health = body(mvc.perform(get("/actuator/health")).andExpect(status().isOk).andReturn())
        assertEquals("UP", health.path("status").asString())
        assertEquals("UP", health.path("components").path("db").path("status").asString())
        assertEquals("UP", health.path("components").path("kafka").path("status").asString())
        assertEquals(1, health.path("components").path("kafka").path("details").path("nodes").asInt())
        assertEquals("UP", health.path("components").path("ollama").path("status").asString())
        assertEquals("0.0.0-test", health.path("components").path("ollama").path("details").path("version").asString())
    }

    @Test
    fun `openapi spec is published`() {
        val api = body(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn())
        assertTrue(api.path("paths").has("/api/statements/{statementId}/events"))
        assertTrue(api.path("paths").has("/api/accounts"))
        assertNull(api.path("paths").path("/api/accounts").path("post").path("requestBody").path("x-iban").asString(null))
    }
}
