package dev.spendly.importing

import dev.spendly.crypto.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Read model of `transactions`. `description` is decrypted transparently by [EncryptedStringConverter] (AC-SD-7). */
@Entity
@Table(name = "transactions")
class TransactionEntity(
    @Id var id: UUID,
    @Column(name = "statement_id") var statementId: UUID,
    @Column(name = "account_id") var accountId: UUID,
    var sequence: Int,
    @Column(name = "booking_date") var bookingDate: LocalDate,
    @Column(name = "value_date") var valueDate: LocalDate?,
    var amount: BigDecimal,
    var currency: String,
    @Column(name = "amount_base") var amountBase: BigDecimal?,
    @Column(name = "fx_rate") var fxRate: BigDecimal?,
    @Convert(converter = EncryptedStringConverter::class)
    var description: String,
    @Column(name = "counterparty_token") var counterpartyToken: String?,
    @Column(name = "counterparty_token_kind") var counterpartyTokenKind: String?,
    @Column(name = "transfer_status") var transferStatus: String,
    @Column(name = "tx_hash") var txHash: String,
) {
    override fun toString() = "TransactionEntity(id=$id, statementId=$statementId)"
}

interface TransactionRepository : JpaRepository<TransactionEntity, UUID> {
    fun findByStatementIdOrderBySequence(statementId: UUID): List<TransactionEntity>

    fun countByStatementId(statementId: UUID): Long

    fun countByAccountId(accountId: UUID): Long
}
