package dev.spendly.transfer

import dev.spendly.transfer.domain.CounterpartyService
import dev.spendly.transfer.domain.TransferService
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** Public API of the transfer module, used by categorization (transfer detection runs before the categorizer). */
@Service
class TransferApi(private val transfers: TransferService, private val counterparties: CounterpartyService) {

    /** Steps 1-4 of transfer detection for one statement; returns the number of transactions marked. */
    fun detectForStatement(statementId: UUID, periodStart: LocalDate, periodEnd: LocalDate, correlationId: String): Int =
        transfers.detectForStatement(statementId, periodStart, periodEnd, correlationId)

    fun markInternalCategories(statementId: UUID) = transfers.markInternalCategories(statementId)

    /** Opens TRANSFER questions for CANDIDATE transactions whose other side is covered; returns how many. */
    fun createTransferQuestions(): Int = transfers.createTransferQuestions()

    /** A TRANSFER answer with applyToFuture: the counterparty becomes one of the user's own accounts. */
    fun markOwnCounterparty(token: String, correlationId: String) = counterparties.markOwnFromAnswer(token, correlationId)
}
