package dev.spendly.support

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager

/** One transaction line of a categorization prompt: `N | direction | amount | description`. */
data class PromptRow(val id: Int, val direction: String, val description: String)

/**
 * Recording, scriptable stand-in for the Ollama chat model (normal tests never call the real Ollama).
 * - [prompts] records the full text of every prompt (AC-SD-2 recording interceptor);
 * - [transactionActiveDuringCall] flips if a call ever happens inside a DB transaction;
 * - [responder] decides each row's answer (default: keyword table, confidence 0.95, else OTHER 0.9);
 * - [down] simulates Ollama being unreachable; [onCall] runs inside the call (e.g. change a status meanwhile).
 */
class ScriptedChatModel : ChatModel {
    val prompts = CopyOnWriteArrayList<String>()
    val calls = AtomicInteger()
    val transactionActiveDuringCall = AtomicBoolean(false)
    @Volatile var down = false
    @Volatile var onCall: (() -> Unit)? = null
    @Volatile var responder: (PromptRow) -> Answer = ::defaultAnswer

    data class Answer(val category: String?, val confidence: Double, val question: String? = null, val suggestions: List<String> = emptyList())

    fun reset() {
        prompts.clear()
        calls.set(0)
        transactionActiveDuringCall.set(false)
        down = false
        onCall = null
        responder = ::defaultAnswer
    }

    override fun call(prompt: Prompt): ChatResponse {
        if (TransactionSynchronizationManager.isActualTransactionActive()) transactionActiveDuringCall.set(true)
        val text = prompt.instructions.joinToString("\n") { it.text ?: "" }
        prompts += text
        calls.incrementAndGet()
        onCall?.invoke()
        if (down) throw org.springframework.web.client.ResourceAccessException("Connection refused (test: Ollama down)")
        val rows = parseRows(text)
        val results = rows.joinToString(",") { r ->
            val a = responder(r)
            val cat = a.category?.let { "\"$it\"" } ?: "null"
            val q = a.question?.let { "\"${it.replace("\"", "'")}\"" } ?: "null"
            """{"id":${r.id},"category":$cat,"confidence":${a.confidence},"question":$q,"suggestions":[${a.suggestions.joinToString(",") { "\"$it\"" }}]}"""
        }
        return ChatResponse(listOf(Generation(AssistantMessage("""{"results":[$results]}"""))))
    }

    companion object {
        private val ROW = Regex("^([0-9]+) \\| (out|in|unknown) \\| [^|]+ \\| (.*)$")

        fun parseRows(text: String): List<PromptRow> = text.lines().mapNotNull { line ->
            ROW.matchEntire(line.trim())?.let { m -> PromptRow(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3]) }
        }

        private val KEYWORDS = listOf(
            "NETFLIX" to "SUBSCRIPTIONS", "SPOTIFY" to "SUBSCRIPTIONS", "ENEL" to "UTILITIES", "ORANGE" to "UTILITIES",
            "LIBRARIA" to "SHOPPING", "LIBRĂRIA" to "SHOPPING", "EMAG" to "SHOPPING", "TRATTORIA" to "RESTAURANTS",
            "ALTEX" to "SHOPPING", "DECATHLON" to "SHOPPING", "SALARIU" to "INCOME", "TRANSFER DE LA" to "INCOME",
            "TOP-UP" to "OTHER", "BOLT" to "TRANSPORT", "MEGA IMAGE" to "GROCERIES", "KAUFLAND" to "GROCERIES",
        )

        fun defaultAnswer(row: PromptRow): Answer {
            val upper = row.description.uppercase()
            val hit = KEYWORDS.firstOrNull { upper.contains(it.first) }
            return if (hit != null) Answer(hit.second, 0.95) else Answer("OTHER", 0.9)
        }
    }
}

/**
 * Deterministic stand-in for nomic-embed-text: hashed character trigrams of the text (prefix removed), L2-normalized,
 * 768 dimensions. Equal texts → similarity 1; similar texts → high similarity. [down] simulates an outage.
 */
class HashEmbeddingModel : EmbeddingModel {
    @Volatile var down = false
    val calls = AtomicInteger()
    val transactionActiveDuringCall = AtomicBoolean(false)

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        if (TransactionSynchronizationManager.isActualTransactionActive()) transactionActiveDuringCall.set(true)
        calls.incrementAndGet()
        if (down) throw org.springframework.web.client.ResourceAccessException("Connection refused (test: embeddings down)")
        return EmbeddingResponse(request.instructions.mapIndexed { i, t -> Embedding(vector(t), i) })
    }

    override fun embed(document: Document): FloatArray = vector(document.text ?: "")

    override fun dimensions(): Int = 768

    companion object {
        fun vector(raw: String): FloatArray {
            val text = " " + raw.substringAfter(": ").lowercase().filter { it.isLetterOrDigit() || it == ' ' } + " "
            val v = FloatArray(768)
            for (i in 0 until text.length - 2) {
                val h = Math.floorMod(text.substring(i, i + 3).hashCode(), 768)
                v[h] += 1f
            }
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
            for (i in v.indices) v[i] /= norm
            return v
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestAiConfiguration {
    @Bean
    @Primary
    fun scriptedChatModel(): ScriptedChatModel = ScriptedChatModel()

    @Bean
    @Primary
    fun hashEmbeddingModel(): HashEmbeddingModel = HashEmbeddingModel()
}
