package dev.spendly.report.domain

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

enum class CoverageStatus { COMPLETE, PARTIAL }

/** The accounts a report covers: every account (the combined view) or a sorted set of IDs. */
data class AccountScope(val accountIds: List<UUID>?) {
    val isAll: Boolean get() = accountIds == null

    /** `reports.account_scope`: ALL or the sorted, comma-separated IDs. */
    val key: String get() = accountIds?.joinToString(",") ?: ALL

    fun includes(id: UUID) = accountIds == null || id in accountIds

    companion object {
        const val ALL = "ALL"
        val all = AccountScope(null)

        /** IDs sorted by their text form, so the key reads in the same order as it is stored. */
        fun of(ids: Collection<UUID>?): AccountScope = if (ids.isNullOrEmpty()) all else AccountScope(ids.distinct().sortedBy(UUID::toString))

        fun parse(key: String): AccountScope = if (key == ALL) all else AccountScope(key.split(',').map(UUID::fromString))
    }
}

data class AccountInfo(val id: UUID, val label: String, val type: String, val activeFrom: LocalDate, val closedAt: LocalDate?) {
    /** The days of [range] the account was active, or null. */
    fun activeIn(range: DateRange): DateRange? {
        val from = maxOf(range.from, activeFrom)
        val to = closedAt?.let { minOf(range.to, it) } ?: range.to
        return if (to.isBefore(from)) null else DateRange(from, to)
    }
}

data class Unconfirmed(val count: Long, val amountBase: BigDecimal) {
    companion object {
        val NONE = Unconfirmed(0, BigDecimal.ZERO)
    }
}

data class AccountCoverage(val account: AccountInfo, val gaps: List<DateRange>) {
    val status: CoverageStatus get() = if (gaps.isEmpty()) CoverageStatus.COMPLETE else CoverageStatus.PARTIAL
}

data class Coverage(val range: DateRange, val accounts: List<AccountCoverage>, val unconfirmed: Unconfirmed) {
    /** COMPLETE when every active account covers every day and no transfer is unconfirmed (plan section 5). */
    val status: CoverageStatus
        get() = if (accounts.all { it.gaps.isEmpty() } && unconfirmed.count == 0L) CoverageStatus.COMPLETE else CoverageStatus.PARTIAL
}

/**
 * Days are the unit of completeness: a day is covered for an account when a CATEGORIZED statement of that account
 * includes it. Pure function so it is unit-tested without a database.
 */
object CoverageCalculator {

    fun compute(range: DateRange, accounts: List<AccountInfo>, statements: Map<UUID, List<DateRange>>, unconfirmed: Unconfirmed): Coverage {
        val result = accounts.mapNotNull { account ->
            val active = account.activeIn(range) ?: return@mapNotNull null
            AccountCoverage(account, subtract(active, statements[account.id].orEmpty()))
        }
        return Coverage(range, result, unconfirmed)
    }

    /** [range] minus the union of [covered], as sorted, non-overlapping ranges. */
    fun subtract(range: DateRange, covered: List<DateRange>): List<DateRange> {
        val gaps = ArrayList<DateRange>()
        var cursor = range.from
        for (c in merge(covered.filter { it.overlaps(range) })) {
            if (c.from.isAfter(cursor)) gaps += DateRange(cursor, minOf(c.from.minusDays(1), range.to))
            if (!c.to.isBefore(cursor)) cursor = c.to.plusDays(1)
            if (cursor.isAfter(range.to)) return gaps
        }
        if (!cursor.isAfter(range.to)) gaps += DateRange(cursor, range.to)
        return gaps
    }

    /** Sorts and merges overlapping or adjacent ranges. */
    fun merge(ranges: List<DateRange>): List<DateRange> {
        val out = ArrayList<DateRange>()
        for (r in ranges.sortedBy { it.from }) {
            val last = out.lastOrNull()
            if (last != null && !r.from.isAfter(last.to.plusDays(1))) {
                out[out.lastIndex] = DateRange(last.from, maxOf(last.to, r.to))
            } else {
                out += r
            }
        }
        return out
    }

    /** Hash of the accounts a report depends on: changes when one is added, closed or re-dated. */
    fun fingerprint(accounts: List<AccountInfo>): String {
        val text = accounts.sortedBy { it.id.toString() }.joinToString("|") { "${it.id}:${it.activeFrom}:${it.closedAt}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
