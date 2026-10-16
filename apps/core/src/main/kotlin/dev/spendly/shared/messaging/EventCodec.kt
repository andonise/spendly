package dev.spendly.shared.messaging

import kotlin.reflect.KClass
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Reads inbound envelopes (validate first, then bind) and writes outbound ones (bind, then validate). */
@Component
class EventCodec(private val jsonMapper: JsonMapper, private val validator: ContractSchemaValidator) {

    fun parseTree(value: String?): JsonNode {
        if (value.isNullOrBlank()) throw NonRetryableEventException(DlqReason.INVALID_JSON, "Empty message value")
        val tree = try {
            jsonMapper.readTree(value)
        } catch (e: JacksonException) {
            throw NonRetryableEventException(DlqReason.INVALID_JSON, "Message value is not valid JSON")
        }
        if (tree == null || !tree.isObject) throw NonRetryableEventException(DlqReason.INVALID_JSON, "Message value is not a JSON object")
        return tree
    }

    fun <T : Any> read(value: String?, expectedEventType: String, payloadType: KClass<T>): EventEnvelope<T> {
        val tree = parseTree(value)
        val eventType = tree.path("eventType").asString("")
        if (eventType != expectedEventType) {
            throw NonRetryableEventException(DlqReason.SCHEMA_VALIDATION, "Unexpected eventType on this topic")
        }
        validator.validate(eventType, tree)
        val payload = jsonMapper.treeToValue(tree.path("payload"), payloadType.java)
        return EventEnvelope(
            eventId = tree.path("eventId").asString(),
            eventType = eventType,
            schemaVersion = tree.path("schemaVersion").asInt(),
            occurredAt = tree.path("occurredAt").asString(),
            correlationId = tree.path("correlationId").asString(),
            producer = tree.path("producer").asString(),
            payload = payload,
        )
    }

    /** Serializes and validates an outbound envelope; an invalid one is a programming error. */
    fun write(envelope: EventEnvelope<*>): String {
        val tree: JsonNode = jsonMapper.valueToTree(envelope)
        try {
            validator.validate(envelope.eventType, tree)
        } catch (e: NonRetryableEventException) {
            throw IllegalStateException("Outbound ${envelope.eventType} violates its contract: ${e.message}")
        }
        return jsonMapper.writeValueAsString(tree)
    }
}
