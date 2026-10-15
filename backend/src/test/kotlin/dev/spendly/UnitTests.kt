package dev.spendly

import dev.spendly.config.SpendlyProperties
import dev.spendly.crypto.AesGcmCipher
import dev.spendly.crypto.SecretsConfiguration
import dev.spendly.crypto.SecretsMisconfiguredException
import dev.spendly.events.ContractSchemaValidator
import dev.spendly.events.DlqReason
import dev.spendly.events.EventTypes
import dev.spendly.events.NonRetryableEventException
import dev.spendly.fx.BnrClient
import dev.spendly.fx.BnrXmlParser
import dev.spendly.fx.FxRateRepository
import dev.spendly.fx.FxService
import dev.spendly.health.OllamaHealthIndicator
import dev.spendly.kafka.DlqRecoverer
import dev.spendly.logging.MaskingMessageConverter
import dev.spendly.sanitizer.Validators
import dev.spendly.statement.FileType
import dev.spendly.statement.UploadStorage
import dev.spendly.support.TestIbans
import dev.spendly.support.TestJson
import dev.spendly.support.TestPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.health.contributor.Status
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Base64

class AesGcmCipherTest {
    private val cipher = AesGcmCipher(ByteArray(32) { it.toByte() })

    @Test
    fun `round trip, random IV, versioned format`() {
        val a = cipher.encrypt("PLATA CARD ****1111 KAUFLAND")
        val b = cipher.encrypt("PLATA CARD ****1111 KAUFLAND")
        assertTrue(a.startsWith("v1:"))
        assertNotEquals(a, b)
        assertFalse(a.contains("KAUFLAND"))
        assertEquals("PLATA CARD ****1111 KAUFLAND", cipher.decrypt(a))
        assertEquals("PLATA CARD ****1111 KAUFLAND", cipher.decrypt(b))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val c = cipher.encrypt("hello")
        val bytes = Base64.getDecoder().decode(c.removePrefix("v1:"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertThrows<Exception> { cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)) }
    }
}

class SecretsConfigurationTest {
    private val config = SecretsConfiguration()

    @Test
    fun `fails fast with a clear message and never echoes the secret`(@TempDir dir: Path) {
        val missing = assertThrows<SecretsMisconfiguredException> { config.hmacService(SpendlyProperties()) }
        assertTrue(missing.message!!.contains("SPENDLY_HMAC_SECRET_PATH is not set"))

        val shortKey = dir.resolve("short.key").also { Files.writeString(it, "tooshort-secret-value") }
        val tooShort = assertThrows<SecretsMisconfiguredException> {
            config.hmacService(SpendlyProperties(secrets = SpendlyProperties.Secrets(hmacSecretPath = shortKey.toString())))
        }
        assertFalse(tooShort.message!!.contains("tooshort-secret-value"))

        assertTrue(assertThrows<SecretsMisconfiguredException> { config.aesGcmCipher(SpendlyProperties()) }.message!!.contains("SPENDLY_AES_KEY is not set"))
        val badKey = Base64.getEncoder().encodeToString(ByteArray(16))
        val wrongSize = assertThrows<SecretsMisconfiguredException> {
            config.aesGcmCipher(SpendlyProperties(secrets = SpendlyProperties.Secrets(aesKey = badKey)))
        }
        assertTrue(wrongSize.message!!.contains("exactly 32 bytes"))
        assertFalse(wrongSize.message!!.contains(badKey))
        assertThrows<SecretsMisconfiguredException> {
            config.aesGcmCipher(SpendlyProperties(secrets = SpendlyProperties.Secrets(aesKey = "not base64 !!")))
        }
    }

    @Test
    fun `valid secrets load`(@TempDir dir: Path) {
        val key = dir.resolve("hmac.key").also { Files.writeString(it, "  " + "k".repeat(40) + "\n") }
        val props = SpendlyProperties(secrets = SpendlyProperties.Secrets(key.toString(), Base64.getEncoder().encodeToString(ByteArray(32))))
        assertEquals(64, config.hmacService(props).hex("x").length)
        config.aesGcmCipher(props)
    }
}

class ValidatorsTest {
    @Test
    fun `iban mod97`() {
        assertTrue(Validators.ibanMod97("RO54SPND0000111122223333"))
        assertTrue(Validators.ibanMod97("RO49AAAA1B31007593840000"))
        assertFalse(Validators.ibanMod97("RO00AAAA1B31007593840000"))
        assertTrue(Validators.ibanMod97(TestIbans.random()))
    }

    @Test
    fun `luhn and cnp`() {
        assertTrue(Validators.luhn("4111111111111111"))
        assertFalse(Validators.luhn("1234567812345678"))
        assertTrue(Validators.cnpChecksum("1960523460032"))
        assertFalse(Validators.cnpChecksum("1960523460031"))
    }
}

class ContractSchemaValidatorTest {
    private val schemas = ContractSchemaValidator.load(TestPaths.contracts.resolve("schemas"))

    @Test
    fun `contract examples are valid`() {
        for ((file, type) in listOf(
            "statement-uploaded.json" to EventTypes.STATEMENT_UPLOADED,
            "transactions-imported.json" to EventTypes.TRANSACTIONS_IMPORTED,
            "statement-parse-failed.json" to EventTypes.STATEMENT_PARSE_FAILED,
        )) {
            val errors = schemas.getValue(type).validate(TestJson.read(TestPaths.contracts.resolve("examples/$file")))
            assertTrue(errors.isEmpty(), "$file: $errors")
        }
    }

