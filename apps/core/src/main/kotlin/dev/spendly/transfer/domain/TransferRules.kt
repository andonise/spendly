package dev.spendly.transfer.domain

import dev.spendly.transaction.MatchKeys
import dev.spendly.transaction.TransferReason
import dev.spendly.transaction.TransferStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

/** A transaction as seen by transfer detection. */
data class TransferTx(
    val id: UUID,
    val accountId: UUID,
    val bookingDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val amountBase: BigDecimal?,
    val status: TransferStatus,
    val reason: TransferReason?,
    val counterpartyToken: String?,
    val paired: Boolean = false,
) {
    override fun toString() = "TransferTx(id=$id, account=$accountId, date=$bookingDate, status=$status)"
}

enum class MatchMethod { IBAN, AMOUNT_DATE, USER }

data class ProposedPair(val outgoing: TransferTx, val incoming: TransferTx, val method: MatchMethod)

/** Step 3 of plan section 10: descriptions that look like a transfer between own accounts → CANDIDATE. */
object TransferPatterns {
    private val PATTERNS = listOf(
        "TOP UP", "TOPUP", "TRANSFER INTERN", "TRANSFER INTRE CONTURI", "TRANSFER PROPRIU", "TRANSFER CONT PROPRIU",
        "INTRE CONTURI PROPRII", "PLATA CARD CREDIT", "RAMBURSARE", "ALIMENTARE CONT", "ALIMENTARE CARD",
        "DEPUNERE ECONOMII", "CONT ECONOMII", "TRANSFER ECONOMII", "TO SAVINGS", "FROM SAVINGS", "SAVINGS DEPOSIT",
        "OWN ACCOUNT", "INTERNAL TRANSFER", "CARD REPAYMENT", "CREDIT CARD PAYMENT", "CREDIT CARD REPAYMENT",
    )

    fun matches(description: String): Boolean {
        val key = MatchKeys.of(description)
        return PATTERNS.any { MatchKeys.containsWords(key, it) }
    }
}

/**
 * Step 2 of plan section 10: pair matching. Pure and deterministic, so it is unit-tested directly.
 *
 * A pair is an outgoing (negative) and an incoming (positive) transaction that are both unpaired, in different
 * accounts, within ±[windowDays] days, with the same amount (exactly, in the same currency; amount_base within
 * ±[fxTolerance] across currencies), and at least one side already looks like a transfer (INTERNAL from an own
 * IBAN/own counterparty, or CANDIDATE). A side found by own-IBAN must point at the other side's account.
 * EXTERNAL transactions and user decisions never pair.
 *
 * Tie-break (greedy over all valid pairs, best first): both sides transfer-like, IBAN-directed, smaller date gap,
 * smaller relative amount gap, earlier outgoing date, then transaction IDs.
 */
class PairMatcher(private val windowDays: Long = 3, private val fxTolerance: BigDecimal = BigDecimal("0.02")) {

    fun transferLike(t: TransferTx) = !t.paired && (t.status == TransferStatus.CANDIDATE || t.status == TransferStatus.INTERNAL)

    fun eligible(t: TransferTx) = !t.paired && t.status != TransferStatus.EXTERNAL && t.reason != TransferReason.USER

    /** [accountTokens]: accountId → the account's iban_hash. */
    fun match(transactions: Collection<TransferTx>, accountTokens: Map<UUID, String>): List<ProposedPair> {
        val pool = transactions.filter(::eligible)
        val outs = pool.filter { it.amount.signum() < 0 }
        val ins = pool.filter { it.amount.signum() > 0 }
        data class Scored(val pair: ProposedPair, val both: Boolean, val directed: Boolean, val days: Long, val rel: BigDecimal)

        val scored = ArrayList<Scored>()
        for (o in outs) for (i in ins) {
            if (o.accountId == i.accountId) continue
            if (!transferLike(o) && !transferLike(i)) continue
            val days = abs(ChronoUnit.DAYS.between(o.bookingDate, i.bookingDate))
            if (days > windowDays) continue
            val rel = amountGap(o, i) ?: continue
            if (!directionOk(o, i, accountTokens) || !directionOk(i, o, accountTokens)) continue
            val directed = o.reason == TransferReason.OWN_IBAN || i.reason == TransferReason.OWN_IBAN
            val method = if (directed) MatchMethod.IBAN else MatchMethod.AMOUNT_DATE
            scored += Scored(ProposedPair(o, i, method), transferLike(o) && transferLike(i), directed, days, rel)
        }
        scored.sortWith(
            compareByDescending<Scored> { it.both }
                .thenByDescending { it.directed }
                .thenBy { it.days }
                .thenBy { it.rel }
                .thenBy { it.pair.outgoing.bookingDate }
                .thenBy { it.pair.outgoing.id.toString() }
                .thenBy { it.pair.incoming.id.toString() },
        )
        val used = HashSet<UUID>()
        val result = ArrayList<ProposedPair>()
        for (s in scored) {
            if (s.pair.outgoing.id in used || s.pair.incoming.id in used) continue
            used += s.pair.outgoing.id
            used += s.pair.incoming.id
            result += s.pair
        }
        return result
    }

    /** Relative amount gap, or null when the amounts don't match. */
    fun amountGap(o: TransferTx, i: TransferTx): BigDecimal? {
        if (o.currency == i.currency) {
            return if (o.amount.abs().compareTo(i.amount.abs()) == 0) BigDecimal.ZERO else null
        }
        val a = o.amountBase?.abs() ?: return null
        val b = i.amountBase?.abs() ?: return null
        val max = a.max(b)
        if (max.signum() == 0) return null
        val rel = a.subtract(b).abs().divide(max, 6, RoundingMode.HALF_UP)
        return if (rel <= fxTolerance) rel else null
    }

    /** A side detected by own IBAN names its target account: the other side must be in that account. */
    private fun directionOk(side: TransferTx, other: TransferTx, accountTokens: Map<UUID, String>): Boolean {
        if (side.reason != TransferReason.OWN_IBAN || side.counterpartyToken == null) return true
        return accountTokens[other.accountId] == side.counterpartyToken
    }
}

/** Coverage for AC-MA-4: does every other active account have CATEGORIZED statements covering date ±window? */
object TransferCoverage {
    data class AccountSpan(val accountId: UUID, val activeFrom: LocalDate, val closedAt: LocalDate?)

    fun covered(
        date: LocalDate,
        ownAccount: UUID,
        accounts: List<AccountSpan>,
        categorizedPeriods: Map<UUID, List<Pair<LocalDate, LocalDate>>>,
        windowDays: Long,
    ): Boolean {
        val from = date.minusDays(windowDays)
        val to = date.plusDays(windowDays)
        for (a in accounts) {
            if (a.accountId == ownAccount) continue
            val start = maxOf(from, a.activeFrom)
            val end = minOf(to, a.closedAt ?: to)
            if (end.isBefore(start)) continue // not active around that date
            val periods = categorizedPeriods[a.accountId].orEmpty()
            var d = start
            while (!d.isAfter(end)) {
                val day = d
                if (periods.none { (ps, pe) -> !day.isBefore(ps) && !day.isAfter(pe) }) return false
                d = d.plusDays(1)
            }
        }
        return true
    }
}
