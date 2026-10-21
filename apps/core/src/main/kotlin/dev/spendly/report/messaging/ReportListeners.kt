package dev.spendly.report.messaging

import dev.spendly.report.domain.AggregatorService
import dev.spendly.report.domain.ReportService
import dev.spendly.shared.messaging.ConsumerNames
import dev.spendly.shared.messaging.DlqRecoverer
import dev.spendly.shared.messaging.EventCodec
import dev.spendly.shared.messaging.EventHeaders
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.ReportRequestedPayload
import dev.spendly.shared.messaging.Topics
import dev.spendly.shared.messaging.TransactionsChangedPayload
import dev.spendly.shared.messaging.correlated
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/** Aggregator: group `spendly-backend-aggregator` on `spendly.transactions-changed`. */
@Component
class AggregatorListener(private val codec: EventCodec, private val service: AggregatorService) {

    @KafkaListener(id = "aggregator", topics = [Topics.TRANSACTIONS_CHANGED], groupId = ConsumerNames.AGGREGATOR)
    fun onTransactionsChanged(record: ConsumerRecord<String, String>) {
        val env = codec.read(record.value(), EventTypes.TRANSACTIONS_CHANGED, TransactionsChangedPayload::class)
        correlated(env.correlationId) { service.handle(env) }
    }
}

/** Report builder: group `spendly-backend-report-builder` on `spendly.report-requested`. */
@Component
class ReportBuilderListener(private val codec: EventCodec, private val service: ReportService) {

    @KafkaListener(id = "reportBuilder", topics = [Topics.REPORT_REQUESTED], groupId = ConsumerNames.REPORT_BUILDER)
    fun onReportRequested(record: ConsumerRecord<String, String>) {
        val env = codec.read(record.value(), EventTypes.REPORT_REQUESTED, ReportRequestedPayload::class)
        correlated(env.correlationId) { service.handle(env) }
    }
}

/** Dead-lettered report builds (group `spendly-backend-report-dlq`): the report becomes FAILED; its next view rebuilds it. */
@Component
class ReportDlqListener(private val codec: EventCodec, private val service: ReportService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        id = "reportDlq", topics = [Topics.REPORT_REQUESTED + Topics.DLQ_SUFFIX], groupId = ConsumerNames.REPORT_DLQ,
        containerFactory = "dlqListenerContainerFactory",
    )
    fun onDeadLetter(record: ConsumerRecord<String, String>) {
        val reason = DlqRecoverer.headerValue(record, EventHeaders.DLQ_REASON) ?: "UNKNOWN"
        val env = try {
            codec.read(record.value(), EventTypes.REPORT_REQUESTED, ReportRequestedPayload::class)
        } catch (e: Exception) {
            log.warn("Unreadable report DLQ message (reason={}, offset={}): {}", reason, record.offset(), e.javaClass.simpleName)
            return
        }
        correlated(env.correlationId) { service.failed(env, reason) }
    }
}
