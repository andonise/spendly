package dev.spendly.categorization

import com.fasterxml.jackson.annotation.JsonProperty
import dev.spendly.config.SpendlyProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/** One transaction to categorize. [amount] null = unknown sign (evaluation rows). */
data class TxInput(
    val id: UUID,
    val description: String,
    val amount: BigDecimal?,
    val currency: String?,
    val counterpartyLabel: String? = null,
    /** CANDIDATE transfer: categorized normally but never turned into a CATEGORY question. */
    val transferCandidate: Boolean = false,
)

/** Outcome for one transaction. [question] non-null = ask the user (category stays null). */
data class TxDecision(
    val id: UUID,
    val category: Category?,
    val source: CategorySource?,
    val confidence: Double?,
    val merchant: MerchantName?,
    val question: String?,
    val suggestions: List<Category>,
    /** Which layer produced the question/decision, for metrics and the eval report. */
    val layer: String,
)

data class EngineResult(
    val decisions: List<TxDecision>,
    val embeddingsAvailable: Boolean,
    val llmAttempted: Boolean,
    val llmAvailable: Boolean,
    val llmNanos: Long,
)

/**
 * Structured output of one LLM batch (mutable with defaults so any JSON binder can construct it). Every field is
 * `required` in the generated JSON schema: with Ollama's schema-constrained decoding, optional fields are often
 * skipped entirely (the model then never states a category). Property order in the schema is alphabetical, so
 * `business` (a short note on what the merchant is) comes before `category`, a light reasoning step.
 */
class LlmBatch {
    @field:JsonProperty(required = true)
    var results: List<LlmItem> = emptyList()
}

class LlmItem {
    @field:JsonProperty(required = true)
    var id: Int = 0
    @field:JsonProperty(required = true)
    var business: String? = null
    @field:JsonProperty(required = true)
    var category: Category? = null
    @field:JsonProperty(required = true)
    var confidence: Double = 0.0
    @field:JsonProperty(required = true)
    var question: String? = null
    @field:JsonProperty(required = true)
    var suggestions: List<Category> = emptyList()
}

/**
 * The three categorization layers (plan sections 9 and 13): rules (user labels, user aliases, banking keywords,
 * system aliases) → embedding similarity over known merchants and past examples → LLM in batches with the
 * category enum, relevant hints and the most similar examples. Low confidence or no LLM → a question.
 *
 * Stateless with respect to statements and NEVER called inside a DB transaction: it only reads the learning
 * tables and calls the models. The caller saves the result in its own short transaction.
 */
