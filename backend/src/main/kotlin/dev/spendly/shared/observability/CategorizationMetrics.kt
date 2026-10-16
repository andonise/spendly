package dev.spendly.shared.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Micrometer metrics for categorization (plan Phase 3 / Phase 6). */
@Component
class CategorizationMetrics(private val registry: MeterRegistry) {
    private val llmTimer: Timer = Timer.builder("spendly.llm.call").description("LLM call latency").register(registry)
    private val embeddingTimer: Timer = Timer.builder("spendly.embedding.call").description("Embedding call latency").register(registry)

    fun categorized(source: String, count: Int = 1) {
        if (count > 0) Counter.builder("spendly.categorization.transactions").tag("layer", source).register(registry).increment(count.toDouble())
    }

    fun questionsCreated(kind: String, count: Int) {
        if (count > 0) Counter.builder("spendly.questions.created").tag("kind", kind).register(registry).increment(count.toDouble())
    }

    fun transfers(outcome: String, count: Int = 1) {
        if (count > 0) Counter.builder("spendly.transfers").tag("outcome", outcome).register(registry).increment(count.toDouble())
    }

    fun llmFailure() = Counter.builder("spendly.llm.failures").register(registry).increment()

    fun recordLlm(nanos: Long) = llmTimer.record(nanos, TimeUnit.NANOSECONDS)

    fun recordEmbedding(nanos: Long) = embeddingTimer.record(nanos, TimeUnit.NANOSECONDS)
}
