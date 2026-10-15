package dev.spendly.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class OutboxRow(
    val id: UUID,
    val topic: String,
    val messageKey: String,
    val payload: String,
    val headers: String,
)

@Repository
class OutboxRepository(private val jdbc: JdbcClient) {

    fun insert(
        id: UUID,
        aggregateId: UUID,
        eventType: String,
        topic: String,
        key: String,
        payloadJson: String,
        headersJson: String,
    ) {
        jdbc.sql(
            """
            INSERT INTO outbox (id, aggregate_id, event_type, topic, message_key, payload, headers)
            VALUES (:id, :aggregateId, :eventType, :topic, :key, CAST(:payload AS jsonb), CAST(:headers AS jsonb))
            """.trimIndent(),
        )
            .param("id", id).param("aggregateId", aggregateId).param("eventType", eventType)
            .param("topic", topic).param("key", key).param("payload", payloadJson).param("headers", headersJson)
            .update()
    }

    /** Locks a batch of unpublished rows; concurrent relays skip locked rows (FOR UPDATE SKIP LOCKED). */
    fun lockUnpublished(limit: Int): List<OutboxRow> =
        jdbc.sql(
            """
            SELECT id, topic, message_key, payload::text AS payload, headers::text AS headers
            FROM outbox
            WHERE published_at IS NULL
            ORDER BY seq
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        ).param("limit", limit)
            .query { rs, _ ->
                OutboxRow(
                    id = rs.getObject("id", UUID::class.java),
                    topic = rs.getString("topic"),
                    messageKey = rs.getString("message_key"),
                    payload = rs.getString("payload"),
                    headers = rs.getString("headers"),
                )
            }.list()

    fun markPublished(id: UUID) {
        jdbc.sql("UPDATE outbox SET published_at = clock_timestamp(), attempts = attempts + 1 WHERE id = :id")
            .param("id", id).update()
    }

    fun markFailed(id: UUID, error: String) {
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
            .param("id", id).param("error", error.take(500)).update()
    }
}
