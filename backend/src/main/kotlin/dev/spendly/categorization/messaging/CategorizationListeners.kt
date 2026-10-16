package dev.spendly.categorization.messaging

import dev.spendly.categorization.domain.CategorizerService
import dev.spendly.categorization.domain.ResumeService
import dev.spendly.shared.messaging.correlated
import dev.spendly.shared.messaging.AnswersSubmittedPayload
import dev.spendly.shared.messaging.ConsumerNames
import dev.spendly.shared.messaging.EventCodec
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.StatementParsedPayload
import dev.spendly.shared.messaging.Topics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

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
