package dev.spendly.categorization

import com.sun.net.httpserver.HttpServer
import dev.spendly.config.SpendlyProperties
import dev.spendly.support.Fixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaChatOptions
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AC-SD-2 at the HTTP level: the real Spring AI Ollama client talks to a recording stub of Ollama's /api/chat.
 * Raw statement lines full of PII go through the shared sanitizer (as the parsers do); every request body that
 * reaches "Ollama" must contain the sanitized descriptions and none of the raw values.
 */
class LlmWireTest {
    private val bodies = CopyOnWriteArrayList<String>()
    @Volatile private var fail = false
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/chat") { ex ->
            bodies += String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            if (fail) {
                ex.sendResponseHeaders(500, -1)
                ex.close()
                return@createContext
            }
            val content = """{"results":[{"id":1,"business":"shop","category":"SHOPPING","confidence":0.95,"question":null,"suggestions":[]},""" +
                """{"id":2,"business":"person","category":"OTHER","confidence":0.9,"question":null,"suggestions":[]},""" +
                """{"id":3,"business":"bills","category":"UTILITIES","confidence":0.9,"question":null,"suggestions":[]},""" +
                """{"id":4,"business":"state","category":"TAXES","confidence":0.9,"question":null,"suggestions":[]}]}"""
            val json = tools.jackson.databind.json.JsonMapper.builder().build()
            val body = json.writeValueAsBytes(
                mapOf(
                    "model" to "qwen3:8b", "created_at" to "2026-10-01T00:00:00Z",
                    "message" to mapOf("role" to "assistant", "content" to content), "done" to true, "done_reason" to "stop",
                ),
            )
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val properties = SpendlyProperties(llm = SpendlyProperties.Llm(maxAttempts = 2, retryBackoff = Duration.ofMillis(10)))
    private val gateway = LlmGateway(
        OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl("http://127.0.0.1:${server.address.port}").build())
            .options(OllamaChatOptions.builder().model("qwen3:8b").build())
            // As in application.yml (spring.ai.retry.max-attempts: 1): the gateway retries, not Spring AI.
            .retryTemplate(org.springframework.core.retry.RetryTemplate(org.springframework.core.retry.RetryPolicy.withMaxRetries(0)))
            .build(),
        properties,
        CategorizationMetrics(SimpleMeterRegistry()),
    )

    private val raw = listOf(
        "PLATA CARD 4111 1111 1111 1111 OBSCURE SHOP 1 IASI RO",
        "TRANSFER CATRE MARIA EXEMPLU RO49AAAA1B31007593840000 ceva",
        "Factura trimisa la ion.popescu@example.com, tel 0722 123 456",
        "Plata impozit CNP 1960523460032 catre ANAF",
    )
    private val forbidden = listOf(
        "4111 1111 1111 1111", "4111111111111111", "RO49AAAA1B31007593840000", "MARIA", "EXEMPLU",
        "ion.popescu@example.com", "0722 123 456", "1960523460032",
    )

    @Test
    fun `AC-SD-2 only sanitized descriptions reach Ollama`() {
        val sanitizer = Fixtures.sanitizer()
        val inputs = raw.map { TxInput(UUID.randomUUID(), sanitizer.sanitize(it).text, BigDecimal("-10.00"), "RON") }
        val system = LlmPrompts.system(listOf(sanitizer.sanitize("Payments to RO49AAAA1B31007593840000 are rent").text), emptyList())
        val out = gateway.entity(system, LlmPrompts.user(inputs, emptyMap()), LlmBatch::class.java)

        assertEquals(4, out.results.size)
        assertEquals(Category.SHOPPING, out.results[0].category)
        val wire = bodies.single()
        inputs.forEach { assertTrue(wire.contains(it.description.replace("\"", "\\\"")), "sanitized description missing: ${it.description}") }
        forbidden.forEach { assertFalse(wire.contains(it), "raw PII on the wire: $it") }
        assertTrue(wire.contains("****1111") && wire.contains("[PERSON#") && wire.contains("[EMAIL]") && wire.contains("[PHONE]"))
        // Provider structured output: the JSON schema with the category enum goes as Ollama's `format`.
        assertTrue(wire.contains("\"format\""))
        assertTrue(wire.contains("CARD_INTEREST"))
    }

    @Test
    fun `an unreachable model is retried, then reported as unavailable`() {
        fail = true
        assertThrows<LlmUnavailableException> { gateway.entity("s", "1 | out | 1.00 RON | X", LlmBatch::class.java) }
        assertEquals(2, bodies.size)
    }
}