@Component
class CategorizationEngine(
    private val learning: LearningRepository,
    private val embeddings: EmbeddingGateway,
    private val llm: LlmGateway,
    private val mode: LlmMode,
    private val properties: SpendlyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = properties.categorization

    fun categorize(inputs: List<TxInput>, llmEnabled: Boolean = mode.isEnabled()): EngineResult {
        if (inputs.isEmpty()) return EngineResult(emptyList(), true, false, true, 0)
        val decided = LinkedHashMap<UUID, TxDecision>()
        val merchants = inputs.associate { it.id to MerchantNormalizer.normalize(it.description) }

        // ---- Layer 1: rules
        val aliases = learning.aliases().sortedWith(compareBy<Alias> { if (it.createdBy == "USER") 0 else 1 }.thenByDescending { it.pattern.length })
        for (tx in inputs) {
            val rule = ruleFor(tx, merchants[tx.id], aliases)
            if (rule != null) decided[tx.id] = rule
        }

        // ---- Layer 2: embeddings
        var pending = inputs.filter { it.id !in decided }
        val neighbors = HashMap<UUID, List<Neighbor>>()
        var embeddingsAvailable = true
        if (pending.isNotEmpty()) {
            indexMerchants()
            val texts = pending.map { merchantText(it, merchants[it.id]) }
            val vectors = embeddings.embed(texts)
            if (vectors == null) {
                embeddingsAvailable = false
            } else {
                pending.forEachIndexed { i, tx ->
                    val near = (learning.nearestMerchants(vectors[i], cfg.fewShotExamples) + learning.nearestExamples(vectors[i], cfg.fewShotExamples))
                        .sortedByDescending { it.similarity }
                    neighbors[tx.id] = near
                    val best = near.firstOrNull()
                    if (best != null && best.similarity >= cfg.embeddingThreshold && best.category != Category.INTERNAL_TRANSFER) {
                        decided[tx.id] = TxDecision(tx.id, best.category, CategorySource.EMBEDDING, best.similarity.coerceIn(0.0, 1.0),
                            merchants[tx.id], null, emptyList(), "EMBEDDING")
                    }
                }
            }
        }

        // ---- Layer 3: LLM
        pending = inputs.filter { it.id !in decided }
        var llmAvailable = true
        var llmAttempted = false
        var llmNanos = 0L
        if (pending.isNotEmpty() && llmEnabled) {
            val hints = learning.hints(merchantIdsFor(pending, merchants))
            for (batch in pending.chunked(cfg.llmBatchSize.coerceIn(1, 50))) {
                if (!llmAvailable) break
                llmAttempted = true
                val started = System.nanoTime()
                try {
                    val result = callLlm(batch, merchants, neighbors, hints)
                    batch.forEachIndexed { i, tx -> decided[tx.id] = fromLlm(tx, merchants[tx.id], result[i + 1], neighbors[tx.id].orEmpty()) }
                } catch (e: LlmUnavailableException) {
                    llmAvailable = false
                    log.warn("LLM unavailable; remaining transactions become questions")
                } finally {
                    llmNanos += System.nanoTime() - started
                }
            }
        }

        // ---- Leftovers: generic questions (LLM off or down) with the top embedding matches as suggestions
        for (tx in inputs) {
            if (tx.id in decided) continue
            val near = neighbors[tx.id].orEmpty()
            val suggestions = near.map { it.category }.filter { it != Category.INTERNAL_TRANSFER }.distinct().take(3)
            decided[tx.id] = unresolved(tx, merchants[tx.id], null, genericQuestion(tx, merchants[tx.id]), suggestions, null, "NONE")
        }
        return EngineResult(inputs.map { decided.getValue(it.id) }, embeddingsAvailable, llmAttempted, llmAvailable, llmNanos)
    }

    /** Fills missing merchant embeddings (seeded or learned merchants); outside any transaction. */
    fun indexMerchants() {
        val missing = learning.merchantsMissingEmbedding()
        if (missing.isEmpty()) return
        val vectors = embeddings.embed(missing.map { it.second }) ?: return
        missing.forEachIndexed { i, (id, _) -> learning.setMerchantEmbedding(id, vectors[i]) }
    }

    private fun merchantText(tx: TxInput, merchant: MerchantName?): String = merchant?.display ?: tx.description

    private fun merchantIdsFor(pending: List<TxInput>, merchants: Map<UUID, MerchantName?>): Set<UUID> =
        learning.merchantIdsByKeys(pending.mapNotNull { merchants[it.id]?.key }.toSet()).values.toSet()

    private fun ruleFor(tx: TxInput, merchant: MerchantName?, aliases: List<Alias>): TxDecision? {
        fun rule(category: Category, m: MerchantName?) = TxDecision(tx.id, category, CategorySource.RULE, 1.0, m, null, emptyList(), "RULE")
        KeywordRules.matchLabel(tx.counterpartyLabel, tx.amount)?.let { return rule(it, merchant) }
        val key = MatchKeys.of(tx.description)
        val userAlias = aliases.firstOrNull { it.createdBy == "USER" && it.category != null && MatchKeys.containsWords(key, it.pattern) }
        if (userAlias != null) return rule(userAlias.category!!, MerchantName(userAlias.merchantName, MatchKeys.of(userAlias.merchantName)))
        KeywordRules.match(tx.description, tx.amount)?.let { return rule(it, null) }
        val systemAlias = aliases.firstOrNull { it.createdBy != "USER" && it.category != null && MatchKeys.containsWords(key, it.pattern) }
        if (systemAlias != null) return rule(systemAlias.category!!, MerchantName(systemAlias.merchantName, MatchKeys.of(systemAlias.merchantName)))
        return null
    }

    private fun callLlm(
        batch: List<TxInput>,
        merchants: Map<UUID, MerchantName?>,
        neighbors: Map<UUID, List<Neighbor>>,
        hints: List<Hint>,
    ): Map<Int, LlmItem> {
        val examples = batch.flatMap { neighbors[it.id].orEmpty() }
            .filter { it.category != Category.INTERNAL_TRANSFER }
            .sortedByDescending { it.similarity }
            .distinctBy { it.text.lowercase() }
            .take(cfg.fewShotExamples)
        val labels = batch.mapNotNull { tx -> tx.counterpartyLabel?.let { "The counterparty of transaction ${batch.indexOf(tx) + 1} is labelled by the user as \"$it\"." } }
        val system = LlmPrompts.system(hints.map { it.text } + labels, examples)
        val user = LlmPrompts.user(batch, merchants)
        val out = llm.entity(system, user, LlmBatch::class.java)
        // Validate: IDs must be the batch's 1..n (others are ignored); duplicates keep the first.
        return out.results.filter { it.id in 1..batch.size }.distinctBy { it.id }.associateBy { it.id }
    }

    private fun fromLlm(tx: TxInput, merchant: MerchantName?, item: LlmItem?, near: List<Neighbor>): TxDecision {
        val nearSuggestions = near.map { it.category }.filter { it != Category.INTERNAL_TRANSFER }.distinct()
        if (item == null) {
            return unresolved(tx, merchant, null, genericQuestion(tx, merchant), nearSuggestions.take(3), null, "LLM")
        }
        val category = item.category?.takeIf { it != Category.INTERNAL_TRANSFER }
        val confidence = item.confidence.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.0
        val suggestions = (item.suggestions + listOfNotNull(category) + nearSuggestions)
            .filter { it != Category.INTERNAL_TRANSFER }.distinct().take(3)
        if (category != null && confidence >= cfg.confidenceThreshold) {
            return TxDecision(tx.id, category, CategorySource.LLM, confidence, merchant, null, emptyList(), "LLM")
        }
        val question = item.question?.trim()?.takeIf { it.length in 5..300 } ?: genericQuestion(tx, merchant)
        return unresolved(tx, merchant, category, question, suggestions, confidence, "LLM")
    }

    /**
     * Not confidently categorized: a question, except for transfer candidates, which keep the best guess
     * (they are excluded from totals until resolved, and a TRANSFER question will cover them).
     */
    private fun unresolved(
        tx: TxInput,
        merchant: MerchantName?,
        guess: Category?,
        question: String,
        suggestions: List<Category>,
        confidence: Double?,
        layer: String,
    ): TxDecision {
        if (tx.transferCandidate) {
            val best = guess ?: suggestions.firstOrNull() ?: Category.OTHER
            val source = if (layer == "LLM" && guess != null) CategorySource.LLM else null
            return TxDecision(tx.id, best, source, confidence, merchant, null, emptyList(), layer)
        }
        return TxDecision(tx.id, null, null, confidence, merchant, question, suggestions, layer)
    }

    private fun genericQuestion(tx: TxInput, merchant: MerchantName?): String =
        "Which category is \"${merchant?.display ?: tx.description.take(120)}\"?"
}

