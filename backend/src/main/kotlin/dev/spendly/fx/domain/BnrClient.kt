package dev.spendly.fx.domain

import dev.spendly.shared.config.SpendlyProperties
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

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
