package dev.spendly.shared.messaging

import org.slf4j.MDC

/** Runs [block] with the event's correlation ID in the logging MDC (Kafka listeners). */
inline fun <T> correlated(correlationId: String?, block: () -> T): T {
    if (correlationId != null) MDC.put("correlationId", correlationId)
    try {
        return block()
    } finally {
        MDC.remove("correlationId")
    }
}
