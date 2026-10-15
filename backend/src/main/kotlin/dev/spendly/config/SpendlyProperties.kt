package dev.spendly.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * All `spendly.*` settings. Values are bound from the environment variables listed in `.env.example`
 * (see application.yml). Secrets are validated in [dev.spendly.crypto.SecretsConfiguration].
 */
@ConfigurationProperties(prefix = "spendly")
data class SpendlyProperties(
    val baseCurrency: String = "RON",
    val contractsDir: String? = null,
    val llm: Llm = Llm(),
    val ollama: Ollama = Ollama(),
    val secrets: Secrets = Secrets(),
    val upload: Upload = Upload(),
    val outbox: Outbox = Outbox(),
    val kafka: Kafka = Kafka(),
    val sse: Sse = Sse(),
    val fx: Fx = Fx(),
) {
    data class Llm(val enabled: Boolean = true)

    data class Ollama(val healthTimeout: Duration = Duration.ofSeconds(2))

    data class Secrets(val hmacSecretPath: String? = null, val aesKey: String? = null) {
        override fun toString() = "Secrets(hmacSecretPath=${hmacSecretPath?.let { "<set>" }}, aesKey=<redacted>)"
    }

    data class Upload(
        val dir: String? = null,
        val retention: Duration = Duration.ZERO,
        val maxSizeMb: Long = 20,
    )

    data class Outbox(
        val relayEnabled: Boolean = true,
        val pollInterval: Duration = Duration.ofMillis(500),
        val batchSize: Int = 50,
        val sendTimeout: Duration = Duration.ofSeconds(10),
    )

    data class Kafka(
        val retryInitialInterval: Duration = Duration.ofSeconds(1),
        val retryMultiplier: Double = 2.0,
        val retryMaxAttempts: Int = 3,
    )

    data class Sse(
        val keepAlive: Duration = Duration.ofSeconds(15),
        val emitterTimeout: Duration = Duration.ofMinutes(30),
    )

    data class Fx(
        val maxLookbackDays: Long = 10,
        val bnr: Bnr = Bnr(),
    ) {
        data class Bnr(
            val enabled: Boolean = true,
            val dailyUrl: String = "https://curs.bnr.ro/nbrfxrates.xml",
            val yearlyUrlTemplate: String = "https://curs.bnr.ro/files/xml/years/nbrfxrates{year}.xml",
            val cron: String = "0 30 13 * * MON-FRI",
            val zone: String = "Europe/Bucharest",
            val timeout: Duration = Duration.ofSeconds(10),
        )
    }

    /** contracts/ folder: SPENDLY_CONTRACTS_DIR, else ../contracts relative to the working directory. */
    fun contractsPath(): Path {
        val configured = contractsDir?.trim().orEmpty()
        val path = if (configured.isNotEmpty()) Path.of(configured) else Path.of("..", "contracts")
        val absolute = path.toAbsolutePath().normalize()
        check(Files.isRegularFile(absolute.resolve("pii-rules.json"))) {
            "Contracts folder not found at $absolute (expected pii-rules.json). Set SPENDLY_CONTRACTS_DIR."
        }
        return absolute
    }
}
