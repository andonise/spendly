package dev.spendly.fx.persistence

import dev.spendly.fx.domain.FxRate
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class FxRateRepository(private val jdbc: JdbcClient) {

    fun upsert(rate: FxRate, source: String) {
        jdbc.sql(
            """
            INSERT INTO fx_rates (date, currency, rate_to_base, source) VALUES (:date, :currency, :rate, :source)
            ON CONFLICT (currency, date) DO UPDATE SET rate_to_base = EXCLUDED.rate_to_base, source = EXCLUDED.source,
                fetched_at = now()
            """.trimIndent(),
        ).param("date", rate.date).param("currency", rate.currency).param("rate", rate.rateToRon)
            .param("source", source).update()
    }

    /** RON per unit of [currency] on [date], or the last earlier rate within [lookbackDays] (weekends, holidays). */
    fun findRonRate(currency: String, date: LocalDate, lookbackDays: Long): Pair<LocalDate, BigDecimal>? =
        jdbc.sql(
            """
            SELECT date, rate_to_base FROM fx_rates
            WHERE currency = :currency AND date <= :date AND date >= :earliest
            ORDER BY date DESC LIMIT 1
            """.trimIndent(),
        ).param("currency", currency).param("date", date).param("earliest", date.minusDays(lookbackDays))
            .query { rs, _ -> rs.getObject("date", LocalDate::class.java) to rs.getBigDecimal("rate_to_base") }
            .optional().orElse(null)
}
