package dev.spendly.categorization.domain

import dev.spendly.categorization.persistence.CategorizationStore
import dev.spendly.shared.messaging.ChangeCause
import dev.spendly.shared.messaging.ConsumerNames
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.StatementParsedPayload
import dev.spendly.shared.observability.CategorizationMetrics
import dev.spendly.shared.outbox.DomainEvents
import dev.spendly.shared.outbox.ProcessedEventsRepository
import dev.spendly.statement.ProcessingStep
import dev.spendly.statement.StatementLifecycle
import dev.spendly.statement.StatementRecord
import dev.spendly.statement.StatementStatus
import dev.spendly.transfer.TransferApi
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Categorizer (group `spendly-backend-categorizer`, topic `spendly.statement-parsed`).
 *
 * 1. Short transaction: guarded PARSED → CATEGORIZING, transfer detection (own IBAN, own counterparty, labeled
 *    third parties, patterns, pair matching) and INTERNAL_TRANSFER for INTERNAL rows.
 * 2. No transaction: read inputs → rules → embeddings → LLM ([CategorizationEngine]).
 * 3. Short transaction, saved only if the statement is still CATEGORIZING (else the result is discarded):
 *    categories + questions + NEEDS_INPUT/InputRequired or CATEGORIZED/TransactionsChanged + processed_events.
 *
 * processed_events is written in step 3, so a crash during the LLM call is redelivered and resumes from
 * CATEGORIZING instead of leaving the statement stuck.
 */
@Service
class CategorizerService(
    private val statements: StatementLifecycle,
    private val processed: ProcessedEventsRepository,
    private val transfers: TransferApi,
    private val store: CategorizationStore,
    private val engine: CategorizationEngine,
    private val events: DomainEvents,
    private val metrics: CategorizationMetrics,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(env: EventEnvelope<StatementParsedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        val statementId = UUID.fromString(env.payload.statementId)
        if (processed.exists(eventId, CONSUMER)) {
            log.info("Duplicate StatementParsed eventId={} statementId={} ignored", eventId, statementId)
            return
        }
        val statement = tx.execute { start(eventId, statementId) } ?: return
        categorize(statement, env.correlationId, eventId)
    }

    /** Step 1. Returns the statement to categorize, or null when there is nothing to do. */
    private fun start(eventId: UUID, statementId: UUID): StatementRecord? {
        val s = statements.lock(statementId)
        if (s == null) {
            log.warn("StatementParsed eventId={} refers to unknown statementId={}; skipped", eventId, statementId)
            processed.tryInsert(eventId, CONSUMER)
            return null
        }
        when (s.status) {
            StatementStatus.PARSED -> statements.transition(statementId, StatementStatus.PARSED, StatementStatus.CATEGORIZING)
            StatementStatus.CATEGORIZING -> log.info("Statement {} already CATEGORIZING; resuming", statementId)
            else -> {
                log.info("StatementParsed eventId={} for statement {} ignored: status is {}", eventId, statementId, s.status)
                processed.tryInsert(eventId, CONSUMER)
                return null
            }
        }
        transfers.detectForStatement(statementId, s.periodStart!!, s.periodEnd!!, s.correlationId)
        statements.updateStep(statementId, StatementStatus.CATEGORIZING, ProcessingStep.TRANSFER_DETECTION)
        return s
    }

    /** Steps 2 and 3; also used directly by tests. */
    fun categorize(statement: StatementRecord, correlationId: String, eventId: UUID?) {
        val inputs = store.inputsFor(statement.id)
        val result = engine.categorize(inputs)
        val saved = tx.execute { save(statement, result, correlationId, eventId) } ?: false
        if (saved) {
            result.decisions.filter { it.category != null && it.source != null }.groupBy { it.source!! }
                .forEach { (source, list) -> metrics.categorized(source.name, list.size) }
        }
    }

    private fun save(statement: StatementRecord, result: EngineResult, correlationId: String, eventId: UUID?): Boolean {
        if (eventId != null && !processed.tryInsert(eventId, CONSUMER)) {
            log.info("Categorization result for statement {} discarded: event already processed", statement.id)
            return false
        }
        val current = statements.lock(statement.id)
        if (current == null || current.status != StatementStatus.CATEGORIZING) {
            log.info("Categorization result for statement {} discarded: status is now {}", statement.id, current?.status)
            return false
        }
        store.applyDecisions(result.decisions)
        val created = store.createQuestions(statement.id, result.decisions)
        metrics.questionsCreated("CATEGORY", created)
        transfers.markInternalCategories(statement.id)
        finish(current, correlationId, ProcessingStep.CATEGORIZE)
        return true
    }

    /**
     * Ends a CATEGORIZING run (also used by the resume handler): open questions → NEEDS_INPUT + InputRequired,
     * else CATEGORIZED + TransactionsChanged for the statement's period. Then TRANSFER questions whose coverage
     * is now complete.
     */
    fun finish(statement: StatementRecord, correlationId: String, step: ProcessingStep) {
        val open = store.openQuestionCount(statement.id)
        if (open > 0) {
            statements.transition(statement.id, StatementStatus.CATEGORIZING, StatementStatus.NEEDS_INPUT, step)
            events.inputRequired(statement.id, statement.accountId, open, correlationId)
        } else {
            statements.transition(statement.id, StatementStatus.CATEGORIZING, StatementStatus.CATEGORIZED, step)
            events.transactionsChanged(
                listOf(statement.accountId), statement.periodStart!!, statement.periodEnd!!,
                ChangeCause.STATEMENT_CATEGORIZED, statement.id, correlationId,
            )
        }
        transfers.createTransferQuestions()
    }

    companion object {
        const val CONSUMER = ConsumerNames.CATEGORIZER
    }
}
