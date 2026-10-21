package dev.spendly.report.web

import dev.spendly.report.domain.CoverageView
import dev.spendly.report.domain.Granularity
import dev.spendly.report.domain.ReportService
import dev.spendly.report.domain.ReportView
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
@Tag(name = "reports")
class ReportController(private val service: ReportService) {

    @GetMapping("/reports")
    fun getReport(
        @RequestParam granularity: Granularity,
        @RequestParam from: LocalDate,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(name = "accountId", required = false) accountIds: List<UUID>?,
    ): ReportView = service.get(granularity, from, to, accountIds)

    @GetMapping("/reports/{reportId}")
    fun getReportById(@PathVariable reportId: UUID): ReportView = service.getById(reportId)

    @GetMapping("/coverage")
    fun getCoverage(
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        @RequestParam(name = "accountId", required = false) accountIds: List<UUID>?,
    ): CoverageView = CoverageView.of(service.coverage(from, to, accountIds))
}
