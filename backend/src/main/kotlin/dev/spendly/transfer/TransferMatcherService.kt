package dev.spendly.transfer

import dev.spendly.config.SpendlyProperties
import dev.spendly.events.ConsumerNames
import dev.spendly.events.EventEnvelope
import dev.spendly.events.TransactionsChangedPayload
import dev.spendly.outbox.ProcessedEventsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

/**
 * Transfer matcher (group `spendly-backend-transfer-matcher`, topic `spendly.transactions-changed`).
 * One transaction: processed_events + pair matching for unpaired transactions around the changed dates
 * (this is what makes matching independent of upload order, AC-MA-3) + TRANSFER questions for candidates
 * whose ±window is now covered by every other active account (AC-MA-4). Re-running it is harmless.
 */
@Service
class TransferMatcherService(
    private val processed: ProcessedEventsRepository,
    private val transfers: TransferService,
    private val tx: TransactionTemplate,
    properties: SpendlyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val window = properties.transfers.dateWindowDays

    fun handle(env: EventEnvelope<TransactionsChangedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        if (processed.exists(eventId, CONSUMER)) {
            log.info("Duplicate TransactionsChanged eventId={} ignored", eventId)
            return
        }
        val from = LocalDate.parse(env.payload.fromDate).minusDays(window)
        val to = LocalDate.parse(env.payload.toDate).plusDays(window)
        tx.executeWithoutResult {
            if (!processed.tryInsert(eventId, CONSUMER)) return@executeWithoutResult
            val pairs = transfers.matchPairs(from, to, env.correlationId)
            val questions = transfers.createTransferQuestions()
            log.info("TransactionsChanged eventId={} (cause={}): {} pair(s), {} transfer question(s)", eventId, env.payload.cause, pairs, questions)
        }
    }

    companion object {
        const val CONSUMER = ConsumerNames.TRANSFER_MATCHER
    }
}
