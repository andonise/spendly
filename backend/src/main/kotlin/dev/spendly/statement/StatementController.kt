package dev.spendly.statement

import dev.spendly.web.CorrelationIdFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class StatementProgress(val transactionsImported: Long, val transactionsCategorized: Long, val questionsOpen: Long)

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
    /** Present once the statement is CATEGORIZED (Phase 3+); always null in Phase 1. */
    val summary: Any?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class UploadAccepted(val statementId: UUID, val status: StatementStatus, val duplicate: Boolean)

@RestController
@RequestMapping("/api/statements")
@Tag(name = "statements")
class StatementController(
    private val service: StatementService,
    private val events: StatementEventsService,
) {

    @GetMapping
    fun listStatements(
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) status: StatementStatus?,
    ): List<StatementResponse> = service.list(accountId, status).map(::toResponse)

    @Operation(summary = "Upload a statement file for an account (202, or 200 with duplicate=true for a re-upload)")
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadStatement(
        @RequestParam("accountId") accountId: UUID,
        @RequestPart("file") file: MultipartFile,
        request: HttpServletRequest,
    ): ResponseEntity<UploadAccepted> {
        val correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE) as String
        val result = service.upload(accountId, file, correlationId)
        val body = UploadAccepted(result.statement.id, result.statement.status, result.duplicate)
        return if (result.duplicate) {
            ResponseEntity.ok(body)
        } else {
            ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/statements/${body.statementId}")).body(body)
        }
    }

    @GetMapping("/{statementId}")
    fun getStatement(@PathVariable statementId: UUID): StatementResponse = toResponse(service.get(statementId))

    @PostMapping("/{statementId}/retry")
    fun retryStatement(@PathVariable statementId: UUID): ResponseEntity<StatementResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(service.retry(statementId)))

    @Operation(summary = "Server-Sent Events with status changes (current status first, keep-alive every 15 s)")
    @GetMapping("/{statementId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamStatementEvents(@PathVariable statementId: UUID): SseEmitter = events.subscribe(service.get(statementId))

    @Operation(summary = "Questions for this statement (always empty until Phase 3)")
    @GetMapping("/{statementId}/questions")
    fun getStatementQuestions(@PathVariable statementId: UUID): List<Any> {
        service.get(statementId)
        return emptyList()
    }

    private fun toResponse(s: StatementRecord) = StatementResponse(
        id = s.id,
        accountId = s.accountId,
        fileType = s.fileType,
        status = s.status,
        lastCompletedStep = s.lastCompletedStep,
        periodStart = s.periodStart,
        periodEnd = s.periodEnd,
        error = s.error,
        progress = StatementProgress(service.transactionCount(s.id), 0, 0),
        summary = null,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
    )
}
