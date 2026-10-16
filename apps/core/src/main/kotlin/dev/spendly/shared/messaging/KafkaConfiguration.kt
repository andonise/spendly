package dev.spendly.shared.messaging

import dev.spendly.shared.config.SpendlyProperties
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff

/**
 * Retries with backoff (initial attempt + 3 retries after 1 s, 2 s, 4 s), then the `<topic>.dlq` topic.
 * Invalid messages (bad JSON, schema violations, unsafe paths) skip the retries and go straight to the DLQ.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

    @Bean
    fun dlqRecoverer(kafkaTemplate: KafkaTemplate<String, String>): DlqRecoverer = DlqRecoverer(kafkaTemplate)

    /** Picked up by Boot's default listener container factory. */
    @Bean
    fun kafkaErrorHandler(recoverer: DlqRecoverer, properties: SpendlyProperties): DefaultErrorHandler {
        val k = properties.kafka
        val backOff = ExponentialBackOff(k.retryInitialInterval.toMillis(), k.retryMultiplier).apply {
            maxAttempts = k.retryMaxAttempts.toLong()
        }
        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(NonRetryableEventException::class.java)
            setCommitRecovered(true)
        }
    }

    /** The DLQ consumer never re-dead-letters: it logs and moves on after two quick retries. */
    @Bean
    fun dlqListenerContainerFactory(consumerFactory: ConsumerFactory<String, String>): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory)
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            setCommonErrorHandler(DefaultErrorHandler(FixedBackOff(1000, 2)))
        }
}

/** Publishes the original message unchanged to `<topic>.dlq`, adding dlqReason/dlqSource/dlqFailedAt (no PII). */
class DlqRecoverer(private val kafkaTemplate: KafkaTemplate<String, String>) : ConsumerRecordRecoverer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun accept(record: ConsumerRecord<*, *>, exception: Exception) {
        val reason = reasonFor(exception)
        val dlqTopic = record.topic() + Topics.DLQ_SUFFIX
        val out = ProducerRecord<String, String>(dlqTopic, null, record.key()?.toString(), record.value()?.toString())
        record.headers().forEach { h ->
            if (h.key() !in DLQ_HEADERS) out.headers().add(RecordHeader(h.key(), h.value()))
        }
        out.headers().add(header(EventHeaders.DLQ_REASON, reason.name))
        out.headers().add(header(EventHeaders.DLQ_SOURCE, Producers.BACKEND))
        out.headers().add(header(EventHeaders.DLQ_FAILED_AT, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
        kafkaTemplate.send(out).get(10, TimeUnit.SECONDS)
        log.warn(
            "Sent message to {} (reason={}, topic={}, partition={}, offset={}, eventId={}, cause={})",
            dlqTopic, reason, record.topic(), record.partition(), record.offset(), headerValue(record, EventHeaders.EVENT_ID),
            rootCause(exception).javaClass.simpleName,
        )
    }

    companion object {
        private val DLQ_HEADERS = setOf(EventHeaders.DLQ_REASON, EventHeaders.DLQ_SOURCE, EventHeaders.DLQ_FAILED_AT)

        fun reasonFor(exception: Throwable): DlqReason {
            var e: Throwable? = exception
            while (e != null) {
                if (e is NonRetryableEventException) return e.reason
                e = e.cause
            }
            return DlqReason.RETRIES_EXHAUSTED
        }

        private fun rootCause(e: Throwable): Throwable {
            var c = e
            while (c.cause != null && c.cause !== c) c = c.cause!!
            return c
        }

        private fun header(key: String, value: String) = RecordHeader(key, value.toByteArray(StandardCharsets.UTF_8))

        fun headerValue(record: ConsumerRecord<*, *>, key: String): String? =
            record.headers().lastHeader(key)?.value()?.toString(StandardCharsets.UTF_8)
    }
}
