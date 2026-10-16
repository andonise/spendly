package dev.spendly.transfer.web

import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.CorrelationIdFilter
import dev.spendly.shared.web.FieldViolation
import dev.spendly.transfer.domain.CounterpartyResponse
import dev.spendly.transfer.domain.CounterpartyService
import dev.spendly.transfer.domain.CounterpartyUpdate
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
@RequestMapping("/api/counterparties")
@Tag(name = "counterparties")
class CounterpartyController(private val service: CounterpartyService) {

    @GetMapping
    fun listCounterparties(): List<CounterpartyResponse> = service.list()

    @PutMapping("/{token}")
    fun updateCounterparty(@PathVariable token: String, @RequestBody body: CounterpartyUpdate, request: HttpServletRequest): CounterpartyResponse {
        if (!TOKEN.matches(token)) {
            throw dev.spendly.shared.web.BadRequestException("INVALID_TOKEN", "token must be 64 lowercase hex characters",
                listOf(dev.spendly.shared.web.FieldViolation("token", "must match ^[0-9a-f]{64}$")))
        }
        return service.update(token, body, request.getAttribute(CorrelationIdFilter.ATTRIBUTE) as String)
    }

    companion object {
        private val TOKEN = Regex("^[0-9a-f]{64}$")
    }
}
