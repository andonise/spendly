package dev.spendly.parsing

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

/** A business parse failure: published as StatementParseFailed, never retried. Message must not contain PII. */
class ParseFailure(val reason: String, message: String, val retryable: Boolean = false) : RuntimeException(message) {
    companion object {
        const val BALANCE_MISMATCH = "BALANCE_MISMATCH"
        const val UNSUPPORTED_LAYOUT = "UNSUPPORTED_LAYOUT"
        const val NO_TRANSACTIONS = "NO_TRANSACTIONS"
        const val FILE_NOT_FOUND = "FILE_NOT_FOUND"
        const val FILE_UNREADABLE = "FILE_UNREADABLE"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}

/** Minimal RFC 4180 reader (quoted fields, doubled quotes, quoted line breaks) with a configurable delimiter. */
object Csv {
    fun parse(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(c)
                    delimiter -> { row.add(field.toString()); field.setLength(0) }
                    '\r' -> {}
                    '\n' -> { row.add(field.toString()); field.setLength(0); records.add(row); row = ArrayList() }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); records.add(row)
        }
        return records
    }

    fun isBlank(record: List<String>) = record.all { it.isBlank() }
}

/** Header/label matching: strip diacritics, uppercase, collapse spaces. */
fun normLabel(s: String): String {
    val d = Normalizer.normalize(s.trim(), Normalizer.Form.NFKD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    return d.uppercase().replace(Regex("\\s+"), " ")
}

/**
 * Amount normalization: handles `1.234,56` and `1,234.56`, signs, and plain numbers. When only one kind of
 * separator appears and it could be a thousands separator (`1.234` / `1,234`), [decimalSeparator] (the
 * file's convention) decides.
 */
object Amounts {
    private val GROUPED = Regex("^[0-9]{1,3}([.,][0-9]{3})+$")

    fun parse(raw: String, decimalSeparator: Char): BigDecimal {
        var s = raw.trim().replace(" ", "").replace(" ", "")
        require(s.isNotEmpty()) { "empty amount" }
        var negative = false
        when {
            s.startsWith("-") -> { negative = true; s = s.substring(1) }
            s.startsWith("+") -> s = s.substring(1)
            s.endsWith("-") -> { negative = true; s = s.dropLast(1) }
        }
        require(s.isNotEmpty() && s.all { it.isDigit() || it == '.' || it == ',' }) { "not an amount" }
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 -> {
                val dec = if (lastDot > lastComma) '.' else ','
                val thousands = if (dec == '.') ',' else '.'
                s.replace(thousands.toString(), "").replace(dec, '.')
            }
            lastDot < 0 && lastComma < 0 -> s
            else -> {
                val sep = if (lastDot >= 0) '.' else ','
                val count = s.count { it == sep }
                val grouped = GROUPED.matches(s)
                if (count > 1 || (grouped && sep != decimalSeparator)) {
                    s.replace(sep.toString(), "") // thousands separators only
                } else {
                    s.replace(sep, '.')
                }
            }
        }
        require(normalized.count { it == '.' } <= 1) { "malformed amount" }
        val value = BigDecimal(normalized)
        require(value.scale() <= 2 || value.stripTrailingZeros().scale() <= 2) { "more than 2 decimals" }
        val scaled = value.setScale(2, RoundingMode.UNNECESSARY)
        return if (negative) scaled.negate() else scaled
    }

    /** Canonical contract string: exactly 2 fraction digits, '.', no thousands separators. */
    fun canonical(amount: BigDecimal): String = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
}
