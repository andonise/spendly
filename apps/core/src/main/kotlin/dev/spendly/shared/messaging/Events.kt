package dev.spendly.shared.messaging

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

object Topics {
    const val STATEMENT_UPLOADED = "spendly.statement-uploaded"
    const val TRANSACTIONS_IMPORTED = "spendly.transactions-imported"
    const val STATEMENT_PARSE_FAILED = "spendly.statement-parse-failed"
    const val STATEMENT_PARSED = "spendly.statement-parsed"
    const val INPUT_REQUIRED = "spendly.input-required"
    const val ANSWERS_SUBMITTED = "spendly.answers-submitted"
    const val TRANSACTIONS_CHANGED = "spendly.transactions-changed"
    const val DLQ_SUFFIX = ".dlq"
    val INGESTION = listOf(STATEMENT_UPLOADED, TRANSACTIONS_IMPORTED, STATEMENT_PARSE_FAILED)
}

object EventTypes {
    const val STATEMENT_UPLOADED = "StatementUploaded"
    const val TRANSACTIONS_IMPORTED = "TransactionsImported"
    const val STATEMENT_PARSE_FAILED = "StatementParseFailed"
    const val STATEMENT_PARSED = "StatementParsed"
    const val INPUT_REQUIRED = "InputRequired"
    const val ANSWERS_SUBMITTED = "AnswersSubmitted"
    const val TRANSACTIONS_CHANGED = "TransactionsChanged"

    /** eventType -> schema file in contracts/schemas. */
    val SCHEMA_FILES = mapOf(
        STATEMENT_UPLOADED to "statement-uploaded.schema.json",
        TRANSACTIONS_IMPORTED to "transactions-imported.schema.json",
        STATEMENT_PARSE_FAILED to "statement-parse-failed.schema.json",
        STATEMENT_PARSED to "statement-parsed.schema.json",
        INPUT_REQUIRED to "input-required.schema.json",
        ANSWERS_SUBMITTED to "answers-submitted.schema.json",
        TRANSACTIONS_CHANGED to "transactions-changed.schema.json",
    )
}

object Producers {
    const val BACKEND = "spendly-backend"
    const val PDF_PARSER = "spendly-pdf-parser"
}

object EventHeaders {
    const val EVENT_ID = "eventId"
    const val EVENT_TYPE = "eventType"
    const val SCHEMA_VERSION = "schemaVersion"
    const val CORRELATION_ID = "correlationId"
    const val DLQ_REASON = "dlqReason"
    const val DLQ_SOURCE = "dlqSource"
    const val DLQ_FAILED_AT = "dlqFailedAt"
}

/** dlqReason header values (contracts/README.md). */
enum class DlqReason { INVALID_JSON, SCHEMA_VALIDATION, UNSAFE_TEMP_FILE_PATH, RETRIES_EXHAUSTED, UNKNOWN_STATEMENT }

object ConsumerNames {
    const val CSV_PARSER = "spendly-backend-csv-parser"
    const val IMPORT = "spendly-backend-import"
    const val DLQ = "spendly-backend-dlq"
    const val CATEGORIZER = "spendly-backend-categorizer"
    const val RESUME = "spendly-backend-resume"
    const val TRANSFER_MATCHER = "spendly-backend-transfer-matcher"
}

data class EventEnvelope<T>(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: String,
    val correlationId: String,
    val producer: String,
    val payload: T,
) {
    companion object {
        fun <T> create(eventType: String, correlationId: String, payload: T, schemaVersion: Int = 1) = EventEnvelope(
            eventId = UUID.randomUUID().toString(),
            eventType = eventType,
            schemaVersion = schemaVersion,
            occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
            correlationId = correlationId,
            producer = Producers.BACKEND,
            payload = payload,
        )
    }
}

data class ParserInfo(val name: String, val version: String)

data class StatementUploadedPayload(
    val statementId: String,
    val accountId: String,
    val bank: String,
    val accountCurrency: String,
    val fileType: String,
    val tempFilePath: String,
    val fileSha256: String,
)

data class ImportedTransaction(
    val txHash: String,
    val sequence: Int,
    val bookingDate: String,
    val valueDate: String?,
    val amount: String,
    val currency: String,
    val description: String,
    val counterpartyToken: String?,
    val counterpartyTokenKind: String?,
) {
    override fun toString() = "ImportedTransaction(sequence=$sequence, <redacted>)"
}

data class TransactionsImportedPayload(
    val statementId: String,
    val accountId: String,
    val parser: ParserInfo,
    val periodStart: String,
    val periodEnd: String,
    val currency: String,
    val transactionCount: Int,
    val transactions: List<ImportedTransaction>,
) {
    override fun toString() = "TransactionsImportedPayload(statementId=$statementId, count=$transactionCount)"
}

data class StatementParseFailedPayload(
    val statementId: String,
    val accountId: String,
    val parser: ParserInfo,
    val reason: String,
    val message: String,
    val retryable: Boolean,
)

/** Inbound message that must go straight to the DLQ (never retried). Message must not contain PII. */
class NonRetryableEventException(val reason: DlqReason, message: String) : RuntimeException(message)

data class StatementParsedPayload(
    val statementId: String,
    val accountId: String,
    val periodStart: String,
    val periodEnd: String,
    val transactionCount: Int,
)

data class InputRequiredPayload(val statementId: String, val accountId: String, val questionCount: Int)

data class AnswersSubmittedPayload(val statementId: String, val accountId: String, val answerCount: Int)

/** Causes of TransactionsChanged (contracts/schemas/transactions-changed.schema.json). */
enum class ChangeCause {
    STATEMENT_CATEGORIZED, TRANSFER_MATCHED, TRANSFER_ANSWERED, COUNTERPARTY_LABELLED, MANUAL_CORRECTION, STATEMENT_DELETED
}

data class TransactionsChangedPayload(
    val accountIds: List<String>,
    val fromDate: String,
    val toDate: String,
    val cause: String,
    val statementId: String?,
)
