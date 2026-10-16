package dev.spendly.categorization

import java.text.Normalizer
import java.util.Locale

/** A normalized merchant: display name ("Kaufland") and its match key ("KAUFLAND"). */
data class MerchantName(val display: String, val key: String)

/** Text keys used by aliases, keyword rules and merchant dedupe. */
object MatchKeys {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^A-Z0-9]+")

    /** Uppercase, no diacritics, every run of non-alphanumerics becomes one space, trimmed. */
    fun of(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "")
        return decomposed.uppercase(Locale.ROOT).replace(NON_ALNUM, " ").trim()
    }

    /** True when [pattern] (a match key) occurs as a whole-word sequence in [key]. */
    fun containsWords(key: String, pattern: String): Boolean =
        pattern.isNotEmpty() && " $key ".contains(" $pattern ")
}

/**
 * Turns a sanitized description into a merchant name:
 * `PLATA CARD ****1111 KAUFLAND 3412 IASI RO` → `Kaufland`. Strips channel prefixes, masked cards/IBANs,
 * masked contacts, one trailing store number, trailing cities and country codes. Person transfers
 * (`[PERSON#…]`) have no merchant.
 */
object MerchantNormalizer {
    private val PERSON = Regex("\\[PERSON#[0-9a-f]+]")
    private val MASKS = listOf(
        Regex("[A-Z]{2}\\*\\*…[A-Z0-9]{4}"), // masked IBAN
        Regex("\\*+[0-9]{4}"),               // masked card ****1111 or *3333
        Regex("\\[(PHONE|EMAIL)]"),
    )

    private val PREFIXES: List<List<String>> = listOf(
        "PLATA LA POS", "PLATA CARD", "PLATA POS", "PLATA ONLINE", "PLATA FACTURA", "CUMPARARE POS", "CUMPARARE",
        "TRANZACTIE POS", "TRANZACTIE CARD", "CARD PAYMENT", "PAYMENT TO", "PAYMENT FROM", "POS", "PLATA",
        "INCASARE", "ACHITARE FACTURA", "DEBIT DIRECT",
    ).map { it.split(' ') }.sortedByDescending { it.size }

    private val COUNTRIES = setOf(
        "RO", "ROU", "NL", "IE", "GB", "UK", "US", "USA", "LU", "DE", "FR", "ES", "IT", "CY", "MT", "SE", "LT", "EE", "BE", "AT", "HU", "BG",
    )

    private val CITIES = setOf(
        "IASI", "CLUJ NAPOCA", "CLUJ", "BUCURESTI", "BUCHAREST", "TIMISOARA", "BRASOV", "CONSTANTA", "SIBIU", "ORADEA",
        "CRAIOVA", "GALATI", "PLOIESTI", "ARAD", "PITESTI", "BACAU", "SUCEAVA", "BAIA MARE", "TARGU MURES", "BRAILA",
        "BUZAU", "BOTOSANI", "SATU MARE", "DEVA", "ALBA IULIA", "FOCSANI", "TULCEA", "RESITA", "SLATINA", "VASLUI",
        "ZALAU", "OTOPENI", "VOLUNTARI", "ILFOV", "PIATRA NEAMT", "TARGOVISTE", "MEDIAS", "HUNEDOARA",
        "AMSTERDAM", "DUBLIN", "LONDON", "LUXEMBOURG", "PARIS", "BERLIN", "VILNIUS", "STOCKHOLM", "MUNCHEN", "MUNICH",
    )

    private val NUMBER = Regex("^[0-9]{2,6}$")

    fun normalize(description: String): MerchantName? {
        if (PERSON.containsMatchIn(description)) return null
        var s = description
        MASKS.forEach { s = it.replace(s, " ") }
        val tokens = s.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return null
        val keys = tokens.map { MatchKeys.of(it) }.toMutableList()

        // Channel prefixes, repeatedly ("PLATA CARD … PLATA IMPOZIT …" → "IMPOZIT …").
        var changed = true
        while (changed && tokens.size > 1) {
            changed = false
            for (prefix in PREFIXES) {
                if (tokens.size > prefix.size && keys.subList(0, prefix.size) == prefix) {
                    repeat(prefix.size) { tokens.removeAt(0); keys.removeAt(0) }
                    changed = true
                    break
                }
            }
        }
        // Drop tokens that are only punctuation.
        for (i in tokens.indices.reversed()) if (keys[i].isEmpty()) { tokens.removeAt(i); keys.removeAt(i) }
        if (tokens.isEmpty()) return null

        // Trailing country codes and cities, then at most one trailing store number.
        var storeNumberRemoved = false
        changed = true
        while (changed && tokens.size > 1) {
            changed = false
            val last = keys.last()
            val lastTwo = if (keys.size >= 3) keys[keys.size - 2] + " " + last else null
            when {
                last in COUNTRIES -> { tokens.removeLast(); keys.removeLast(); changed = true }
                lastTwo != null && lastTwo in CITIES -> {
                    repeat(2) { tokens.removeLast(); keys.removeLast() }
                    changed = true
                }
                last in CITIES -> { tokens.removeLast(); keys.removeLast(); changed = true }
                !storeNumberRemoved && NUMBER.matches(last) -> {
                    tokens.removeLast(); keys.removeLast(); storeNumberRemoved = true; changed = true
                }
            }
        }
        val key = keys.joinToString(" ").trim()
        if (key.isEmpty()) return null
        val raw = tokens.joinToString(" ")
        val display = if (raw.any { it.isLowerCase() }) raw else titleCase(tokens)
        return MerchantName(display.take(120), key.take(120))
    }

    private fun titleCase(tokens: List<String>): String = tokens.joinToString(" ") { t ->
        val letters = t.count { it.isLetter() }
        if (letters <= 3 && t.none { it == '.' }) t
        else t.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
