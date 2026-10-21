package dev.spendly.transaction

import java.util.UUID

/**
 * Learning from a manual category correction with "Apply to future" (the merchant gets a user alias). Declared here
 * and implemented by the categorization module, which owns learning, so `transaction` does not depend on it.
 * Called inside the correction's transaction.
 */
interface CategoryLearning {
    fun learn(transactionId: UUID, description: String, category: Category)
}
