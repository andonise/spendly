package dev.spendly.importing

import dev.spendly.crypto.AesGcmCipher
import dev.spendly.events.ConsumerNames
import dev.spendly.events.DlqReason
import dev.spendly.events.EventEnvelope
import dev.spendly.events.NonRetryableEventException
import dev.spendly.events.StatementParseFailedPayload
import dev.spendly.events.TransactionsImportedPayload
import dev.spendly.fx.FxService
import dev.spendly.outbox.ProcessedEventsRepository
import dev.spendly.statement.ProcessingStep
import dev.spendly.statement.StatementError
import dev.spendly.statement.StatementRepository
import dev.spendly.statement.StatementStateMachine
import dev.spendly.statement.StatementStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Import handler (consumer group `spendly-backend-import`) for TransactionsImported and StatementParseFailed,
 * from the Kotlin CSV parser and the Python pdf-parser alike.
 *
 * One DB transaction covers: processed_events + all transactions (`ON CONFLICT (account_id, tx_hash) DO NOTHING`)
 * + period + amount_base/fx_rate + guarded status change (`WHERE status = 'UPLOADED'`) + last_completed_step.
 * SSE is pushed after commit. Any exception rolls everything back (AC-TX-4).
 */
@Service
class ImportService(
    private val processed: ProcessedEventsRepository,
    private val statements: StatementRepository,
    private val stateMachine: StatementStateMachine,
    private val fx: FxService,
    private val cipher: AesGcmCipher,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun importTransactions(env: EventEnvelope<TransactionsImportedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        val p = env.payload
        val statementId = UUID.fromString(p.statementId)
        if (processed.exists(eventId, CONSUMER)) {
            log.info("Duplicate TransactionsImported eventId={} statementId={} ignored", eventId, statementId)
            return
        }
        if (p.transactionCount != p.transactions.size) {
            throw NonRetryableEventException(DlqReason.SCHEMA_VALIDATION, "transactionCount does not match transactions length")
        }
        // Outside the transaction (network): try to obtain missing FX rates.
        fx.ensureRates(p.transactions.map { it.currency to LocalDate.parse(it.bookingDate) }.toSet())

        tx.executeWithoutResult {
            if (!processed.tryInsert(eventId, CONSUMER)) return@executeWithoutResult
            val statement = statements.lockById(statementId)
            if (statement == null) {
                log.warn("TransactionsImported eventId={} refers to unknown statementId={} accountId={}; skipped", eventId, statementId, p.accountId)
                return@executeWithoutResult
            }
            if (statement.accountId.toString() != p.accountId) {
                throw NonRetryableEventException(DlqReason.SCHEMA_VALIDATION, "accountId does not match the statement's account")
            }
            if (statement.status != StatementStatus.UPLOADED) {
                log.info("TransactionsImported eventId={} for statement {} ignored: status is {}", eventId, statementId, statement.status)
                return@executeWithoutResult
            }

            var missingFx = 0
            val batch = p.transactions.map { t ->
                val amount = BigDecimal(t.amount)
                val bookingDate = LocalDate.parse(t.bookingDate)
                val rate = fx.rateToBase(t.currency, bookingDate)
                if (rate == null) missingFx++
                MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("statementId", statementId)
                    .addValue("accountId", statement.accountId)
                    .addValue("sequence", t.sequence)
                    .addValue("bookingDate", bookingDate)
                    .addValue("valueDate", t.valueDate?.let(LocalDate::parse), java.sql.Types.DATE)
                    .addValue("amount", amount)
                    .addValue("currency", t.currency)
                    .addValue("amountBase", rate?.let { fx.toBase(amount, it) }, java.sql.Types.NUMERIC)
                    .addValue("fxRate", rate, java.sql.Types.NUMERIC)
                    .addValue("description", cipher.encrypt(t.description))
                    .addValue("counterpartyToken", t.counterpartyToken, java.sql.Types.VARCHAR)
                    .addValue("counterpartyTokenKind", t.counterpartyTokenKind, java.sql.Types.VARCHAR)
                    .addValue("txHash", t.txHash)
            }
            if (batch.isNotEmpty()) jdbc.batchUpdate(INSERT_SQL, batch.toTypedArray())
            statements.updatePeriod(statementId, LocalDate.parse(p.periodStart), LocalDate.parse(p.periodEnd))

            if (missingFx > 0) {
                // Transactions are kept (amount_base NULL); a retry after importing rates resumes from PARSE.
                stateMachine.transition(
                    statementId, StatementStatus.UPLOADED, StatementStatus.FAILED, ProcessingStep.PARSE,
                    StatementError("FX_RATE_MISSING", "No BNR exchange rate for $missingFx transaction(s); import rates, then retry", true),
                )
            } else {
                stateMachine.transition(statementId, StatementStatus.UPLOADED, StatementStatus.PARSED, ProcessingStep.PARSE)
            }
            log.info("Imported statement {} ({} transactions, eventId={}, producer={})", statementId, p.transactions.size, eventId, env.producer)
        }
    }

    fun parseFailed(env: EventEnvelope<StatementParseFailedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        val p = env.payload
        val statementId = UUID.fromString(p.statementId)
        tx.executeWithoutResult {
            if (!processed.tryInsert(eventId, CONSUMER)) {
                log.info("Duplicate StatementParseFailed eventId={} ignored", eventId)
                return@executeWithoutResult
            }
            val statement = statements.lockById(statementId)
            if (statement == null) {
                log.warn("StatementParseFailed eventId={} refers to unknown statementId={}; skipped", eventId, statementId)
                return@executeWithoutResult
            }
            if (statement.status != StatementStatus.UPLOADED) {
                log.info("StatementParseFailed eventId={} for statement {} ignored: status is {}", eventId, statementId, statement.status)
                return@executeWithoutResult
            }
            stateMachine.transition(
                statementId, StatementStatus.UPLOADED, StatementStatus.FAILED,
                error = StatementError(p.reason, p.message, p.retryable),
            )
        }
    }

    /** Retry from PARSE: fill amount_base for rows missing a rate; true when every row now has one. */
    fun completeFx(statementId: UUID): Boolean {
        val rows = jdbc.query(
            "SELECT id, booking_date, amount, currency FROM transactions WHERE statement_id = :id AND amount_base IS NULL FOR UPDATE",
            mapOf("id" to statementId),
        ) { rs, _ ->
            listOf(rs.getObject("id", UUID::class.java), rs.getObject("booking_date", LocalDate::class.java), rs.getBigDecimal("amount"), rs.getString("currency"))
        }
        var missing = 0
        for ((id, date, amount, currency) in rows) {
            val rate = fx.rateToBase(currency as String, date as LocalDate)
            if (rate == null) {
                missing++
                continue
            }
            jdbc.update(
                "UPDATE transactions SET fx_rate = :rate, amount_base = :base WHERE id = :id",
                mapOf("rate" to rate, "base" to fx.toBase(amount as BigDecimal, rate), "id" to id),
            )
        }
        return missing == 0
    }

    companion object {
        const val CONSUMER = ConsumerNames.IMPORT
        private val INSERT_SQL = """
            INSERT INTO transactions (id, statement_id, account_id, sequence, booking_date, value_date, amount, currency,
                amount_base, fx_rate, description, counterparty_token, counterparty_token_kind, tx_hash)
            VALUES (:id, :statementId, :accountId, :sequence, :bookingDate, :valueDate, :amount, :currency,
                :amountBase, :fxRate, :description, :counterpartyToken, :counterpartyTokenKind, :txHash)
            ON CONFLICT (account_id, tx_hash) DO NOTHING
        """.trimIndent()
    }
}
