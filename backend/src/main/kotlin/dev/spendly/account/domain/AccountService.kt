package dev.spendly.account.domain

import dev.spendly.account.AccountUsage
import dev.spendly.account.persistence.AccountEntity
import dev.spendly.account.persistence.AccountRepository
import dev.spendly.account.web.AccountCreate
import dev.spendly.account.web.AccountUpdate
import dev.spendly.shared.crypto.HmacService
import dev.spendly.shared.privacy.Validators
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.ConflictException
import dev.spendly.shared.web.FieldViolation
import dev.spendly.shared.web.NotFoundException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val usage: AccountUsage,
    private val hmac: HmacService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(): List<AccountEntity> = accounts.findAllByOrderByLabelAscIdAsc()

    @Transactional(readOnly = true)
    fun get(id: UUID): AccountEntity = accounts.findById(id).orElseThrow { NotFoundException("Account $id") }

    @Transactional
    fun create(req: AccountCreate): AccountEntity {
        val compact = compactIban(req.iban!!)
        if (!Validators.ibanMod97(compact)) {
            throw BadRequestException("INVALID_IBAN", "The IBAN is not valid", listOf(FieldViolation("iban", "invalid IBAN (mod-97 check failed)")))
        }
        val hash = hmac.ibanToken(compact)
        if (accounts.existsByIbanHash(hash)) {
            throw ConflictException("DUPLICATE_IBAN", "An account with the same IBAN is already registered")
        }
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val entity = AccountEntity(
            id = UUID.randomUUID(),
            label = req.label!!.trim(),
            bank = req.bank!!.trim(),
            type = req.type!!,
            currency = req.currency!!,
            ibanHash = hash,
            ibanCountry = compact.substring(0, 2),
            ibanLast4 = compact.takeLast(4),
            cardLast4 = req.cardLast4,
            activeFrom = req.activeFrom!!,
            closedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        try {
            accounts.saveAndFlush(entity)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("DUPLICATE_IBAN", "An account with the same IBAN is already registered")
        }
        log.info("Account {} created", entity.id)
        return entity
    }

    @Transactional
    fun update(id: UUID, req: AccountUpdate): AccountEntity {
        val a = get(id)
        req.label?.let { a.label = it.trim() }
        req.bank?.let { a.bank = it.trim() }
        if (req.cardLast4Present) a.cardLast4 = req.cardLast4
        if (req.closedAtPresent) {
            val closed = req.closedAt
            if (closed != null && closed.isBefore(a.activeFrom)) {
                throw BadRequestException("INVALID_CLOSED_AT", "closedAt must not be before activeFrom", listOf(FieldViolation("closedAt", "must be on or after activeFrom")))
            }
            a.closedAt = closed
        }
        a.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return accounts.saveAndFlush(a)
    }

    @Transactional
    fun delete(id: UUID) {
        val a = get(id)
        if (usage.statementCount(id) > 0) {
            throw ConflictException("ACCOUNT_HAS_STATEMENTS", "The account still has statements")
        }
        accounts.delete(a)
        log.info("Account {} deleted", id)
    }

    companion object {
        /** Removes whitespace and uppercases. */
        fun compactIban(raw: String): String = raw.filterNot { it.isWhitespace() || it == ' ' }.uppercase()
    }
}
