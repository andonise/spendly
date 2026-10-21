package dev.spendly.report.domain

import dev.spendly.report.persistence.AggregateRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Statements categorized before Phase 4 never produced aggregates. On startup, when `daily_aggregates` is empty but
 * CATEGORIZED statements exist, run the aggregator once per account over its whole span (this also creates the
 * monthly reports). Afterwards TransactionsChanged keeps the aggregates current.
 */
@Component
class AggregateBackfill(
    private val aggregates: AggregateRepository,
    private val aggregator: AggregatorService,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun backfill() {
        if (!aggregates.isEmpty()) return
        val spans = aggregates.categorizedSpans()
        if (spans.isEmpty()) return
        spans.forEach { (accountId, range) ->
            tx.executeWithoutResult { aggregator.rebuild(listOf(accountId), range, "backfill-$accountId") }
        }
        log.info("Backfilled daily aggregates for {} account(s)", spans.size)
    }
}
