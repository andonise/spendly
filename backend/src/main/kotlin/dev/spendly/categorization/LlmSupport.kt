package dev.spendly.categorization

import dev.spendly.config.SpendlyProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime LLM switch (initially `spendly.llm.enabled`, default true; AC-LLM-1). A Settings page can flip it. */
@Component
class LlmMode(properties: SpendlyProperties) {
    private val enabled = AtomicBoolean(properties.llm.enabled)

    fun isEnabled(): Boolean = enabled.get()

    fun set(value: Boolean) = enabled.set(value)
}

/** Micrometer metrics for categorization (plan Phase 3 / Phase 6). */
@Component
class CategorizationMetrics(private val registry: MeterRegistry) {
    private val llmTimer: Timer = Timer.builder("spendly.llm.call").description("LLM call latency").register(registry)
    private val embeddingTimer: Timer = Timer.builder("spendly.embedding.call").description("Embedding call latency").register(registry)

    fun categorized(source: CategorySource, count: Int = 1) {
        if (count > 0) Counter.builder("spendly.categorization.transactions").tag("layer", source.name).register(registry).increment(count.toDouble())
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

class LlmUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The only way the backend calls the chat model. Calls are serialized (one at a time, fair queue), run on the
 * caller's thread (never inside a DB transaction, enforced here), time-boxed by the HTTP read timeout
 * (`spring.http.clients.read-timeout`) and retried; after the last attempt it throws [LlmUnavailableException]
 * so callers can fall back to the non-LLM path. Prompts and responses are never logged.
 */
@Component
class LlmGateway(
    chatModel: ChatModel,
    private val properties: SpendlyProperties,
    private val metrics: CategorizationMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: ChatClient = ChatClient.builder(chatModel).build()
    private val permits = Semaphore(1, true)

    /** Low temperature, thinking disabled (qwen3 is a hybrid thinking model). */
    fun categorizationOptions(): OllamaChatOptions.Builder = OllamaChatOptions.builder()
        .model(properties.llm.model)
        .temperature(properties.llm.temperature)
        .disableThinking()

    fun <T : Any> entity(system: String, user: String, type: Class<T>): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "LLM call inside a DB transaction" }
        var last: Exception? = null
        for (attempt in 1..properties.llm.maxAttempts.coerceAtLeast(1)) {
            permits.acquire()
            val started = System.nanoTime()
            try {
                val spec = client.prompt().system(system).user(user).options(categorizationOptions()).call()
                val result = if (properties.llm.providerStructuredOutput) {
                    spec.entity(type) { it.useProviderStructuredOutput() }
                } else {
                    spec.entity(type)
                }
                return result ?: throw IllegalStateException("empty LLM response")
            } catch (e: Exception) {
                last = e
                metrics.llmFailure()
                log.warn("LLM call failed (attempt {}/{}): {}", attempt, properties.llm.maxAttempts, e.javaClass.simpleName)
            } finally {
                metrics.recordLlm(System.nanoTime() - started)
                permits.release()
            }
            if (attempt < properties.llm.maxAttempts) Thread.sleep(properties.llm.retryBackoff.toMillis() * attempt)
        }
        throw LlmUnavailableException("LLM unavailable after ${properties.llm.maxAttempts} attempts", last)
    }
}

/**
 * Embeddings (nomic-embed-text, 768 dims) with retry; returns null when the model is unreachable, so callers
 * fall back to rules + questions. Never inside a DB transaction.
 */
@Component
class EmbeddingGateway(
    private val embeddingModel: EmbeddingModel,
    private val properties: SpendlyProperties,
    private val metrics: CategorizationMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val permits = Semaphore(1, true)

    /** nomic-embed-text task prefix for symmetric similarity between short merchant texts. */
    fun textFor(merchantText: String) = "classification: $merchantText"

    fun embed(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Embedding call inside a DB transaction" }
        for (attempt in 1..properties.llm.maxAttempts.coerceAtLeast(1)) {
            permits.acquire()
            val started = System.nanoTime()
            try {
                val out = embeddingModel.embed(texts.map(::textFor))
                check(out.size == texts.size) { "embedding count mismatch" }
                return out
            } catch (e: Exception) {
                log.warn("Embedding call failed (attempt {}): {}", attempt, e.javaClass.simpleName)
            } finally {
                metrics.recordEmbedding(System.nanoTime() - started)
                permits.release()
            }
            if (attempt < properties.llm.maxAttempts) Thread.sleep(properties.llm.retryBackoff.toMillis() * attempt)
        }
        return null
    }

    companion object {
        fun literal(v: FloatArray): String = v.joinToString(",", "[", "]")
    }
}
