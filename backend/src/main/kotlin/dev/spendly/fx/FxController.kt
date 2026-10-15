package dev.spendly.fx

import dev.spendly.common.BadRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.time.LocalDate

@RestController
@RequestMapping("/api/fx-rates")
@Tag(name = "fx-rates")
class FxController(private val fx: FxService) {

    data class ImportResult(val imported: Int)

    data class RateResponse(val currency: String, val date: LocalDate, val baseCurrency: String, val rateToBase: BigDecimal?)

    @Operation(summary = "Import BNR-format XML rates from a local file (offline use)")
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun importRates(@RequestPart("file") file: MultipartFile): ImportResult {
        val count = try {
            file.inputStream.use { fx.importXml(it, FxService.SOURCE_MANUAL) }
        } catch (e: javax.xml.stream.XMLStreamException) {
            throw BadRequestException("INVALID_FX_XML", "The file is not valid BNR rate XML")
        }
        if (count == 0) throw BadRequestException("INVALID_FX_XML", "No rates found in the file")
        return ImportResult(count)
    }

    @Operation(summary = "Rate used for a currency and booking date (the date's rate or the last earlier one)")
    @GetMapping
    fun rate(
        @RequestParam currency: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): RateResponse = RateResponse(currency.uppercase(), date, fx.baseCurrency, fx.rateToBase(currency, date))
}
