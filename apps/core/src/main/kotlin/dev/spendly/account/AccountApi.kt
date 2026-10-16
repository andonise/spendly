package dev.spendly.account

import dev.spendly.account.persistence.AccountRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** What other modules may know about an account. */
data class AccountRef(val id: UUID, val bank: String, val currency: String)

/** Public API of the account module. */
@Service
class AccountApi(private val accounts: AccountRepository) {

    fun find(id: UUID): AccountRef? = accounts.findById(id).map { AccountRef(it.id, it.bank, it.currency) }.orElse(null)
}
