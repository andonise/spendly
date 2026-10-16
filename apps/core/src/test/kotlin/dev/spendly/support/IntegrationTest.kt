package dev.spendly.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Base for integration tests. Every subclass uses exactly the same configuration, so Spring caches ONE
 * application context (one set of Kafka consumers) shared by all of them. Containers are singletons
 * started once per JVM (pgvector/pgvector:pg17 and redpandadata/redpanda:v26.2.3); the compose stack is never used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestAiConfiguration::class)
abstract class IntegrationTest {

    companion object {
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"),
        ).withDatabaseName("spendly").withUsername("spendly").withPassword("spendly-test")

        val redpanda: RedpandaContainer = RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v26.2.3"))

        val uploadDir: Path = Files.createTempDirectory("spendly-uploads-")
        val hmacKeyFile: Path = Files.createTempFile("spendly-hmac-", ".key").also {
            Files.writeString(it, Fixtures.fixtureSecret + "\n", StandardCharsets.UTF_8)
        }
        val aesKey: String = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        /** Stub of Ollama's GET /api/version so the health check is deterministic (no real Ollama in tests). */
        val ollamaStub: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/version") { ex ->
                val body = """{"version":"0.0.0-test"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }

        val topics = listOf(
            "spendly.statement-uploaded", "spendly.transactions-imported", "spendly.statement-parse-failed",
            "spendly.statement-uploaded.dlq", "spendly.transactions-imported.dlq", "spendly.statement-parse-failed.dlq",
            "spendly.statement-parsed", "spendly.input-required", "spendly.answers-submitted", "spendly.transactions-changed",
            "spendly.statement-parsed.dlq", "spendly.answers-submitted.dlq", "spendly.transactions-changed.dlq",
            "spendly.test-outbox",
        )

        init {
            postgres.start()
            redpanda.start()
            AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers)).use { admin ->
                val existing = admin.listTopics().names().get()
                admin.createTopics(topics.filterNot(existing::contains).map { NewTopic(it, 3, 1.toShort()) }).all().get()
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.kafka.bootstrap-servers") { redpanda.bootstrapServers }
            registry.add("spring.ai.ollama.base-url") { "http://127.0.0.1:${ollamaStub.address.port}" }
            registry.add("spendly.contracts-dir") { TestPaths.contracts.toString() }
            registry.add("spendly.secrets.hmac-secret-path") { hmacKeyFile.toString() }
            registry.add("spendly.secrets.aes-key") { aesKey }
            registry.add("spendly.upload.dir") { uploadDir.toString() }
            registry.add("spendly.upload.retention") { "PT0S" }
            registry.add("spendly.fx.bnr.enabled") { "false" }
            registry.add("spendly.llm.enabled") { "true" }
            registry.add("spendly.outbox.poll-interval") { "200ms" }
            // Same backoff shape as production (x2, 3 retries) but faster, to keep the suite quick.
            registry.add("spendly.kafka.retry-initial-interval") { "100ms" }
        }
    }
}
