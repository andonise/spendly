package dev.spendly.kafka

import dev.spendly.events.ConsumerNames
import dev.spendly.events.EventCodec
import dev.spendly.events.EventHeaders
import dev.spendly.events.EventTypes
import dev.spendly.events.StatementParseFailedPayload
import dev.spendly.events.StatementUploadedPayload
import dev.spendly.events.Topics
import dev.spendly.events.TransactionsImportedPayload
import dev.spendly.importing.ImportService
import dev.spendly.parsing.CsvParsingService
import dev.spendly.statement.StatementError
import dev.spendly.statement.StatementRepository
import dev.spendly.statement.StatementStateMachine
import dev.spendly.statement.StatementStatus
import dev.spendly.outbox.ProcessedEventsRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

private inline fun <T> withCorrelation(correlationId: String?, block: () -> T): T {
    if (correlationId != null) MDC.put("correlationId", correlationId)
    try {
        return block()
    } finally {
        MDC.remove("correlationId")
    }
}

/** Kotlin CSV parser stage: group `spendly-backend-csv-parser` on `spendly.statement-uploaded`. */
@Component
class CsvParserListener(private val codec: EventCodec, private val service: CsvParsingService) {

    @KafkaListener(id = "csvParser", topics = [Topics.STATEMENT_UPLOADED], groupId = ConsumerNames.CSV_PARSER)
    fun onStatementUploaded(record: ConsumerRecord<String, String>) {
        val env = codec.read(record.value(), EventTypes.STATEMENT_UPLOADED, StatementUploadedPayload::class)
        withCorrelation(env.correlationId) { service.handle(env) }
    }
}

/** Import handler: group `spendly-backend-import` on TransactionsImported and StatementParseFailed. */
@Component
class ImportListener(private val codec: EventCodec, private val service: ImportService) {

    @KafkaListener(
        id = "importer",
        topics = [Topics.TRANSACTIONS_IMPORTED, Topics.STATEMENT_PARSE_FAILED],
        groupId = ConsumerNames.IMPORT,
    )
    fun onMessage(record: ConsumerRecord<String, String>) {
        when (record.topic()) {
            Topics.TRANSACTIONS_IMPORTED -> {
                val env = codec.read(record.value(), EventTypes.TRANSACTIONS_IMPORTED, TransactionsImportedPayload::class)
                withCorrelation(env.correlationId) { service.importTransactions(env) }
            }
            Topics.STATEMENT_PARSE_FAILED -> {
                val env = codec.read(record.value(), EventTypes.STATEMENT_PARSE_FAILED, StatementParseFailedPayload::class)
                withCorrelation(env.correlationId) { service.parseFailed(env) }
            }
        }
    }
}

/**
 * DLQ consumer (group `spendly-backend-dlq`): moves the statement to FAILED when the message carries a known
 * statementId (only from UPLOADED: every ingestion topic precedes PARSED); otherwise it only logs the IDs.
 */
@Component
class DlqListener(
    private val jsonMapper: JsonMapper,
    private val statements: StatementRepository,
    private val stateMachine: StatementStateMachine,
    private val processed: ProcessedEventsRepository,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        id = "dlq",
        topics = [
            Topics.STATEMENT_UPLOADED + Topics.DLQ_SUFFIX,
            Topics.TRANSACTIONS_IMPORTED + Topics.DLQ_SUFFIX,
            Topics.STATEMENT_PARSE_FAILED + Topics.DLQ_SUFFIX,
            Topics.STATEMENT_PARSED + Topics.DLQ_SUFFIX,
            Topics.ANSWERS_SUBMITTED + Topics.DLQ_SUFFIX,
            Topics.TRANSACTIONS_CHANGED + Topics.DLQ_SUFFIX,
        ],
        groupId = ConsumerNames.DLQ,
        containerFactory = "dlqListenerContainerFactory",
    )
    fun onDeadLetter(record: ConsumerRecord<String, String>) {
        val reason = DlqRecoverer.headerValue(record, EventHeaders.DLQ_REASON) ?: "UNKNOWN"
        val source = DlqRecoverer.headerValue(record, EventHeaders.DLQ_SOURCE) ?: "unknown"
        val tree = try {
            record.value()?.let { jsonMapper.readTree(it) }
        } catch (e: Exception) {
            null
        }
        val eventId = tree?.path("eventId")?.asString(null)?.let(::uuidOrNull)
            ?: DlqRecoverer.headerValue(record, EventHeaders.EVENT_ID)?.let(::uuidOrNull)
        val statementId = tree?.path("payload")?.path("statementId")?.asString(null)?.let(::uuidOrNull)
        // Phase 3 categorization topics fail a statement that is PARSED or CATEGORIZING; ingestion topics one in UPLOADED.
        val phase3 = record.topic().removeSuffix(Topics.DLQ_SUFFIX) in setOf(Topics.STATEMENT_PARSED, Topics.ANSWERS_SUBMITTED)
        val failable = if (phase3) setOf(StatementStatus.PARSED, StatementStatus.CATEGORIZING) else setOf(StatementStatus.UPLOADED)
        if (eventId == null || statementId == null) {
            log.warn("DLQ message on {} without usable IDs (reason={}, source={}, eventId={}, offset={})", record.topic(), reason, source, eventId, record.offset())
            return
        }
        tx.executeWithoutResult {
            if (!processed.tryInsert(eventId, ConsumerNames.DLQ)) return@executeWithoutResult
            val statement = statements.lockById(statementId)
            when {
                statement == null ->
                    log.warn("DLQ message eventId={} (reason={}, source={}) refers to unknown statementId={}", eventId, reason, source, statementId)
                statement.status !in failable ->
                    log.warn("DLQ message eventId={} (reason={}) for statement {} ignored: status is {}", eventId, reason, statementId, statement.status)
                else -> {
                    stateMachine.transition(
                        statementId, statement.status, StatementStatus.FAILED,
                        error = StatementError("DLQ", "Processing failed ($reason, by $source); see logs for eventId $eventId", true),
                    )
                    log.warn("Statement {} FAILED from DLQ (eventId={}, reason={}, source={})", statementId, eventId, reason, source)
                }
            }
        }
    }

    private fun uuidOrNull(s: String): UUID? = try {
        UUID.fromString(s)
    } catch (e: IllegalArgumentException) {
        null
    }
}
