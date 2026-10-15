package dev.spendly.health

import dev.spendly.config.SpendlyProperties
import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.DescribeClusterOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/** `kafka` health: AdminClient.describeCluster with a short timeout. */
@Component("kafkaHealthIndicator")
class KafkaHealthIndicator(private val kafkaAdmin: KafkaAdmin) : HealthIndicator {
    private val timeoutMs = 3000

    private var client: AdminClient? = null

    @Synchronized
    private fun admin(): AdminClient = client ?: run {
        val config = HashMap(kafkaAdmin.configurationProperties)
        config[AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG] = timeoutMs
        config[AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG] = timeoutMs
        config[AdminClientConfig.CLIENT_ID_CONFIG] = "spendly-backend-health"
        AdminClient.create(config).also { client = it }
    }

    override fun health(): Health = try {
        val result = admin().describeCluster(DescribeClusterOptions().timeoutMs(timeoutMs))
        val clusterId = result.clusterId().get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        val nodes = result.nodes().get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        Health.up().withDetail("clusterId", clusterId).withDetail("nodes", nodes.size).build()
    } catch (e: Exception) {
        Health.down().withDetail("error", e.javaClass.simpleName).build()
    }

    @PreDestroy
    @Synchronized
    fun close() {
        client?.close(Duration.ofSeconds(2))
        client = null
    }
}

/**
 * `ollama` health: GET /api/version with a short timeout. Ollama being down never makes the app DOWN:
 * it reports DEGRADED (the app falls back to non-LLM mode), or DISABLED when spendly.llm.enabled=false.
 */
@Component("ollamaHealthIndicator")
class OllamaHealthIndicator(
    private val properties: SpendlyProperties,
    @Value("\${spring.ai.ollama.base-url:http://localhost:11434}") private val baseUrl: String,
) : HealthIndicator {

    private val client: RestClient by lazy {
        val timeout = properties.ollama.healthTimeout
        val http = HttpClient.newBuilder().connectTimeout(timeout).build()
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(timeout) })
            .build()
    }

    data class VersionResponse(val version: String? = null)

    override fun health(): Health {
        if (!properties.llm.enabled) return Health.status(DISABLED).withDetail("llmEnabled", false).build()
        return try {
            val version = client.get().uri("/api/version").retrieve().body(VersionResponse::class.java)
            Health.up().withDetail("version", version?.version ?: "unknown").build()
        } catch (e: Exception) {
            Health.status(DEGRADED)
                .withDetail("error", e.javaClass.simpleName)
                .withDetail("impact", "LLM features fall back to non-LLM mode")
                .build()
        }
    }

    companion object {
        val DEGRADED = Status("DEGRADED", "Ollama is unreachable; LLM features are unavailable")
        val DISABLED = Status("DISABLED", "LLM mode is off")
    }
}
