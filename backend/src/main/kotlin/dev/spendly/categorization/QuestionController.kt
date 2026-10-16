package dev.spendly.categorization

import dev.spendly.transfer.CounterpartyResponse
import dev.spendly.transfer.CounterpartyService
import dev.spendly.transfer.CounterpartyUpdate
import dev.spendly.web.CorrelationIdFilter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
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
import java.util.UUID

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

@RestController
@RequestMapping("/api/counterparties")
@Tag(name = "counterparties")
class CounterpartyController(private val service: CounterpartyService) {

    @GetMapping
    fun listCounterparties(): List<CounterpartyResponse> = service.list()

    @PutMapping("/{token}")
    fun updateCounterparty(@PathVariable token: String, @RequestBody body: CounterpartyUpdate, request: HttpServletRequest): CounterpartyResponse {
        if (!TOKEN.matches(token)) {
            throw dev.spendly.common.BadRequestException("INVALID_TOKEN", "token must be 64 lowercase hex characters",
                listOf(dev.spendly.common.FieldViolation("token", "must match ^[0-9a-f]{64}$")))
        }
        return service.update(token, body, request.getAttribute(CorrelationIdFilter.ATTRIBUTE) as String)
    }

    companion object {
        private val TOKEN = Regex("^[0-9a-f]{64}$")
    }
}
