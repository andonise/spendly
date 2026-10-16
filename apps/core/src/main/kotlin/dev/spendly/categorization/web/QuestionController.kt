package dev.spendly.categorization.web

import dev.spendly.categorization.domain.AnswerRequest
import dev.spendly.categorization.domain.AnswersSubmission
import dev.spendly.categorization.domain.QuestionResponse
import dev.spendly.categorization.domain.QuestionService
import dev.spendly.shared.web.CorrelationIdFilter
import dev.spendly.statement.StatementResponse
import dev.spendly.statement.StatementViews
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/questions")
@Tag(name = "questions")
class QuestionController(private val service: QuestionService) {

    @GetMapping
    fun listOpenQuestions(@RequestParam(required = false) status: String?): List<QuestionResponse> = service.list(status)

    @PostMapping("/{questionId}/answer")
    fun answerQuestion(
        @PathVariable questionId: UUID,
        @RequestBody body: AnswerRequest,
        request: HttpServletRequest,
    ): ResponseEntity<QuestionResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(service.answerQuestion(questionId, body, request.getAttribute(CorrelationIdFilter.ATTRIBUTE) as String))
}

/** The statement-scoped question endpoints (`/api/statements/{statementId}/questions|answers`, tag questions). */
@RestController
@RequestMapping("/api/statements")
@Tag(name = "questions")
class StatementQuestionsController(private val service: QuestionService, private val views: StatementViews) {

    @Operation(summary = "Questions for this statement (open ones first)")
    @GetMapping("/{statementId}/questions")
    fun getStatementQuestions(@PathVariable statementId: UUID): List<QuestionResponse> =
        service.forStatement(statementId)

    @Operation(summary = "Submit answers for every open question; 202, or 409 STATEMENT_NOT_IN_NEEDS_INPUT")
    @PostMapping("/{statementId}/answers")
    fun submitAnswers(
        @PathVariable statementId: UUID,
        @RequestBody body: AnswersSubmission,
        request: HttpServletRequest,
    ): ResponseEntity<StatementResponse> {
        val correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE) as String
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(views.of(service.submitAnswers(statementId, body, correlationId)))
    }
}
