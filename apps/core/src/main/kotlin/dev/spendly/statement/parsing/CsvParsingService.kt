package dev.spendly.statement.parsing

import dev.spendly.shared.messaging.ConsumerNames
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.ParserInfo
import dev.spendly.shared.messaging.StatementParseFailedPayload
import dev.spendly.shared.messaging.StatementUploadedPayload
import dev.spendly.shared.messaging.Topics
import dev.spendly.shared.messaging.TransactionsImportedPayload
import dev.spendly.shared.outbox.OutboxService
import dev.spendly.shared.outbox.ProcessedEventsRepository
import dev.spendly.statement.FileType
import dev.spendly.statement.StatementStatus
import dev.spendly.statement.domain.UploadStorage
import dev.spendly.statement.persistence.StatementRepository
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Kotlin CSV parser stage (consumer group `spendly-backend-csv-parser`): StatementUploaded (CSV/XLS/XLSX)
 * → sanitize + parse + validate → outbox TransactionsImported or StatementParseFailed → delete the original.
 * PDF is skipped (owned by the pdf-parser) but still recorded in processed_events.
 */
@Service
class CsvParsingService(
    private val processed: ProcessedEventsRepository,
    private val statements: StatementRepository,
    private val storage: UploadStorage,
    private val parsers: List<StatementParser>,
    private val outbox: OutboxService,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(env: EventEnvelope<StatementUploadedPayload>) {
        val eventId = UUID.fromString(env.eventId)
        val p = env.payload
        val statementId = UUID.fromString(p.statementId)
        if (processed.exists(eventId, CONSUMER)) {
            log.info("Duplicate StatementUploaded eventId={} ignored", eventId)
            return
        }
        val fileType = FileType.valueOf(p.fileType)
        val statement = statements.findById(statementId)
        val skipReason = when {
            fileType == FileType.PDF -> "PDF is handled by the pdf-parser"
            statement == null -> "unknown statement"
            statement.accountId.toString() != p.accountId -> "account mismatch"
            statement.status != StatementStatus.UPLOADED -> "statement is ${statement.status}"
            else -> null
        }
        if (skipReason != null) {
            tx.executeWithoutResult { processed.tryInsert(eventId, CONSUMER) }
            log.info("StatementUploaded eventId={} statementId={} skipped: {}", eventId, statementId, skipReason)
            return
        }

        val path = storage.resolve(p.tempFilePath) // UNSAFE_TEMP_FILE_PATH -> DLQ
        val ctx = ParseContext(UUID.fromString(p.accountId), p.accountCurrency, p.bank)
        val outcome: Any = try {
            parse(path, fileType, ctx)
        } catch (f: ParseFailure) {
            f
        }

        val committed = tx.execute {
            if (!processed.tryInsert(eventId, CONSUMER)) return@execute false
            when (outcome) {
                is ParsedStatement -> {
                    val payload = TransactionsImportedPayload(
                        statementId = p.statementId,
                        accountId = p.accountId,
                        parser = outcome.parser,
                        periodStart = outcome.periodStart.toString(),
                        periodEnd = outcome.periodEnd.toString(),
                        currency = outcome.currency,
                        transactionCount = outcome.transactions.size,
                        transactions = TxHashes.toEvent(ctx.accountId, outcome.transactions),
                    )
                    outbox.append(
                        EventEnvelope.create(EventTypes.TRANSACTIONS_IMPORTED, env.correlationId, payload),
                        Topics.TRANSACTIONS_IMPORTED, p.accountId, statementId,
                    )
                }
                is ParseFailure -> {
                    val payload = StatementParseFailedPayload(
                        statementId = p.statementId,
                        accountId = p.accountId,
                        parser = parserFor(fileType),
                        reason = outcome.reason,
                        message = (outcome.message ?: outcome.reason).take(500),
                        retryable = outcome.retryable,
                    )
                    outbox.append(
                        EventEnvelope.create(EventTypes.STATEMENT_PARSE_FAILED, env.correlationId, payload),
                        Topics.STATEMENT_PARSE_FAILED, p.accountId, statementId,
                    )
                }
            }
            true
        } ?: false

        if (!committed) {
            log.info("StatementUploaded eventId={} was processed concurrently; nothing to do", eventId)
            return
        }
        if (outcome is ParsedStatement) {
            storage.deleteAfterSuccess(path) // AC-SD-3: originals are deleted after a successful parse
            log.info("Statement {} parsed: {} transactions (eventId={})", statementId, outcome.transactions.size, eventId)
        } else {
            log.info("Statement {} parse failed: {} (eventId={})", statementId, (outcome as ParseFailure).reason, eventId)
        }
    }

    /** Reads, detects the layout and parses. IO errors propagate (transient: retried, then DLQ). */
    fun parse(path: Path, fileType: FileType, ctx: ParseContext): ParsedStatement {
        if (fileType == FileType.XLS || fileType == FileType.XLSX) {
            throw ParseFailure(
                ParseFailure.UNSUPPORTED_LAYOUT,
                "XLS/XLSX statements are not supported yet; export the statement as CSV",
            )
        }
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: NoSuchFileException) {
            throw ParseFailure(ParseFailure.FILE_NOT_FOUND, "The uploaded file is no longer available")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw ParseFailure(ParseFailure.FILE_UNREADABLE, "The file is not valid UTF-8 text")
        }
        val parser = parsers.firstOrNull { it.detect(text) }
            ?: throw ParseFailure(ParseFailure.UNSUPPORTED_LAYOUT, "Unrecognized CSV layout (no known header row)")
        return try {
            parser.parse(text, ctx)
        } catch (f: ParseFailure) {
            throw f
        } catch (e: RuntimeException) {
            // A parser bug: report the class only (the message could echo file content).
            log.warn("Parser {} failed with {}", parser.info.name, e.javaClass.simpleName)
            throw ParseFailure(ParseFailure.INTERNAL_ERROR, "Unexpected parser error (${e.javaClass.simpleName})", retryable = true)
        }
    }

    private fun parserFor(fileType: FileType) =
        dev.spendly.shared.messaging.ParserInfo(if (fileType == FileType.CSV) "kotlin-csv" else "kotlin-xls", "1.0.0")

    companion object {
        const val CONSUMER = ConsumerNames.CSV_PARSER
    }
}
