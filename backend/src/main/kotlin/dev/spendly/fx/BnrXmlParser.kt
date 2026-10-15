package dev.spendly.fx

import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

data class FxRate(val date: LocalDate, val currency: String, val rateToRon: BigDecimal)

/**
 * Parses BNR reference-rate XML (daily `nbrfxrates.xml` and yearly `nbrfxrates{YEAR}.xml` share the format):
 * `<Cube date="2026-10-15"><Rate currency="EUR">4.9750</Rate><Rate currency="HUF" multiplier="100">1.2345</Rate></Cube>`.
 * The rate is RON per 1 unit (value / multiplier). Namespace-agnostic; external entities are disabled.
 */
object BnrXmlParser {
    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    fun parse(input: InputStream): List<FxRate> {
        val reader = factory.createXMLStreamReader(input)
        val out = ArrayList<FxRate>()
        var cubeDate: LocalDate? = null
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
                when (reader.localName) {
                    "Cube" -> cubeDate = LocalDate.parse(reader.getAttributeValue(null, "date"))
                    "Rate" -> {
                        val date = cubeDate ?: continue
                        val currency = reader.getAttributeValue(null, "currency")?.trim()?.uppercase() ?: continue
                        val multiplier = reader.getAttributeValue(null, "multiplier")?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ONE
                        val text = reader.elementText.trim()
                        val value = text.toBigDecimalOrNull() ?: continue // e.g. "-" for unpublished
                        if (value.signum() <= 0 || multiplier.signum() <= 0 || !Regex("^[A-Z]{3}$").matches(currency)) continue
                        out += FxRate(date, currency, value.divide(multiplier, MathContext.DECIMAL64).setScale(8, java.math.RoundingMode.HALF_EVEN))
                    }
                }
            }
        } finally {
            reader.close()
        }
        return out
    }
}
