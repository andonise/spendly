package dev.spendly

import dev.spendly.config.SpendlyProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SpendlyProperties::class)
class SpendlyBackendApplication

fun main(args: Array<String>) {
	runApplication<SpendlyBackendApplication>(*args)
}
