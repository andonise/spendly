package dev.spendly.sanitizer

/** Validators used by pii-rules.json (`iban_mod97`, `luhn`, `cnp_checksum`) and by the accounts API. */
object Validators {
    private val IBAN_SHAPE = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]+$")
    private val CNP_WEIGHTS = intArrayOf(2, 7, 9, 1, 4, 6, 3, 5, 8, 2, 7, 9)

    /** ISO 7064 mod-97 on a compact IBAN; also checks shape and length 15–34. */
    fun ibanMod97(candidate: String): Boolean {
        if (candidate.length !in 15..34 || !IBAN_SHAPE.matches(candidate)) return false
        val rearranged = candidate.substring(4) + candidate.substring(0, 4)
        var rem = 0
        for (ch in rearranged) {
            rem = when (ch) {
                in '0'..'9' -> (rem * 10 + (ch - '0')) % 97
                in 'A'..'Z' -> (rem * 100 + (ch - 'A' + 10)) % 97
                else -> return false
            }
        }
        return rem == 1
    }

    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19 || digits.any { it !in '0'..'9' }) return false
        var total = 0
        for ((i, ch) in digits.reversed().withIndex()) {
            var d = ch - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            total += d
        }
        return total % 10 == 0
    }

    fun cnpChecksum(cnp: String): Boolean {
        if (cnp.length != 13 || cnp.any { it !in '0'..'9' }) return false
        var sum = 0
        for (i in 0 until 12) sum += (cnp[i] - '0') * CNP_WEIGHTS[i]
        val r = sum % 11
        val control = if (r == 10) 1 else r
        return control == cnp[12] - '0'
    }
}

/** Python `str.isspace()` semantics, so trimming matches the Python sanitizer exactly. */
fun isPyWhitespace(c: Char): Boolean =
    Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'

/** Python `str.strip()` with no arguments. */
fun String.pyStrip(): String {
    var start = 0
    var end = length
    while (start < end && isPyWhitespace(this[start])) start++
    while (end > start && isPyWhitespace(this[end - 1])) end--
    return substring(start, end)
}
