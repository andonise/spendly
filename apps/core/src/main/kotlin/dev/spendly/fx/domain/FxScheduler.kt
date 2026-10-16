package dev.spendly.fx.domain

import dev.spendly.fx.FxService
import dev.spendly.shared.config.SpendlyProperties
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Daily BNR fetch (banking days ~13:00 Romanian time) plus one fetch shortly after startup. */
@Component
class FxScheduler(
    private val fx: FxService,
    private val properties: SpendlyProperties,
    private val taskScheduler: TaskScheduler,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${spendly.fx.bnr.cron:0 30 13 * * MON-FRI}", zone = "\${spendly.fx.bnr.zone:Europe/Bucharest}")
    fun daily() = refresh()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        if (properties.fx.bnr.enabled) taskScheduler.schedule(::refresh, Instant.now().plusSeconds(5))
    }

    private fun refresh() {
        if (!properties.fx.bnr.enabled) return
        try {
            fx.refreshDaily()
        } catch (e: Exception) {
            log.warn("BNR daily rate fetch failed: {} (import rates manually via POST /api/fx-rates/import)", e.javaClass.simpleName)
        }
    }
}
