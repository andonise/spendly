package dev.spendly.account

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class AccountType { CURRENT, SAVINGS, CREDIT_CARD, E_WALLET }

/** An account. The IBAN itself is never stored: only its HMAC, country code and last 4 characters. */
@Entity
@Table(name = "accounts")
class AccountEntity(
    @Id
    var id: UUID,
    var label: String,
    var bank: String,
    @Enumerated(EnumType.STRING)
    var type: AccountType,
    var currency: String,
    @Column(name = "iban_hash")
    var ibanHash: String,
    @Column(name = "iban_country")
    var ibanCountry: String,
    @Column(name = "iban_last4")
    var ibanLast4: String,
    @Column(name = "card_last4")
    var cardLast4: String?,
    @Column(name = "active_from")
    var activeFrom: LocalDate,
    @Column(name = "closed_at")
    var closedAt: LocalDate?,
    @Column(name = "created_at")
    var createdAt: Instant,
    @Column(name = "updated_at")
    var updatedAt: Instant,
) {
    val ibanMasked: String get() = "$ibanCountry**…$ibanLast4"
}

interface AccountRepository : JpaRepository<AccountEntity, UUID> {
    fun findAllByOrderByLabelAscIdAsc(): List<AccountEntity>

    fun existsByIbanHash(ibanHash: String): Boolean
}
