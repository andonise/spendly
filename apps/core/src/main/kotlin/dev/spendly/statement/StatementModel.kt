package dev.spendly.statement

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class StatementStatus {
    UPLOADED, PARSED, CATEGORIZING, NEEDS_INPUT, CATEGORIZED, FAILED;

    val terminal: Boolean get() = this == CATEGORIZED || this == FAILED
}

/** Last step completed successfully; a retry resumes after it. */
enum class ProcessingStep { UPLOAD, PARSE, TRANSFER_DETECTION, CATEGORIZE, APPLY_ANSWERS }

enum class FileType(val extension: String) {
    CSV("csv"), XLS("xls"), XLSX("xlsx"), PDF("pdf");

    companion object {
        fun fromExtension(ext: String): FileType? = entries.firstOrNull { it.extension == ext.lowercase() }
    }
}

/** Stored in `statements.error` (jsonb). `message` never contains PII, amounts or descriptions. */
data class StatementError(val code: String, val message: String, val retryable: Boolean? = null)

data class StatementRecord(
    val id: UUID,
    val accountId: UUID,
    val fileType: FileType,
    val fileHash: String,
    val status: StatementStatus,
    val lastCompletedStep: ProcessingStep?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val error: StatementError?,
    val correlationId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val tempFileName: String get() = "$id.${fileType.extension}"
}
