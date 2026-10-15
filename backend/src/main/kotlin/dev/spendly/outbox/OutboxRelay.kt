package dev.spendly.outbox

import dev.spendly.config.SpendlyProperties
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes committed outbox rows to Kafka (at least once). Rows are locked with
 * `FOR UPDATE SKIP LOCKED`, sent synchronously in `seq` order, then marked `published_at`.
 * A crash between send and mark re-sends the row later; consumers dedupe on eventId.
 */
@Component
class OutboxRelay(
    private val repository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val transactionTemplate: TransactionTemplate,
    private val jsonMapper: JsonMapper,
    private val properties: SpendlyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Runtime switch (initially `spendly.outbox.relay-enabled`); tests use it to simulate a crash before publishing. */
    val enabled = AtomicBoolean(properties.outbox.relayEnabled)

    @Scheduled(fixedDelayString = "\${spendly.outbox.poll-interval:500ms}", initialDelayString = "\${spendly.outbox.poll-interval:500ms}")
    fun poll() {
        if (!enabled.get()) return
        try {
            while (publishPending() == properties.outbox.batchSize) {
                // keep draining full batches
            }
        } catch (e: Exception) {
            log.warn("Outbox relay run failed: {}", e.javaClass.simpleName)
        }
    }

    /** Publishes one batch; returns the number of rows published. Stops at the first send failure to keep order. */
    fun publishPending(): Int = transactionTemplate.execute {
        val rows = repository.lockUnpublished(properties.outbox.batchSize)
        var published = 0
        for (row in rows) {
            try {
                send(row)
                repository.markPublished(row.id)
                published++
            } catch (e: Exception) {
                log.warn("Outbox publish failed eventId={} topic={}: {}", row.id, row.topic, e.javaClass.simpleName)
                repository.markFailed(row.id, e.javaClass.simpleName)
                break
            }
        }
        published
    } ?: 0

    private fun send(row: OutboxRow) {
        val headers: Map<String, String> = jsonMapper.readValue(row.headers, Map::class.java)
            .entries.associate { (k, v) -> k.toString() to v.toString() }
        val record = ProducerRecord<String, String>(row.topic, null, row.messageKey, row.payload)
        headers.forEach { (k, v) -> record.headers().add(RecordHeader(k, v.toByteArray(StandardCharsets.UTF_8))) }
        kafkaTemplate.send(record).get(properties.outbox.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
        log.info("Published eventId={} topic={} key={}", row.id, row.topic, row.messageKey)
    }
}
