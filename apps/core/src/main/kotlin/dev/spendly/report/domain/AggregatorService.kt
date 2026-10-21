package dev.spendly.report.domain

import dev.spendly.report.persistence.AggregateRepository
import dev.spendly.report.persistence.ReportRepository
import dev.spendly.shared.messaging.ConsumerNames
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.ReportRequestReason
import dev.spendly.shared.messaging.TransactionsChangedPayload
import dev.spendly.shared.outbox.DomainEvents
import dev.spendly.shared.outbox.ProcessedEventsRepository
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * Aggregator (group `spendly-backend-aggregator`, topic `spendly.transactions-changed`). One transaction per event
 * (plan section 7, "Rebuild aggregates"): processed_events + delete/insert `daily_aggregates` for the changed accounts
 * and dates (per-account advisory locks) + overlapping reports → STALE (version + 1) + ReportRequested for the ones
 * rebuilt eagerly (monthly, or a build in progress) + a monthly report for every month that got its first covered day.
 */
@Service
class AggregatorService(
    private val processed: ProcessedEventsRepository,
    private val aggregates: AggregateRepository,
    private val reports: ReportRepository,
    private val events: DomainEvents,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(env: EventEnvelope<TransactionsChangedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        if (processed.exists(eventId, CONSUMER)) {
            log.info("Duplicate TransactionsChanged eventId={} ignored by the aggregator", eventId)
            return
        }
        val range = DateRange(LocalDate.parse(env.payload.fromDate), LocalDate.parse(env.payload.toDate))
        val accountIds = env.payload.accountIds.map(UUID::fromString)
        tx.executeWithoutResult {
            if (!processed.tryInsert(eventId, CONSUMER)) return@executeWithoutResult
            val r = rebuild(accountIds, range, env.correlationId)
            log.info(
                "TransactionsChanged eventId={} (cause={}): {} aggregate row(s), {} report(s) stale, {} build(s) requested",
                eventId, env.payload.cause, r.rows, r.stale, r.requested,
            )
        }
    }

    data class Result(val rows: Int, val stale: Int, val requested: Int)

    /** The aggregator's work, in the caller's transaction (also used by the startup backfill). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun rebuild(accountIds: List<UUID>, range: DateRange, correlationId: String): Result {
        val ids = accountIds.distinct().sorted()
        ids.forEach(aggregates::lockAccount)
        val rows = ids.sumOf { aggregates.rebuild(it, range) }

        var requested = 0
        val stale = reports.markStale(ids, range)
        for (r in stale) {
            val reason = when {
                r.period.granularity == Granularity.MONTH -> ReportRequestReason.STALE_MONTHLY
                r.status == ReportStatus.PENDING -> ReportRequestReason.PENDING_REBUILD
                else -> continue // other granularities are rebuilt on their next view
            }
            request(r.id, r.version, r.period, r.scope, reason, correlationId)
            requested++
        }
        for (month in aggregates.coveredMonths(range)) {
            val period = Period.month(month)
            val id = reports.insertPending(period, AccountScope.all, requested = true) ?: continue
            events.reportRequested(id, 1, period.granularity.name, period.start, period.end, AccountScope.ALL, ReportRequestReason.MONTH_STARTED, correlationId)
            requested++
        }
        return Result(rows, stale.size, requested)
    }

    private fun request(id: UUID, version: Int, period: Period, scope: AccountScope, reason: ReportRequestReason, correlationId: String) {
        events.reportRequested(id, version, period.granularity.name, period.start, period.end, scope.key, reason, correlationId)
        reports.setRequested(id, version)
    }

    companion object {
        const val CONSUMER = ConsumerNames.AGGREGATOR
    }
}
