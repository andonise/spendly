package dev.spendly.categorization.persistence

import dev.spendly.categorization.llm.EmbeddingGateway
import dev.spendly.shared.crypto.AesGcmCipher
import dev.spendly.transaction.Category
import dev.spendly.transaction.MerchantName
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class Alias(
    val pattern: String,
    val merchantId: UUID,
    val merchantName: String,
    val category: Category?,
    val createdBy: String,
)

/** A known merchant or a past example close to a transaction, by cosine similarity. */
data class Neighbor(val text: String, val category: Category, val similarity: Double, val fromExample: Boolean)

data class Hint(val merchantId: UUID?, val text: String)

/** merchants, merchant_aliases, merchant_hints and categorization_examples (the learning tables). */
@Repository
class LearningRepository(private val jdbc: JdbcClient, private val cipher: AesGcmCipher) {

    fun aliases(): List<Alias> = jdbc.sql(
        """
        SELECT a.pattern, a.merchant_id, m.normalized_name, m.default_category, a.created_by
        FROM merchant_aliases a JOIN merchants m ON m.id = a.merchant_id
        """.trimIndent(),
    ).query { rs, _ ->
        Alias(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3), Category.parse(rs.getString(4)), rs.getString(5))
    }.list()

    fun merchantsMissingEmbedding(limit: Int = 500): List<Pair<UUID, String>> =
        jdbc.sql("SELECT id, normalized_name FROM merchants WHERE embedding IS NULL AND default_category IS NOT NULL ORDER BY match_key LIMIT :limit")
            .param("limit", limit).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()

    fun setMerchantEmbedding(id: UUID, vector: FloatArray) {
        jdbc.sql("UPDATE merchants SET embedding = CAST(:v AS vector) WHERE id = :id")
            .param("v", EmbeddingGateway.literal(vector)).param("id", id).update()
    }

    fun nearestMerchants(vector: FloatArray, k: Int): List<Neighbor> = jdbc.sql(
        """
        SELECT normalized_name, default_category, 1 - (embedding <=> CAST(:v AS vector)) AS sim
        FROM merchants WHERE embedding IS NOT NULL AND default_category IS NOT NULL
        ORDER BY embedding <=> CAST(:v AS vector) LIMIT :k
        """.trimIndent(),
    ).param("v", EmbeddingGateway.literal(vector)).param("k", k).query { rs, _ ->
        Neighbor(rs.getString(1), Category.valueOf(rs.getString(2)), rs.getDouble(3), false)
    }.list()

    fun nearestExamples(vector: FloatArray, k: Int): List<Neighbor> = jdbc.sql(
        """
        SELECT raw_description, category, 1 - (embedding <=> CAST(:v AS vector)) AS sim
        FROM categorization_examples WHERE embedding IS NOT NULL
        ORDER BY embedding <=> CAST(:v AS vector) LIMIT :k
        """.trimIndent(),
    ).param("v", EmbeddingGateway.literal(vector)).param("k", k).query { rs, _ ->
        Neighbor(cipher.decrypt(rs.getString(1)), Category.valueOf(rs.getString(2)), rs.getDouble(3), true)
    }.list()

    /** Global hints plus hints of the given merchants (decrypted). */
    fun hints(merchantIds: Collection<UUID>): List<Hint> = jdbc.sql(
        """
        SELECT merchant_id, text FROM merchant_hints
        WHERE merchant_id IS NULL OR merchant_id = ANY(CAST(:ids AS uuid[]))
        ORDER BY created_at DESC LIMIT 50
        """.trimIndent(),
    ).param("ids", merchantIds.map(UUID::toString).toTypedArray()).query { rs, _ ->
        Hint(rs.getObject(1, UUID::class.java), cipher.decrypt(rs.getString(2)))
    }.list()

    fun merchantIdsByKeys(keys: Collection<String>): Map<String, UUID> =
        if (keys.isEmpty()) emptyMap() else jdbc.sql("SELECT match_key, id FROM merchants WHERE match_key = ANY(CAST(:keys AS varchar[]))")
            .param("keys", keys.toTypedArray()).query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }.list().toMap()

    /** Inserts or finds a merchant by match key; [defaultCategory] (from a user alias) overwrites the old default. */
    fun upsertMerchant(name: MerchantName, defaultCategory: Category? = null): UUID = jdbc.sql(
        """
        INSERT INTO merchants (id, normalized_name, match_key, default_category)
        VALUES (:id, :name, :key, :category)
        ON CONFLICT (match_key) DO UPDATE SET
            default_category = COALESCE(EXCLUDED.default_category, merchants.default_category),
            updated_at = now()
        RETURNING id
        """.trimIndent(),
    ).param("id", UUID.randomUUID()).param("name", name.display).param("key", name.key)
        .param("category", defaultCategory?.name).query(UUID::class.java).single()

    fun upsertUserAlias(pattern: String, merchantId: UUID) {
        jdbc.sql(
            """
            INSERT INTO merchant_aliases (id, pattern, merchant_id, created_by) VALUES (:id, :pattern, :merchant, 'USER')
            ON CONFLICT (pattern) DO UPDATE SET merchant_id = EXCLUDED.merchant_id, created_by = 'USER'
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("pattern", pattern).param("merchant", merchantId).update()
    }

    fun insertHint(merchantId: UUID?, text: String) {
        jdbc.sql("INSERT INTO merchant_hints (id, merchant_id, text) VALUES (:id, :merchant, :text)")
            .param("id", UUID.randomUUID()).param("merchant", merchantId).param("text", cipher.encrypt(text)).update()
    }

    fun insertExample(rawDescription: String, category: Category, vector: FloatArray?, answerId: UUID) {
        jdbc.sql(
            """
            INSERT INTO categorization_examples (id, raw_description, category, embedding, source_answer_id)
            VALUES (:id, :raw, :category, CAST(:v AS vector), :answer)
            ON CONFLICT (source_answer_id) DO NOTHING
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("raw", cipher.encrypt(rawDescription)).param("category", category.name)
            .param("v", vector?.let(EmbeddingGateway::literal)).param("answer", answerId).update()
    }
}
