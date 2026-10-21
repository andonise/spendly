package dev.spendly.shared.outbox

import dev.spendly.shared.messaging.AnswersSubmittedPayload
import dev.spendly.shared.messaging.ChangeCause
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.InputRequiredPayload
import dev.spendly.shared.messaging.ReportRequestReason
import dev.spendly.shared.messaging.ReportRequestedPayload
import dev.spendly.shared.messaging.StatementParsedPayload
import dev.spendly.shared.messaging.Topics
import dev.spendly.shared.messaging.TransactionsChangedPayload
import java.time.LocalDate
import java.util.UUID
import org.springframework.stereotype.Component

/** Writes the Phase 3 and 4 events to the outbox, in the caller's transaction (they publish after commit). */
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

    fun reportRequested(
        reportId: UUID,
        version: Int,
        granularity: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        accountScope: String,
        reason: ReportRequestReason,
        correlationId: String,
    ) = outbox.append(
        EventEnvelope.create(
            EventTypes.REPORT_REQUESTED, correlationId,
            ReportRequestedPayload(reportId.toString(), version, granularity, periodStart.toString(), periodEnd.toString(), accountScope, reason.name),
        ),
        Topics.REPORT_REQUESTED, reportId.toString(), reportId,
    )
}
