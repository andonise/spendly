package dev.spendly.parsing

import dev.spendly.support.Fixtures
import dev.spendly.support.TestJson
import dev.spendly.support.TestPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID

class CsvParsersTest {
    private val sanitizer = Fixtures.sanitizer()
    private val generic = GenericBankCsvParser(sanitizer)
    private val revolut = RevolutCsvParser(sanitizer)
    private val accountId = UUID.fromString("3f1c2a9e-7b4d-4e21-9a6f-0c5d8e2b1a47")
    private val ctx = ParseContext(accountId, "RON", "Generic Bank")

    private val genericText = Files.readString(TestPaths.sample("generic-bank-2026-08.csv"))
    private val revolutText = Files.readString(TestPaths.sample("revolut-2026-08.csv"))

    private val pan = Regex("(?<![0-9])[0-9](?: ?[0-9]){12,18}(?![0-9])")
    private val fullIban = Regex("[A-Z]{2}[0-9]{2} ?[A-Z0-9]{4}(?: ?[A-Z0-9]{4}){2,7}")

    @Test
    fun `format is detected from the header row`() {
        assertTrue(generic.detect(genericText))
        assertFalse(generic.detect(revolutText))
        assertTrue(revolut.detect(revolutText))
        assertFalse(revolut.detect(genericText))
    }

    @Test
    fun `generic bank sample - counts, totals, period and masking`() {
        val st = generic.parse(genericText, ctx)
        assertEquals(18, st.transactions.size)
        assertEquals(BigDecimal("6393.56"), st.transactions.filter { it.amount.signum() < 0 }.sumOf { it.amount }.negate())
        assertEquals(BigDecimal("8650.00"), st.transactions.filter { it.amount.signum() > 0 }.sumOf { it.amount })
        assertEquals(LocalDate.of(2026, 8, 1), st.periodStart)
        assertEquals(LocalDate.of(2026, 8, 31), st.periodEnd)
        assertEquals("RON", st.currency)
        assertEquals("kotlin-csv-generic", st.parser.name)

        val descriptions = st.transactions.map { it.description }
        descriptions.forEach { d ->
            assertFalse(pan.containsMatchIn(d), "PAN left in a description")
            assertFalse(fullIban.containsMatchIn(d), "IBAN left in a description")
            assertFalse(d.contains("POPESCU") || d.contains("IONESCU") || d.contains("0722"), "PII left in a description")
            assertFalse(d.contains("MARIA EXEMPLU"), "holder name extracted")
        }
        assertEquals("PLATA CARD ****1111 KAUFLAND 3412 IASI RO", descriptions[1])
        assertEquals("TRANSFER CATRE [PERSON#4d37c156] RO**…0000 chirie august", descriptions[4])
        assertEquals("Reincarcare [PHONE] Orange", descriptions[13])
        assertTrue(descriptions[16].startsWith("TRANSFER DE LA [PERSON#"))
        // Counterparty: first IBAN token, else person token
        assertEquals(Pair("1c9ac6775d5a872c103df3ddb118f89e343b744c345f1169a916426f17e290d8", dev.spendly.sanitizer.Sanitizer.CounterpartyKind.IBAN), st.transactions[4].counterparty)
        assertEquals(dev.spendly.sanitizer.Sanitizer.CounterpartyKind.PERSON, st.transactions[16].counterparty?.second)
        assertNull(st.transactions[0].counterparty)
        assertEquals(BigDecimal("8500.00"), st.transactions[0].amount)
        assertEquals(BigDecimal("-1249.99"), st.transactions[15].amount)
    }

    @Test
    fun `identical rows on one day get distinct hashes via occurrence`() {
        val events = TxHashes.toEvent(accountId, generic.parse(genericText, ctx).transactions)
        assertEquals((0 until 18).toList(), events.map { it.sequence })
        assertEquals(events[2].description, events[3].description)
        assertNotEquals(events[2].txHash, events[3].txHash)
        assertEquals(18, events.map { it.txHash }.toSet().size)
        // Stable: parsing again produces the same hashes
        assertEquals(events.map { it.txHash }, TxHashes.toEvent(accountId, generic.parse(genericText, ctx).transactions).map { it.txHash })
    }

