package dev.spendly.fx

import dev.spendly.config.SpendlyProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.http.HttpClient
import java.time.Instant
import java.time.LocalDate

/** Fetches BNR XML over HTTP. The only outbound request the app makes; it sends no user data. */
@Component
class BnrClient(private val properties: SpendlyProperties) {
    private val restClient: RestClient by lazy {
        val timeout = properties.fx.bnr.timeout
        val http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build()
        RestClient.builder().requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(timeout) }).build()
    }

    fun fetchDaily(): List<FxRate> = fetch(properties.fx.bnr.dailyUrl)

    fun fetchYear(year: Int): List<FxRate> = fetch(properties.fx.bnr.yearlyUrlTemplate.replace("{year}", year.toString()))

    private fun fetch(url: String): List<FxRate> {
        val bytes = restClient.get().uri(url).retrieve().body(ByteArray::class.java) ?: return emptyList()
        return BnrXmlParser.parse(ByteArrayInputStream(bytes))
    }
}

/**
 * FX rates for `amount_base` (plan section 8): BNR rate for the booking date, or the last earlier one
 * (up to `spendly.fx.max-lookback-days`). The base currency itself has rate 1.
 */
@Service
class FxService(
    private val repository: FxRateRepository,
    private val client: BnrClient,
    private val properties: SpendlyProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val baseCurrency: String = properties.baseCurrency.uppercase()

    /** Units of base currency per 1 unit of [currency] on [date]; null when no rate is known. */
    fun rateToBase(currency: String, date: LocalDate): BigDecimal? {
        val cur = currency.uppercase()
        if (cur == baseCurrency) return BigDecimal.ONE
        val ronPerCur = ronRate(cur, date) ?: return null
        val ronPerBase = ronRate(baseCurrency, date) ?: return null
        return ronPerCur.divide(ronPerBase, MathContext.DECIMAL64).setScale(8, RoundingMode.HALF_EVEN)
    }

    private fun ronRate(currency: String, date: LocalDate): BigDecimal? =
        if (currency == "RON") BigDecimal.ONE else repository.findRonRate(currency, date, properties.fx.maxLookbackDays)?.second

    fun toBase(amount: BigDecimal, rate: BigDecimal): BigDecimal = amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN)

    /** Imports BNR-format XML (manual import for offline use, or the BNR fetch). Returns the number of rates. */
    fun importXml(input: InputStream, source: String): Int {
        val rates = BnrXmlParser.parse(input)
        store(rates, source)
        return rates.size
    }

    private fun store(rates: List<FxRate>, source: String) {
        tx.executeWithoutResult { rates.forEach { repository.upsert(it, source) } }
    }

    fun refreshDaily(): Int {
        val rates = client.fetchDaily()
        store(rates, SOURCE_BNR)
        log.info("Stored {} BNR rates", rates.size)
        return rates.size
    }

    /**
     * Best effort, outside any DB transaction: when rates are missing for the given dates, download the
     * BNR yearly files for those years. Network errors are logged, never thrown.
     */
    fun ensureRates(needed: Collection<Pair<String, LocalDate>>) {
        if (!properties.fx.bnr.enabled) return
        val missingYears = needed.filter { (cur, date) -> rateToBase(cur, date) == null }.map { it.second.year }.toSortedSet()
        for (year in missingYears) {
            try {
                store(client.fetchYear(year), SOURCE_BNR)
                log.info("Fetched BNR yearly rates for {}", year)
            } catch (e: Exception) {
                log.warn("Could not fetch BNR yearly rates for {}: {}", year, e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val SOURCE_BNR = "BNR"
        const val SOURCE_MANUAL = "MANUAL"
    }
}

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
