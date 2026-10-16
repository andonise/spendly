package dev.spendly.transaction.web

import dev.spendly.transaction.domain.TransactionFilter
import dev.spendly.transaction.domain.TransactionPage
import dev.spendly.transaction.domain.TransactionQueryService
import dev.spendly.transaction.domain.TransactionResponse
import io.swagger.v3.oas.annotations.tags.Tag
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/transactions")
@Tag(name = "transactions")
class TransactionController(private val service: TransactionQueryService) {

    @GetMapping
    fun listTransactions(
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) statementId: UUID?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) transferStatus: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "bookingDate,desc") sort: String,
    ): TransactionPage = service.page(TransactionFilter(accountId, statementId, from, to, category, transferStatus), page, size, sort)

    @GetMapping("/{transactionId}")
    fun getTransaction(@PathVariable transactionId: UUID): TransactionResponse = service.get(transactionId)
}
