package dev.spendly.sanitizer

import dev.spendly.crypto.HmacService
import dev.spendly.support.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/** AC-SD-4 (Kotlin side): every case in contracts/fixtures/sanitizer-cases.json, text and tokens. */
class SanitizerFixturesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `matches the shared fixture`(id: String, input: String, expectedText: String, expectedTokens: List<Pair<String, String>>) {
        val result = sanitizer.sanitize(input)
        assertEquals(expectedText, result.text, "text of case $id")
        assertEquals(expectedTokens, result.tokens.map { it.ruleId to it.token }, "tokens of case $id")
    }

    @Test
    fun `fixture file has all 29 cases`() {
        assertEquals(29, Fixtures.sanitizerCases.path("cases").size())
    }

    companion object {
        private val sanitizer = Fixtures.sanitizer()

        @JvmStatic
        fun cases(): Stream<Arguments> = Fixtures.sanitizerCases.path("cases").values().map { c ->
            Arguments.of(
                c.path("id").asString(),
                c.path("input").asString(),
                c.path("expected").path("text").asString(),
                c.path("expected").path("tokens").values().map { it.path("ruleId").asString() to it.path("token").asString() },
            )
        }.stream()
    }
}

/** AC-SD-5: pseudonyms are stable and keyed. */
class PseudonymStabilityTest {
    private val sanitizer = Fixtures.sanitizer()

    @Test
    fun `same IBAN gives the same token, compact or spaced, and equals the accounts iban_hash`() {
        val a = sanitizer.sanitize("TRANSFER CATRE RO49AAAA1B31007593840000").tokens.single().token
        val b = sanitizer.sanitize("Beneficiar: RO49 AAAA 1B31 0075 9384 0000 chirie").tokens.single().token
        val c = sanitizer.sanitize("TRANSFER CATRE RO49AAAA1B31007593840000").tokens.single().token
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, HmacService.fromSecretText(Fixtures.fixtureSecret).ibanToken("RO49AAAA1B31007593840000"))
    }

    @Test
    fun `same person gives the same token across spellings`() {
        val a = sanitizer.sanitize("TRANSFER CATRE ION POPESCU").tokens.single().token
        val b = sanitizer.sanitize("To Ion Popescu").tokens.single().token
        assertEquals(a, b)
    }

    @Test
    fun `a different key gives different tokens`() {
        val other = Fixtures.sanitizer("another-secret-key-of-at-least-32-bytes!!")
        val input = "TRANSFER CATRE ION POPESCU RO38TEST7777888899990000"
        val mine = sanitizer.sanitize(input)
        val theirs = other.sanitize(input)
        assertEquals(mine.text.replace(Regex("PERSON#[0-9a-f]{8}"), ""), theirs.text.replace(Regex("PERSON#[0-9a-f]{8}"), ""))
        assertEquals(2, theirs.tokens.size)
        mine.tokens.zip(theirs.tokens).forEach { (m, t) -> assertNotEquals(m.token, t.token) }
    }
}

class DescriptionsTest {
    @Test
    fun `truncates to 500 code points, trims, and falls back to N-A`() {
        val emoji = "😀" // one code point, two UTF-16 units
        val long = emoji.repeat(499) + "ab"
        val result = Descriptions.finalize(long)
        assertEquals(500, result.codePointCount(0, result.length))
        assertEquals(emoji.repeat(499) + "a", result)
        assertEquals("N/A", Descriptions.finalize(""))
        assertEquals("N/A", Descriptions.finalize("   "))
        assertEquals("X", Descriptions.finalize("X".padEnd(500, ' ') + "Y"))
        assertEquals("FEE Cash at Revolut ATM", Descriptions.fee("Cash at Revolut ATM"))
    }
}
