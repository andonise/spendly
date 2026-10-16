package dev.spendly.statement

import dev.spendly.statement.domain.StatementStateMachine
import dev.spendly.statement.persistence.StatementRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Public API of the statement module for the later pipeline steps (categorization): read a statement and move it
 * through guarded status transitions (SQL guard `WHERE status = :expected`).
 */
@Service
class StatementLifecycle(private val statements: StatementRepository, private val stateMachine: StatementStateMachine) {

    fun find(id: UUID): StatementRecord? = statements.findById(id)

    /** `SELECT … FOR UPDATE`: call inside a transaction. */
    fun lock(id: UUID): StatementRecord? = statements.lockById(id)

    fun updateStep(id: UUID, expected: StatementStatus, step: ProcessingStep): Int = statements.updateStep(id, expected, step)

    /** Throws a 409 conflict when the statement is no longer in [expected]. */
    fun transition(id: UUID, expected: StatementStatus, target: StatementStatus, step: ProcessingStep? = null, error: StatementError? = null) =
        stateMachine.transition(id, expected, target, step, error)

    /** Like [transition], but returns false instead of throwing. */
    fun tryTransition(id: UUID, expected: StatementStatus, target: StatementStatus, step: ProcessingStep? = null, error: StatementError? = null): Boolean =
        stateMachine.tryTransition(id, expected, target, step, error)
}
