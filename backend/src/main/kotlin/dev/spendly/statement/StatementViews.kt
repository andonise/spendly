package dev.spendly.statement

import dev.spendly.transaction.StatementProgress
import dev.spendly.transaction.StatementSummary
import dev.spendly.transaction.StatementSummaryService
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** contracts/openapi-draft.yaml `Statement`. */
data class StatementResponse(
    val id: UUID,
    val accountId: UUID,
    val fileType: FileType,
    val status: StatementStatus,
    val lastCompletedStep: ProcessingStep?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val error: StatementError?,
    val progress: StatementProgress,
    /** Present once the statement is CATEGORIZED, else null. */
    val summary: StatementSummary?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Builds the API view of a statement (progress counters, and the summary once CATEGORIZED). */
@Component
class StatementViews(private val summaries: StatementSummaryService) {

    fun of(s: StatementRecord) = StatementResponse(
        id = s.id,
        accountId = s.accountId,
        fileType = s.fileType,
        status = s.status,
        lastCompletedStep = s.lastCompletedStep,
        periodStart = s.periodStart,
        periodEnd = s.periodEnd,
        error = s.error,
        progress = summaries.progress(s.id),
        summary = if (s.status == StatementStatus.CATEGORIZED) summaries.summary(s.id) else null,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
    )
}
