package dev.spendly.support

import dev.spendly.shared.crypto.HmacService
import dev.spendly.shared.privacy.Sanitizer
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

object TestPaths {
    val contracts: Path = Path.of(System.getProperty("spendly.test.contracts-dir") ?: "../contracts").toAbsolutePath().normalize()
    val samples: Path = Path.of(System.getProperty("spendly.test.samples-dir") ?: "../samples").toAbsolutePath().normalize()

    fun sample(name: String): Path = samples.resolve(name)
}

object TestJson {
    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun read(path: Path): JsonNode = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8))
}

object Fixtures {
    val sanitizerCases: JsonNode by lazy { TestJson.read(TestPaths.contracts.resolve("fixtures/sanitizer-cases.json")) }
    val fixtureSecret: String get() = sanitizerCases.path("hmacSecret").asString()

    fun sanitizer(secret: String = fixtureSecret): Sanitizer =
        Sanitizer(TestJson.read(TestPaths.contracts.resolve("pii-rules.json")), HmacService.fromSecretText(secret))
}

/** Builds a mod-97-valid synthetic IBAN (never a real one) so each test can register its own account. */
object TestIbans {
    fun random(country: String = "RO", bankCode: String = "TEST"): String {
        val digits = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).toString().padStart(16, '0').take(16)
        return withCheckDigits(country, bankCode + digits)
    }

    fun withCheckDigits(country: String, bban: String): String {
        val rearranged = bban + country + "00"
        val numeric = rearranged.map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString("")
        val check = 98 - BigInteger(numeric).mod(BigInteger.valueOf(97)).toInt()
        return country + check.toString().padStart(2, '0') + bban
    }
}

/** Polls until [condition] is true or the timeout expires. */
fun eventually(timeoutMs: Long = 30_000, intervalMs: Long = 100, message: String = "condition not met", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            if (condition()) return
        } catch (e: Throwable) {
            last = e
        }
        Thread.sleep(intervalMs)
    }
    throw AssertionError("Timed out after ${timeoutMs}ms: $message", last)
}
