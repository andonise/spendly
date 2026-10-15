package dev.spendly.statement

import dev.spendly.config.SpendlyProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * SSE fan-out for statement status. Status changes are application events published inside the
 * transaction that made them (HTTP requests and Kafka consumers alike) and delivered after commit,
 * so clients never see a status that was rolled back.
 *
 * Wire format (contracts/openapi-draft.yaml):
 * ```
 * event: status
 * id: <monotonic sequence>
 * data: {"statementId":"…","status":"PARSED","lastCompletedStep":"PARSE","error":null,"occurredAt":"…"}
 * ```
 */
@Service
class StatementEventsService(
    private val repository: StatementRepository,
    private val jsonMapper: JsonMapper,
    private val properties: SpendlyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sequence = AtomicLong()
    private val emitters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()

    data class StatusEventData(
        val statementId: String,
        val status: StatementStatus,
        val lastCompletedStep: ProcessingStep?,
        val error: StatementError?,
        val occurredAt: String,
    )

    /** Registers first, then sends the current status, so no transition in between is lost. */
    fun subscribe(statement: StatementRecord): SseEmitter {
        val emitter = SseEmitter(properties.sse.emitterTimeout.toMillis())
        val list = emitters.computeIfAbsent(statement.id) { CopyOnWriteArrayList() }
        list += emitter
        val remove = { list.remove(emitter); Unit }
        emitter.onCompletion(remove)
        emitter.onTimeout { remove(); emitter.complete() }
        emitter.onError { remove() }

        val current = repository.findById(statement.id) ?: statement
        send(
            emitter,
            StatusEventData(current.id.toString(), current.status, current.lastCompletedStep, current.error, current.updatedAt.toString()),
        )
        if (current.status.terminal) emitter.complete()
        return emitter
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onStatusChanged(event: StatementStatusChanged) {
        val list = emitters[event.statementId] ?: return
        val data = StatusEventData(
            event.statementId.toString(), event.status, event.lastCompletedStep, event.error, event.occurredAt.toString(),
        )
        for (emitter in list) {
            if (send(emitter, data) && event.status.terminal) emitter.complete()
        }
    }

    @Scheduled(fixedRateString = "\${spendly.sse.keep-alive:15s}", initialDelayString = "\${spendly.sse.keep-alive:15s}")
    fun keepAlive() {
        emitters.values.forEach { list ->
            list.forEach { emitter ->
                try {
                    synchronized(emitter) { emitter.send(SseEmitter.event().comment("keep-alive")) }
                } catch (e: Exception) {
                    list.remove(emitter)
                }
            }
        }
        emitters.entries.removeIf { it.value.isEmpty() }
    }

    fun subscriberCount(statementId: UUID): Int = emitters[statementId]?.size ?: 0

    private fun send(emitter: SseEmitter, data: StatusEventData): Boolean = try {
        synchronized(emitter) {
            emitter.send(
                SseEmitter.event()
                    .name("status")
                    .id(sequence.incrementAndGet().toString())
                    .data(jsonMapper.writeValueAsString(data)),
            )
        }
        true
    } catch (e: IOException) {
        log.debug("SSE client gone for statement {}", data.statementId)
        emitters[UUID.fromString(data.statementId)]?.remove(emitter)
        false
    } catch (e: IllegalStateException) {
        emitters[UUID.fromString(data.statementId)]?.remove(emitter)
        false
    }
}
