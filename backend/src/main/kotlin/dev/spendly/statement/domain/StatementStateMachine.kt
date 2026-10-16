package dev.spendly.statement.domain

import dev.spendly.shared.web.ConflictException
import dev.spendly.statement.ProcessingStep
import dev.spendly.statement.StatementError
import dev.spendly.statement.StatementStatus
import dev.spendly.statement.persistence.StatementRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/** Published inside the transaction that changed the status; SSE listeners receive it after commit. */
data class StatementStatusChanged(
    val statementId: UUID,
    val status: StatementStatus,
    val lastCompletedStep: ProcessingStep?,
    val error: StatementError?,
    val occurredAt: Instant,
)

/**
 * The statement status machine (plan section 5). Allowed transitions are checked here, and the guard is
 * enforced in SQL (`UPDATE … WHERE id = ? AND status = :expected`), so two concurrent callers can't both pass.
 * Must run inside the caller's DB transaction.
 */
@Component
class StatementStateMachine(
    private val repository: StatementRepository,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun transition(
        statementId: UUID,
        expected: StatementStatus,
        target: StatementStatus,
        step: ProcessingStep? = null,
        error: StatementError? = null,
    ) {
        check(target in (ALLOWED[expected] ?: emptySet())) { "Transition $expected -> $target is not allowed" }
        val clearError = target != StatementStatus.FAILED
        val updated = repository.updateStatus(statementId, expected, target, step, error, clearError)
        if (updated == 0) {
            throw StatusConflictException(statementId, expected, target)
        }
        val current = repository.findById(statementId)!!
        log.info("Statement {} {} -> {} (step={})", statementId, expected, target, current.lastCompletedStep)
        events.publishEvent(
            StatementStatusChanged(
                statementId = statementId,
                status = target,
                lastCompletedStep = current.lastCompletedStep,
                error = current.error,
                occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            ),
        )
    }

    /** Like [transition], but returns false instead of throwing when the SQL guard matched 0 rows. */
    fun tryTransition(
        statementId: UUID,
        expected: StatementStatus,
        target: StatementStatus,
        step: ProcessingStep? = null,
        error: StatementError? = null,
    ): Boolean = try {
        transition(statementId, expected, target, step, error)
        true
    } catch (e: StatusConflictException) {
        false
    }

    companion object {
        val ALLOWED: Map<StatementStatus, Set<StatementStatus>> = mapOf(
            StatementStatus.UPLOADED to setOf(StatementStatus.PARSED, StatementStatus.FAILED),
            StatementStatus.PARSED to setOf(StatementStatus.CATEGORIZING, StatementStatus.FAILED),
            StatementStatus.CATEGORIZING to setOf(StatementStatus.CATEGORIZED, StatementStatus.NEEDS_INPUT, StatementStatus.FAILED),
            StatementStatus.NEEDS_INPUT to setOf(StatementStatus.CATEGORIZING, StatementStatus.FAILED),
            // retry: resume after last_completed_step
            StatementStatus.FAILED to setOf(StatementStatus.UPLOADED, StatementStatus.PARSED),
            StatementStatus.CATEGORIZED to emptySet(),
        )
    }
}

class StatusConflictException(statementId: UUID, expected: StatementStatus, target: StatementStatus) :
    ConflictException("STATUS_CONFLICT", "Statement $statementId is no longer $expected; cannot move it to $target")
