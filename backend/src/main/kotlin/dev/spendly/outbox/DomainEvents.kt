package dev.spendly.outbox

import dev.spendly.events.AnswersSubmittedPayload
import dev.spendly.events.ChangeCause
import dev.spendly.events.EventEnvelope
import dev.spendly.events.EventTypes
import dev.spendly.events.InputRequiredPayload
import dev.spendly.events.StatementParsedPayload
import dev.spendly.events.Topics
import dev.spendly.events.TransactionsChangedPayload
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** Writes the Phase 3 events to the outbox, in the caller's transaction (they publish after commit). */
@Component
class DomainEvents(private val outbox: OutboxService) {

    fun statementParsed(statementId: UUID, accountId: UUID, periodStart: LocalDate, periodEnd: LocalDate, count: Int, correlationId: String) =
        outbox.append(
            EventEnvelope.create(
                EventTypes.STATEMENT_PARSED, correlationId,
                StatementParsedPayload(statementId.toString(), accountId.toString(), periodStart.toString(), periodEnd.toString(), count),
            ),
            Topics.STATEMENT_PARSED, accountId.toString(), statementId,
        )

    fun inputRequired(statementId: UUID, accountId: UUID, questionCount: Int, correlationId: String) =
        outbox.append(
            EventEnvelope.create(EventTypes.INPUT_REQUIRED, correlationId, InputRequiredPayload(statementId.toString(), accountId.toString(), questionCount)),
            Topics.INPUT_REQUIRED, accountId.toString(), statementId,
        )

    fun answersSubmitted(statementId: UUID, accountId: UUID, answerCount: Int, correlationId: String) =
        outbox.append(
            EventEnvelope.create(EventTypes.ANSWERS_SUBMITTED, correlationId, AnswersSubmittedPayload(statementId.toString(), accountId.toString(), answerCount)),
            Topics.ANSWERS_SUBMITTED, accountId.toString(), statementId,
        )

    /** [accountIds] first = the account whose change caused the event (it is also the message key). */
    fun transactionsChanged(
        accountIds: List<UUID>,
        from: LocalDate,
        to: LocalDate,
        cause: ChangeCause,
        statementId: UUID?,
        correlationId: String,
    ) {
        val ids = accountIds.distinct()
        require(ids.isNotEmpty())
        val (lo, hi) = if (to.isBefore(from)) to to from else from to to
        outbox.append(
            EventEnvelope.create(
                EventTypes.TRANSACTIONS_CHANGED, correlationId,
                TransactionsChangedPayload(ids.map(UUID::toString), lo.toString(), hi.toString(), cause.name, statementId?.toString()),
            ),
            Topics.TRANSACTIONS_CHANGED, ids.first().toString(), statementId ?: ids.first(),
        )
    }
}
