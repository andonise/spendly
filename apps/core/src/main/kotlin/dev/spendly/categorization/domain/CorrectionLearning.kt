package dev.spendly.categorization.domain

import dev.spendly.categorization.persistence.LearningRepository
import dev.spendly.transaction.Category
import dev.spendly.transaction.CategoryLearning
import dev.spendly.transaction.MerchantNormalizer
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** A manual correction with "Apply to future" teaches the merchant like an answer does: default category + user alias. */
@Component
class CorrectionLearning(private val learning: LearningRepository, private val jdbc: JdbcClient) : CategoryLearning {

    override fun learn(transactionId: UUID, description: String, category: Category) {
        val merchant = MerchantNormalizer.normalize(description) ?: return
        val merchantId = learning.upsertMerchant(merchant, category)
        jdbc.sql("UPDATE transactions SET merchant_id = :m WHERE id = :id").param("m", merchantId).param("id", transactionId).update()
        learning.upsertUserAlias(merchant.key, merchantId)
    }
}
