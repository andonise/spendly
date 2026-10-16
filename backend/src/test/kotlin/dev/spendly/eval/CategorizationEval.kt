package dev.spendly.eval

import dev.spendly.categorization.CategorizationEngine
import dev.spendly.categorization.Category
import dev.spendly.categorization.LlmMode
import dev.spendly.categorization.TxInput
import dev.spendly.parsing.Csv
import dev.spendly.support.TestPaths
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Categorization evaluation (not part of `test`): `.\gradlew.bat evalCategorization -Pgolden=<csv> [-PllmOff=true]`.
 *
 * Runs the real layers (rules with the system seed only, embeddings with nomic-embed-text, LLM with qwen3:8b)
 * cold, on a fresh pgvector DB, over a CSV with columns `description,category`. Amounts are unknown in the CSV,
 * so rows are categorized without a sign. Prints the metrics of eval/golden/README.md and writes them as JSON.
 */
@Tag("eval")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CategorizationEval {
    @Autowired lateinit var engine: CategorizationEngine
    @Autowired lateinit var mode: LlmMode
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper

    data class Row(val description: String, val expected: Category)

    data class LayerStats(val decided: Int, val correct: Int, val coverage: Double, val accuracy: Double?)

    @Test
    fun `evaluate categorization`() {
        val golden = Path.of(System.getProperty("spendly.eval.golden") ?: TestPaths.contracts.resolve("../eval/golden/categorization-dev.csv").toString())
        val rows = readGolden(golden)
        check(rows.isNotEmpty()) { "No rows in $golden" }
        val noSeed = System.getProperty("spendly.eval.no-seed").toBoolean()
        if (noSeed) {
            jdbc.sql("DELETE FROM merchant_aliases WHERE created_by = 'SYSTEM'").update()
            jdbc.sql("DELETE FROM merchants").update()
        }
        val llmOn = System.getProperty("spendly.eval.llm-off") != "true"
        mode.set(llmOn)

        val inputs = rows.map { TxInput(UUID.randomUUID(), it.description, null, null) }
        val started = System.nanoTime()
        // Seed merchant embeddings first, so the run time below is the categorization itself.
        engine.indexMerchants()
        val indexedAt = System.nanoTime()
        val result = engine.categorize(inputs, llmEnabled = llmOn)
        val finished = System.nanoTime()

        val byId = result.decisions.associateBy { it.id }
        data class Outcome(val row: Row, val layer: String, val predicted: Category?, val question: String?)
        val outcomes = rows.mapIndexed { i, r ->
            val d = byId.getValue(inputs[i].id)
            Outcome(r, if (d.category != null) d.source!!.name else "QUESTION", d.category, d.question)
        }
        val auto = outcomes.filter { it.predicted != null }
        val correct = auto.count { it.predicted == it.row.expected }
        val layers = listOf("RULE", "EMBEDDING", "LLM").associateWith { layer ->
            val l = auto.filter { it.layer == layer }
            val c = l.count { it.predicted == it.row.expected }
            LayerStats(l.size, c, ratio(l.size, rows.size), if (l.isEmpty()) null else ratio(c, l.size))
        }
        val confusions = auto.filter { it.predicted != it.row.expected }
            .groupBy { "${it.row.expected} -> ${it.predicted}" }
            .map { (k, v) -> mapOf("confusion" to k, "count" to v.size, "examples" to v.take(3).map { it.row.description }) }
            .sortedByDescending { it["count"] as Int }.take(10)
        val questions = outcomes.filter { it.predicted == null }
        val report = linkedMapOf(
            "golden" to golden.fileName.toString(),
            "systemSeed" to !noSeed,
            "model" to System.getProperty("spendly.eval.model", "qwen3:8b"),
            "llmEnabled" to llmOn,
            "total" to rows.size,
            "autoCategorized" to auto.size,
            "coverage" to ratio(auto.size, rows.size),
            "accuracy" to if (auto.isEmpty()) null else ratio(correct, auto.size),
            "correct" to correct,
            "questions" to questions.size,
            "perLayer" to layers,
            "questionsWouldHaveBeen" to questions.groupBy { it.row.expected.name }.mapValues { it.value.size },
            "llmAvailable" to result.llmAvailable,
            "embeddingsAvailable" to result.embeddingsAvailable,
            "llmSeconds" to result.llmNanos / 1e9,
            "merchantIndexSeconds" to (indexedAt - started) / 1e9,
            "categorizeSeconds" to (finished - indexedAt) / 1e9,
            "topConfusions" to confusions,
            "thresholds" to mapOf(
                "embedding" to System.getProperty("spendly.categorization.embedding-threshold", "default"),
                "confidence" to System.getProperty("spendly.categorization.confidence-threshold", "default"),
            ),
        )
        val out = Path.of(System.getProperty("spendly.eval.report") ?: "build/reports/categorization-eval.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8)
        println("=== Categorization eval: ${golden.fileName} (${rows.size} rows, system seed: ${!noSeed}, LLM: ${if (llmOn) "on" else "off"}) ===")
        println("coverage  ${pct(auto.size, rows.size)} (${auto.size}/${rows.size} auto, ${questions.size} questions)")
        println("accuracy  ${pct(correct, auto.size)} (${correct}/${auto.size} on auto-categorized)")
        layers.forEach { (k, v) -> println("  %-9s decided %3d  coverage %s  accuracy %s".format(k, v.decided, pct(v.decided, rows.size), pct(v.correct, v.decided))) }
        println("LLM time  %.1f s, categorize total %.1f s".format(result.llmNanos / 1e9, (finished - indexedAt) / 1e9))
        confusions.forEach { println("  confusion ${it["confusion"]} x${it["count"]}: ${it["examples"]}") }
        questions.forEach { println("  question [${it.row.expected}] ${it.row.description} :: ${it.question}") }
        println("report    $out")
    }

    private fun readGolden(path: Path): List<Row> {
        val records = Csv.parse(Files.readString(path, StandardCharsets.UTF_8).removePrefix("﻿"), ',').filter { r -> r.any { it.isNotBlank() } }
        val header = records.first().map { it.trim().lowercase() }
        val d = header.indexOf("description")
        val c = header.indexOf("category")
        check(d >= 0 && c >= 0) { "CSV needs description and category columns" }
        return records.drop(1).map { r ->
            Row(r[d].trim(), Category.parse(r[c]) ?: error("Unknown category '${r[c]}' in $path"))
        }
    }

    private fun ratio(a: Int, b: Int): Double = if (b == 0) 0.0 else Math.round(a * 10000.0 / b) / 10000.0

    private fun pct(a: Int, b: Int): String = if (b == 0) "n/a" else "%.1f%%".format(a * 100.0 / b)

    companion object {
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"),
        ).withDatabaseName("spendly").withUsername("spendly").withPassword("spendly-eval")

        private val hmacKeyFile: Path = Files.createTempFile("spendly-eval-hmac-", ".key").also {
            Files.writeString(it, Base64.getEncoder().encodeToString(ByteArray(48).also(SecureRandom()::nextBytes)), StandardCharsets.UTF_8)
        }
        private val uploadDir: Path = Files.createTempDirectory("spendly-eval-uploads-")

        init {
            postgres.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.kafka.bootstrap-servers") { "127.0.0.1:1" }
            registry.add("spring.kafka.listener.auto-startup") { "false" }
            registry.add("spendly.outbox.relay-enabled") { "false" }
            registry.add("spendly.fx.bnr.enabled") { "false" }
            registry.add("spendly.contracts-dir") { TestPaths.contracts.toString() }
            registry.add("spendly.secrets.hmac-secret-path") { hmacKeyFile.toString() }
            registry.add("spendly.secrets.aes-key") { Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)) }
            registry.add("spendly.upload.dir") { uploadDir.toString() }
            System.getProperty("spendly.eval.ollama-base-url")?.let { url -> registry.add("spring.ai.ollama.base-url") { url } }
            listOf("spendly.categorization.embedding-threshold", "spendly.categorization.confidence-threshold", "spendly.llm.provider-structured-output")
                .forEach { key -> System.getProperty(key)?.let { v -> registry.add(key) { v } } }
        }
    }
}