    @Test
    fun `revolut sample - completed rows only, fee as its own row`() {
        val st = revolut.parse(revolutText, ParseContext(accountId, "RON", "Revolut"))
        assertEquals(8, st.transactions.size)
        assertEquals(BigDecimal("534.54"), st.transactions.filter { it.amount.signum() < 0 }.sumOf { it.amount }.negate())
        assertEquals(BigDecimal("500.00"), st.transactions.filter { it.amount.signum() > 0 }.sumOf { it.amount })
        assertEquals(LocalDate.of(2026, 8, 11), st.periodStart)
        assertEquals(LocalDate.of(2026, 8, 28), st.periodEnd)
        assertFalse(st.transactions.any { it.description == "Uber" }, "REVERTED row imported")
        val atm = st.transactions.indexOfFirst { it.description == "Cash at Revolut ATM" }
        assertEquals("FEE Cash at Revolut ATM", st.transactions[atm + 1].description)
        assertEquals(BigDecimal("-5.00"), st.transactions[atm + 1].amount)
        assertEquals(st.transactions[atm].bookingDate, st.transactions[atm + 1].bookingDate)
        assertEquals("To [PERSON#4d37c156]", st.transactions.first { it.amount == BigDecimal("-100.00") }.description)
        assertEquals(LocalDate.of(2026, 8, 13), st.transactions[1].bookingDate) // Completed Date, not Started Date
    }

    @Test
    fun `balance mismatch is a business failure`() {
        val tampered = genericText.replace("LIDL DISCOUNT IASI;96,40", "LIDL DISCOUNT IASI;96,41")
        val failure = assertThrows<ParseFailure> { generic.parse(tampered, ctx) }
        assertEquals(ParseFailure.BALANCE_MISMATCH, failure.reason)

        val tamperedRevolut = revolutText.replace("Spotify,-26.99", "Spotify,-26.98")
        assertEquals(ParseFailure.BALANCE_MISMATCH, assertThrows<ParseFailure> { revolut.parse(tamperedRevolut, ctx) }.reason)
    }

    @Test
    fun `txHash matches the formula in the contracts example`() {
        val example = TestJson.read(TestPaths.contracts.resolve("examples/transactions-imported.json"))
        val tx = example.path("payload").path("transactions")[0]
        val hash = TxHashes.hash(
            UUID.fromString(example.path("payload").path("accountId").asString()),
            LocalDate.parse(tx.path("bookingDate").asString()),
            tx.path("amount").asString(), tx.path("currency").asString(), tx.path("description").asString(), 0,
        )
        assertEquals(tx.path("txHash").asString(), hash)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "1.234,56|,|1234.56", "1,234.56|.|1234.56", "1.234,56|.|1234.56", "1,234.56|,|1234.56",
            "187,43|,|187.43", "-120.00|.|-120.00", "5.000,00|,|5000.00", "1.249,99|,|1249.99", "8500|,|8500.00",
            "1.234|,|1234.00", "1,234|.|1234.00", "1,5|,|1.50", "12.5|.|12.50", "+3,00|,|3.00", "1 234,56|,|1234.56",
            "1.234.567,89|,|1234567.89",
        ],
    )
    fun `amounts normalize both decimal conventions`(raw: String, decimal: Char, expected: String) {
        assertEquals(BigDecimal(expected), Amounts.parse(raw, decimal))
    }

    @Test
    fun `canonical amount string`() {
        assertEquals("-45.20", Amounts.canonical(BigDecimal("-45.2")))
        assertEquals("1234.00", Amounts.canonical(BigDecimal("1234")))
    }
}
