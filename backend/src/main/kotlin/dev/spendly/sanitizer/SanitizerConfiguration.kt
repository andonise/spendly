package dev.spendly.sanitizer

import dev.spendly.config.SpendlyProperties
import dev.spendly.crypto.HmacService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@Configuration(proxyBeanMethods = false)
class SanitizerConfiguration {

    @Bean
    fun sanitizer(properties: SpendlyProperties, hmacService: HmacService, jsonMapper: JsonMapper): Sanitizer {
        val rulesFile = properties.contractsPath().resolve("pii-rules.json")
        val rules = jsonMapper.readTree(Files.readString(rulesFile, StandardCharsets.UTF_8))
        return Sanitizer(rules, hmacService)
    }
}

/** The "Description" rule from contracts/README.md; both parsers build (and hash) descriptions this way. */
object Descriptions {
    const val MAX_CODE_POINTS = 500
    const val EMPTY = "N/A"

    /** Steps 2–4: keep the first 500 code points, trim (Python strip semantics), "N/A" if empty. */
    fun finalize(sanitized: String): String {
        val cut = if (sanitized.codePointCount(0, sanitized.length) > MAX_CODE_POINTS) {
            sanitized.substring(0, sanitized.offsetByCodePoints(0, MAX_CODE_POINTS))
        } else {
            sanitized
        }
        return cut.pyStrip().ifEmpty { EMPTY }
    }

    fun fee(mainFinalDescription: String): String = finalize("FEE $mainFinalDescription")
}
