package dev.spendly.shared.privacy

import dev.spendly.shared.crypto.HmacService
import java.text.Normalizer
import java.util.regex.Matcher
import java.util.regex.Pattern
import tools.jackson.databind.JsonNode

/**
 * The shared PII sanitizer. Implements the algorithm in contracts/README.md ("Sanitizer algorithm")
 * exactly, driven by contracts/pii-rules.json, so it matches the Python sanitizer (AC-SD-4).
 *
 * Stateless after construction and thread-safe. Never log its input.
 */
class Sanitizer(rules: JsonNode, private val hmac: HmacService) {

    data class Token(val ruleId: String, val token: String)

    data class Result(val text: String, val tokens: List<Token>) {
        fun firstToken(ruleId: String): String? = tokens.firstOrNull { it.ruleId == ruleId }?.token

        /** `counterpartyToken` per contracts: first iban token, else first person_name token. */
        fun counterparty(): Pair<String, CounterpartyKind>? =
            firstToken(RULE_IBAN)?.let { it to CounterpartyKind.IBAN }
                ?: firstToken(RULE_PERSON)?.let { it to CounterpartyKind.PERSON }

        override fun toString() = "Sanitizer.Result(<redacted>, tokens=${tokens.size})"
    }

    enum class CounterpartyKind { IBAN, PERSON }

    private class Rule(
        val id: String,
        val pattern: Pattern,
        val action: String,
        val replacement: String,
        val tokenNamespace: String?,
        val keywordGroup: Int,
        val nameGroup: Int,
        val minWords: Int,
        val stopWords: Set<String>,
        val companyWords: Set<String>,
    )

    val rulesVersion: Int = rules.path("version").asInt()
    private val unicodeForm: Normalizer.Form
    private val whitespaceToSpace: Set<Int>
    private val ruleList: List<Rule>

    init {
        val norm = rules.path("normalization")
        unicodeForm = Normalizer.Form.valueOf(norm.path("unicodeForm").asString("NFC"))
        whitespaceToSpace = norm.path("whitespaceToSpace").values().map { it.asString().codePointAt(0) }.toSet()
        ruleList = rules.path("rules").values().map { r ->
            val id = r.path("id").asString()
            require(id in SUPPORTED_RULES || r.path("action").asString() in setOf("mask", "remove")) {
                "Unsupported sanitizer rule: $id"
            }
            Rule(
                id = id,
                pattern = Pattern.compile(r.path("pattern").asString()),
                action = r.path("action").asString(),
                replacement = r.path("replacement").asString(""),
                tokenNamespace = r.path("tokenNamespace").takeIf { it.isString }?.asString(),
                keywordGroup = r.path("keywordGroup").asInt(1),
                nameGroup = r.path("nameGroup").asInt(2),
                minWords = r.path("minWords").asInt(2),
                stopWords = r.path("stopWords").values().map { it.asString() }.toSet(),
                companyWords = r.path("companyWords").values().map { it.asString() }.toSet(),
            )
        }
    }

    fun sanitize(input: String?): Result {
        // 1. normalize
        var text = normalizeInput(input ?: "")
        val tokens = ArrayList<Token>()
        // 2. rules in file order
        for (rule in ruleList) {
            text = when (rule.id) {
                RULE_IBAN -> replaceAll(text, rule.pattern) { m -> iban(rule, m, tokens) }
                RULE_CNP -> replaceAll(text, rule.pattern) { m ->
                    if (Validators.cnpChecksum(m.group())) rule.replacement else m.group()
                }
                RULE_PAN -> replaceAll(text, rule.pattern) { m -> pan(m) }
                RULE_PERSON -> replaceAll(text, rule.pattern) { m -> personName(rule, m, tokens) }
                else -> replaceAll(text, rule.pattern) { _ -> rule.replacement }
            }
        }
        // 3. clean up
        text = collapseSpaces(text).pyStrip()
        return Result(text, tokens.toList())
    }

    private fun normalizeInput(raw: String): String {
        val nfc = Normalizer.normalize(raw, unicodeForm)
        val sb = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            if (cp in whitespaceToSpace) sb.append(' ') else sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return collapseSpaces(sb.toString()).pyStrip()
    }

    private fun iban(rule: Rule, m: Matcher, tokens: MutableList<Token>): String {
        val match = m.group()
        val (n, compact) = shrink(match, Validators::ibanMod97) ?: return match
        tokens += Token(rule.id, hmac.hex("${rule.tokenNamespace}:$compact"))
        return "${compact.substring(0, 2)}**…${compact.takeLast(4)}" + rest(match, n)
    }

    private fun pan(m: Matcher): String {
        val match = m.group()
        val (n, digits) = shrink(match, Validators::luhn) ?: return match
        return "****${digits.takeLast(4)}" + rest(match, n)
    }

    private fun personName(rule: Rule, m: Matcher, tokens: MutableList<Token>): String {
        val keyword = m.group(rule.keywordGroup)
        val words = m.group(rule.nameGroup).split(' ').filter { it.isNotEmpty() }
        val norms = words.map(::normWord)
        val cut = norms.indexOfFirst { it in rule.stopWords }.let { if (it < 0) words.size else it }
        val kept = norms.subList(0, cut)
        val tail = words.subList(cut, words.size)
        if (kept.size < rule.minWords || kept.any { it in rule.companyWords }) return m.group()
        val token = hmac.hex("${rule.tokenNamespace}:${kept.joinToString(" ")}")
        tokens += Token(rule.id, token)
        val sb = StringBuilder(keyword).append(" [PERSON#").append(token, 0, 8).append(']')
        if (tail.isNotEmpty()) sb.append(' ').append(tail.joinToString(" "))
        return sb.toString()
    }

    companion object {
        const val RULE_IBAN = "iban"
        const val RULE_CNP = "cnp"
        const val RULE_PAN = "pan"
        const val RULE_PERSON = "person_name"
        private val SUPPORTED_RULES = setOf(RULE_IBAN, RULE_CNP, RULE_PAN, RULE_PERSON)
        private val SPACES = Regex(" {2,}")

        private fun collapseSpaces(s: String) = SPACES.replace(s, " ")

        /** NFKD, drop combining marks (Mn), uppercase (locale-independent), remove '.'. */
        fun normWord(word: String): String {
            val decomposed = Normalizer.normalize(word, Normalizer.Form.NFKD)
            val sb = StringBuilder(decomposed.length)
            var i = 0
            while (i < decomposed.length) {
                val cp = decomposed.codePointAt(i)
                if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            return sb.toString().uppercase().replace(".", "")
        }

        /** Chunk shrinking: the longest prefix of space-separated chunks whose concatenation is valid. */
        private fun shrink(match: String, valid: (String) -> Boolean): Pair<Int, String>? {
            val chunks = match.split(' ')
            for (n in chunks.size downTo 1) {
                val candidate = chunks.subList(0, n).joinToString("")
                if (valid(candidate)) return n to candidate
            }
            return null
        }

        private fun rest(match: String, n: Int): String {
            val chunks = match.split(' ')
            return match.substring(chunks.subList(0, n).joinToString(" ").length)
        }

        /** Python `re.sub(pattern, fn, text)` / Java find() loop: non-overlapping, left to right. */
        private inline fun replaceAll(text: String, pattern: Pattern, fn: (Matcher) -> String): String {
            val m = pattern.matcher(text)
            if (!m.find()) return text
            val sb = StringBuilder(text.length)
            var last = 0
            do {
                sb.append(text, last, m.start())
                sb.append(fn(m))
                last = m.end()
            } while (m.find())
            sb.append(text, last, text.length)
            return sb.toString()
        }
    }
}
