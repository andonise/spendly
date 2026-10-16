package dev.spendly.account

import java.util.UUID

/**
 * Implemented by modules that reference accounts (statement), so account doesn't depend on them.
 * An account that is still in use can't be deleted.
 */
fun interface AccountUsage {
    fun statementCount(accountId: UUID): Long
}
