package dev.spendly.kafka

import dev.spendly.categorization.CategorizerService
import dev.spendly.categorization.ResumeService
import dev.spendly.events.AnswersSubmittedPayload
import dev.spendly.events.ConsumerNames
import dev.spendly.events.EventCodec
import dev.spendly.events.EventTypes
import dev.spendly.events.StatementParsedPayload
import dev.spendly.events.Topics
import dev.spendly.events.TransactionsChangedPayload
import dev.spendly.transfer.TransferMatcherService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private inline fun <T> correlated(correlationId: String?, block: () -> T): T {
    if (correlationId != null) MDC.put("correlationId", correlationId)
    try {
        return block()
    } finally {
        MDC.remove("correlationId")
    }
}

/**
 * Categorizer: group `spendly-backend-categorizer` on `spendly.statement-parsed`. One record per poll and a long
 * poll interval, because a statement can spend minutes in the local LLM.
 */
@Component
class CategorizerListener(private val codec: EventCodec, private val service: CategorizerService) {

    @KafkaListener(
        id = "categorizer", topics = [Topics.STATEMENT_PARSED], groupId = ConsumerNames.CATEGORIZER,
        properties = ["max.poll.records=1", "max.poll.interval.ms=1800000"],
    )
    fun onStatementParsed(record: ConsumerRecord<String, String>) {
        val env = codec.read(record.value(), EventTypes.STATEMENT_PARSED, StatementParsedPayload::class)
        correlated(env.correlationId) { service.handle(env) }
    }
}

/** Resume handler: group `spendly-backend-resume` on `spendly.answers-submitted`. */
@Component
class ResumeListener(private val codec: EventCodec, private val service: ResumeService) {

    @KafkaListener(
        id = "resume", topics = [Topics.ANSWERS_SUBMITTED], groupId = ConsumerNames.RESUME,
        properties = ["max.poll.records=1", "max.poll.interval.ms=1800000"],
    )
    fun onAnswersSubmitted(record: ConsumerRecord<String, String>) {
        val env = codec.read(record.value(), EventTypes.ANSWERS_SUBMITTED, AnswersSubmittedPayload::class)
        correlated(env.correlationId) { service.handle(env) }
    }
}

/** Transfer matcher: group `spendly-backend-transfer-matcher` on `spendly.transactions-changed`. */
@Component
class TransferMatcherListener(private val codec: EventCodec, private val service: TransferMatcherService) {

    @KafkaListener(id = "transferMatcher", topics = [Topics.TRANSACTIONS_CHANGED], groupId = ConsumerNames.TRANSFER_MATCHER)
    fun onTransactionsChanged(record: ConsumerRecord<String, String>) {
        val env = codec.read(record.value(), EventTypes.TRANSACTIONS_CHANGED, TransactionsChangedPayload::class)
        correlated(env.correlationId) { service.handle(env) }
    }
}
