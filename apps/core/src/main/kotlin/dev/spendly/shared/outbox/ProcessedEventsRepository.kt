package dev.spendly.shared.outbox

import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Consumer idempotency: `(event_id, consumer)` is inserted in the same transaction as the consumer's effect. */
@Repository
class ProcessedEventsRepository(private val jdbc: JdbcClient) {

    fun exists(eventId: UUID, consumer: String): Boolean =
        jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id AND consumer = :consumer")
            .param("id", eventId).param("consumer", consumer)
            .query(Long::class.java).single() > 0

    /** Returns false when the event was already recorded (duplicate delivery). */
    fun tryInsert(eventId: UUID, consumer: String): Boolean =
        jdbc.sql(
            "INSERT INTO processed_events (event_id, consumer) VALUES (:id, :consumer) ON CONFLICT DO NOTHING",
        ).param("id", eventId).param("consumer", consumer).update() == 1
}
