package dev.spendly.statement.domain

import dev.spendly.account.AccountApi
import dev.spendly.account.AccountRef
import dev.spendly.shared.config.SpendlyProperties
import dev.spendly.shared.messaging.EventEnvelope
import dev.spendly.shared.messaging.EventTypes
import dev.spendly.shared.messaging.StatementUploadedPayload
import dev.spendly.shared.messaging.Topics
import dev.spendly.shared.outbox.OutboxService
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.ConflictException
import dev.spendly.shared.web.FileTooLargeException
import dev.spendly.shared.web.NotFoundException
import dev.spendly.statement.FileType
import dev.spendly.statement.ProcessingStep
import dev.spendly.statement.StatementRecord
import dev.spendly.statement.StatementStatus
import dev.spendly.statement.importing.ImportService
import dev.spendly.statement.persistence.StatementRepository
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile

data class UploadResult(val statement: StatementRecord, val duplicate: Boolean)

@Service
class StatementService(
    private val statements: StatementRepository,
    private val accounts: AccountApi,
    private val storage: UploadStorage,
    private val outbox: OutboxService,
    private val stateMachine: StatementStateMachine,
    private val importService: ImportService,
    private val properties: SpendlyProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun get(id: UUID): StatementRecord = statements.findById(id) ?: throw NotFoundException("Statement $id")

    fun list(accountId: UUID?, status: StatementStatus?): List<StatementRecord> = statements.list(accountId, status)

    /**
     * Upload: hash + type check, dedupe by (account, SHA-256), save as `<statementId>.<ext>`, then ONE transaction
     * with the `statements` row (UPLOADED) and the outbox StatementUploaded row.
     */
    fun upload(accountId: UUID, file: MultipartFile, correlationId: String): UploadResult {
        val account = accounts.find(accountId) ?: throw NotFoundException("Account $accountId")
        val maxBytes = properties.upload.maxSizeMb * 1024 * 1024
        if (file.size > maxBytes) throw FileTooLargeException("The file exceeds ${properties.upload.maxSizeMb} MB")
        if (file.isEmpty) throw dev.spendly.shared.web.BadRequestException("EMPTY_FILE", "The file is empty")

        val incoming = storage.receive(file.inputStream)
        try {
            val fileType = UploadStorage.detectType(file.originalFilename, incoming.head)
            val existing = statements.findByAccountAndHash(accountId, incoming.sha256)
            if (existing != null) {
                if (existing.status == StatementStatus.FAILED && existing.lastCompletedStep == ProcessingStep.UPLOAD &&
                    !storage.exists(existing.tempFileName)
                ) {
                    // The same file again after a failed parse whose original is gone: restart it.
                    storage.moveInto(incoming, existing.tempFileName)
                    restartFromUpload(existing, account, correlationId)
                    return UploadResult(get(existing.id), duplicate = false)
                }
                storage.discard(incoming.path)
                log.info("Duplicate upload for account {} matches statement {}", accountId, existing.id)
                return UploadResult(existing, duplicate = true)
            }

            val statementId = UUID.randomUUID()
            val fileName = "$statementId.${fileType.extension}"
            val stored = storage.moveInto(incoming, fileName)
            try {
                tx.executeWithoutResult {
                    statements.insert(statementId, accountId, fileType, incoming.sha256, correlationId)
                    outbox.append(uploadedEvent(statementId, account, fileType, fileName, incoming.sha256, correlationId),
                        Topics.STATEMENT_UPLOADED, accountId.toString(), statementId)
                }
            } catch (e: DuplicateKeyException) {
                storage.discard(stored) // concurrent identical upload won the race
                val winner = statements.findByAccountAndHash(accountId, incoming.sha256)!!
                return UploadResult(winner, duplicate = true)
            } catch (e: Exception) {
                storage.discard(stored)
                throw e
            }
            log.info("Statement {} uploaded for account {} ({})", statementId, accountId, fileType)
            return UploadResult(get(statementId), duplicate = false)
        } finally {
            storage.discard(incoming.path) // no-op when it was moved
        }
    }

    /**
     * Retry a FAILED statement from `last_completed_step`:
     * - UPLOAD (or none): the original must still exist; re-publish StatementUploaded (status → UPLOADED).
     * - PARSE: transactions are stored; fill missing FX rates (status → PARSED).
     */
    fun retry(id: UUID): StatementRecord {
        val statement = get(id)
        if (statement.status != StatementStatus.FAILED) {
            throw ConflictException("STATEMENT_NOT_FAILED", "Only FAILED statements can be retried (status is ${statement.status})")
        }
        when (statement.lastCompletedStep) {
            null, ProcessingStep.UPLOAD -> {
                if (!storage.exists(statement.tempFileName)) {
                    throw ConflictException("ORIGINAL_FILE_DELETED", "The original file is no longer available; upload it again")
                }
                val account = accounts.find(statement.accountId) ?: throw NotFoundException("Account ${statement.accountId}")
                restartFromUpload(statement, account, statement.correlationId)
            }
            ProcessingStep.PARSE -> tx.executeWithoutResult {
                if (!importService.completeFx(id)) {
                    throw ConflictException("FX_RATE_MISSING", "Exchange rates are still missing; import them, then retry")
                }
                stateMachine.transition(id, StatementStatus.FAILED, StatementStatus.PARSED, ProcessingStep.PARSE)
                importService.announceParsed(id, statement.accountId, statement.periodStart!!, statement.periodEnd!!, statement.correlationId)
            }
            // Failed during categorization (e.g. dead-lettered): back to PARSED; the categorizer re-runs detection
            // and categorization (answers already applied and user categories are kept).
            else -> tx.executeWithoutResult {
                stateMachine.transition(id, StatementStatus.FAILED, StatementStatus.PARSED, ProcessingStep.PARSE)
                importService.announceParsed(id, statement.accountId, statement.periodStart!!, statement.periodEnd!!, statement.correlationId)
            }
        }
        log.info("Statement {} retried from {}", id, statement.lastCompletedStep)
        return get(id)
    }

    private fun restartFromUpload(statement: StatementRecord, account: AccountRef, correlationId: String) {
        tx.executeWithoutResult {
            stateMachine.transition(statement.id, StatementStatus.FAILED, StatementStatus.UPLOADED, ProcessingStep.UPLOAD)
            outbox.append(
                uploadedEvent(statement.id, account, statement.fileType, statement.tempFileName, statement.fileHash, correlationId),
                Topics.STATEMENT_UPLOADED, account.id.toString(), statement.id,
            )
        }
    }

    private fun uploadedEvent(
        statementId: UUID,
        account: AccountRef,
        fileType: FileType,
        fileName: String,
        sha256: String,
        correlationId: String,
    ) = EventEnvelope.create(
        EventTypes.STATEMENT_UPLOADED,
        correlationId,
        StatementUploadedPayload(
            statementId = statementId.toString(),
            accountId = account.id.toString(),
            bank = account.bank,
            accountCurrency = account.currency,
            fileType = fileType.name,
            tempFilePath = fileName,
            fileSha256 = sha256,
        ),
    )
}