/** Prompt texts for layer 3. Only sanitized descriptions and amounts go in; nothing else about the user. */
object LlmPrompts {
    const val VERSION = "categorize-v2"

    fun system(knowledge: List<String>, examples: List<Neighbor>): String = buildString {
        appendLine("You categorize bank transactions of a person in Romania. Descriptions may be Romanian or English.")
        appendLine("Choose exactly one category for each transaction from this list (NAME: what belongs there):")
        Category.forLlm.forEach { appendLine("- ${it.name}: ${it.meaning}") }
        appendLine()
        appendLine("How to read the descriptions:")
        appendLine("- Card payments look like \"PLATA CARD ****1111 <MERCHANT> <store number> <CITY> RO\" or \"POS <MERCHANT> <CITY>\"; app exports show just the merchant name.")
        appendLine("- Identify the merchant or brand and use what you know about what it sells. Ignore store numbers, cities and country codes.")
        appendLine("- [PERSON#…] is an anonymized private person and RO**…1234 a masked account number.")
        appendLine("- \"out\" means money paid, \"in\" means money received. When the direction is unknown, words like \"payment from\",")
        appendLine("  \"incasare\", \"transfer de la\" or \"refund\" mean money received (usually INCOME).")
        appendLine("- Decide the category from the KIND of business, not from how it is billed: a learning or course platform is")
        appendLine("  EDUCATION even when paid monthly; a ticketing site is ENTERTAINMENT; a fast-food or restaurant chain is")
        appendLine("  RESTAURANTS; OTHER is only for payments that fit no category.")
        appendLine()
        appendLine("For each transaction return:")
        appendLine("- id: the transaction id;")
        appendLine("- business: what KIND of business the merchant is, in a few words, not its name (e.g. \"supermarket chain\", \"bookstore\", \"electricity supplier\", \"private person\");")
        appendLine("- category: one NAME from the list, or null;")
        appendLine("- confidence: 0.0 to 1.0, how sure you are of the category;")
        appendLine("- question and suggestions.")
        appendLine("When you know the merchant or brand, or the description itself names the kind of business or payment")
        appendLine("(a restaurant, a shop of some kind, a school, a loan installment, ...), choose its category with confidence 0.8")
        appendLine("or higher, question null and suggestions an empty list. Only when you cannot tell what kind of business it is, set category to null and")
        appendLine("confidence below 0.5, write ONE short question for the user (e.g. \"Is SC ALFA TRADE SRL a gym, a supplier,")
        appendLine("or a person?\") and give up to 3 likely categories in suggestions.")
        if (knowledge.isNotEmpty()) {
            appendLine()
            appendLine("User-provided knowledge (always trust this over your own guesses):")
            knowledge.forEach { appendLine("- ${oneLine(it)}") }
        }
        if (examples.isNotEmpty()) {
            appendLine()
            appendLine("Examples of how this user categorizes similar transactions:")
            examples.forEach { appendLine("- \"${oneLine(it.text)}\" -> ${it.category.name}") }
        }
    }.trim()

    fun user(batch: List<TxInput>, merchants: Map<UUID, MerchantName?>): String = buildString {
        appendLine("Transactions (id | direction | amount | description):")
        batch.forEachIndexed { i, tx ->
            val direction = when {
                tx.amount == null -> "unknown"
                tx.amount.signum() < 0 -> "out"
                else -> "in"
            }
            val amount = tx.amount?.abs()?.toPlainString()?.let { "$it ${tx.currency ?: ""}".trim() } ?: "unknown"
            append(i + 1).append(" | ").append(direction).append(" | ").append(amount).append(" | ").appendLine(oneLine(tx.description))
        }
        append("Return exactly one result for each id from 1 to ${batch.size}.")
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').take(300)
}