    @Test
    fun `balances, unsafe paths and bad amounts are rejected`() {
        val tx = TestJson.read(TestPaths.contracts.resolve("examples/transactions-imported.json")) as tools.jackson.databind.node.ObjectNode
        (tx.path("payload") as tools.jackson.databind.node.ObjectNode).put("closingBalance", "7256.44")
        assertFalse(schemas.getValue(EventTypes.TRANSACTIONS_IMPORTED).validate(tx).isEmpty())

        val up = TestJson.read(TestPaths.contracts.resolve("examples/statement-uploaded.json")) as tools.jackson.databind.node.ObjectNode
        (up.path("payload") as tools.jackson.databind.node.ObjectNode).put("tempFilePath", "../etc/passwd")
        assertFalse(schemas.getValue(EventTypes.STATEMENT_UPLOADED).validate(up).isEmpty())
    }
}

class BnrAndFxTest {
    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <DataSet xmlns="http://www.bnr.ro/xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Header><Publisher>National Bank of Romania</Publisher><PublishingDate>2026-08-07</PublishingDate><MessageType>DR</MessageType></Header>
          <Body>
            <Subject>Reference rates</Subject>
            <OrigCurrency>RON</OrigCurrency>
            <Cube date="2026-08-06"><Rate currency="EUR">4.9712</Rate><Rate currency="HUF" multiplier="100">1.2650</Rate></Cube>
            <Cube date="2026-08-07"><Rate currency="EUR">4.9750</Rate><Rate currency="USD">4.2801</Rate></Cube>
          </Body>
        </DataSet>
    """.trimIndent()

    @Test
    fun `parses daily or yearly BNR XML with multipliers`() {
        val rates = BnrXmlParser.parse(xml.byteInputStream())
        assertEquals(4, rates.size)
        assertEquals(BigDecimal("0.01265000"), rates.first { it.currency == "HUF" }.rateToRon)
        assertEquals(BigDecimal("4.97500000"), rates.first { it.currency == "EUR" && it.date == LocalDate.of(2026, 8, 7) }.rateToRon)
    }

    @Test
    fun `base currency is 1, other currencies use the stored rate or the last earlier one`() {
        val repo = mockk<FxRateRepository>()
        every { repo.findRonRate("EUR", LocalDate.of(2026, 8, 9), 10) } returns (LocalDate.of(2026, 8, 7) to BigDecimal("4.97500000"))
        every { repo.findRonRate("USD", any(), any()) } returns null
        val fx = FxService(repo, mockk<BnrClient>(), SpendlyProperties(), mockk<TransactionTemplate>())
        assertEquals(BigDecimal.ONE, fx.rateToBase("RON", LocalDate.of(2026, 8, 9)))
        assertEquals(BigDecimal("4.97500000"), fx.rateToBase("EUR", LocalDate.of(2026, 8, 9)))
        assertNull(fx.rateToBase("USD", LocalDate.of(2026, 8, 9)))
        assertEquals(BigDecimal("-99.50"), fx.toBase(BigDecimal("-20.00"), BigDecimal("4.97500000")))
        verify(exactly = 0) { repo.findRonRate("RON", any(), any()) }
    }
}

class UploadTypeDetectionTest {
    @Test
    fun `extension and magic bytes must agree`() {
        assertEquals(FileType.CSV, UploadStorage.detectType("a.CSV", "Data;x\n".toByteArray()))
        assertEquals(FileType.PDF, UploadStorage.detectType("a.pdf", "%PDF-1.7".toByteArray()))
        assertEquals(FileType.XLSX, UploadStorage.detectType("a.xlsx", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1)))
        assertThrows<Exception> { UploadStorage.detectType("a.pdf", "Data;x".toByteArray()) }
        assertThrows<Exception> { UploadStorage.detectType("a.csv", byteArrayOf(0x50, 0x4B, 0x03, 0x00, 0x00)) }
        assertThrows<Exception> { UploadStorage.detectType("a.exe", "MZ".toByteArray()) }
    }
}

class DlqAndLoggingTest {
    @Test
    fun `dlq reason comes from the non-retryable cause, else RETRIES_EXHAUSTED`() {
        assertEquals(DlqReason.INVALID_JSON, DlqRecoverer.reasonFor(RuntimeException(NonRetryableEventException(DlqReason.INVALID_JSON, "x"))))
        assertEquals(DlqReason.RETRIES_EXHAUSTED, DlqRecoverer.reasonFor(IllegalStateException("db down")))
    }

    @Test
    fun `logback masking safety net`() {
        val masked = MaskingMessageConverter.mask("iban RO49 AAAA 1B31 0075 9384 0000 card 4111 1111 1111 1111 mail a.b@example.com tel 0722 123 456 id 9b2e4c71-5a3f-4d88-b1e0-6f7a2c9d3e15")
        assertFalse(masked.contains("RO49"))
        assertFalse(masked.contains("4111"))
        assertFalse(masked.contains("example.com"))
        assertFalse(masked.contains("0722"))
        assertTrue(masked.contains("9b2e4c71-5a3f-4d88-b1e0-6f7a2c9d3e15"), "IDs must stay readable")
    }
}

class OllamaHealthUnitTest {
    @Test
    fun `ollama down is DEGRADED, never an exception`() {
        val props = SpendlyProperties(ollama = SpendlyProperties.Ollama(java.time.Duration.ofMillis(500)))
        val health = OllamaHealthIndicator(props, "http://127.0.0.1:9").health()
        assertEquals(Status("DEGRADED"), health.status)
    }

    @Test
    fun `llm disabled reports DISABLED without calling ollama`() {
        val props = SpendlyProperties(llm = SpendlyProperties.Llm(false))
        assertEquals(Status("DISABLED"), OllamaHealthIndicator(props, "http://127.0.0.1:9").health().status)
    }
}
