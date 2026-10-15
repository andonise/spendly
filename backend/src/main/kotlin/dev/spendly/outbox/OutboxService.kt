package dev.spendly.outbox

import dev.spendly.events.EventCodec
import dev.spendly.events.EventEnvelope
import dev.spendly.events.EventHeaders
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Writes an event to the outbox in the caller's DB transaction (transactional outbox, plan section 7).
 * It is published to Kafka only after that transaction commits, by [OutboxRelay].
 */
@Service
class OutboxService(
    private val repository: OutboxRepository,
    private val codec: EventCodec,
    private val jsonMapper: JsonMapper,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun append(envelope: EventEnvelope<*>, topic: String, key: String, aggregateId: UUID) {
        val value = codec.write(envelope)
        val headers = mapOf(
            EventHeaders.EVENT_ID to envelope.eventId,
            EventHeaders.EVENT_TYPE to envelope.eventType,
            EventHeaders.SCHEMA_VERSION to envelope.schemaVersion.toString(),
            EventHeaders.CORRELATION_ID to envelope.correlationId,
        )
        repository.insert(
            id = UUID.fromString(envelope.eventId),
            aggregateId = aggregateId,
            eventType = envelope.eventType,
            topic = topic,
            key = key,
            payloadJson = value,
            headersJson = jsonMapper.writeValueAsString(headers),
        )
    }
}
