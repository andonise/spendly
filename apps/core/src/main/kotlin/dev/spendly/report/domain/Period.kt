package dev.spendly.report.domain

import dev.spendly.shared.config.SpendlyProperties
import dev.spendly.shared.web.BadRequestException
import dev.spendly.shared.web.FieldViolation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class Granularity { DAY, WEEK, MONTH, CUSTOM }

/**
 * How long a period is, which decides the insight rules that apply (plan Phase 4 table): DAY = 1 day,
 * WEEK = 2 to 27 days, MONTH = 28 days or more ("Month+"). A CUSTOM range gets the scale of its length.
 */
enum class Scale { DAY, WEEK, MONTH }

data class DateRange(val from: LocalDate, val to: LocalDate) {
    init {
        require(!to.isBefore(from)) { "to before from" }
    }

    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    fun contains(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)

    fun overlaps(o: DateRange) = !o.to.isBefore(from) && !o.from.isAfter(to)

    fun dates(): Sequence<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
}

/** A validated report period (plan section 5: day / week / month / custom range). */
data class Period(val granularity: Granularity, val range: DateRange) {
    val start: LocalDate get() = range.from
    val end: LocalDate get() = range.to

    val scale: Scale
        get() = when {
            range.days == 1L -> Scale.DAY
            range.days < 28 -> Scale.WEEK
            else -> Scale.MONTH
        }

    /**
     * Previous periods of the same length, most recent first: DAY → the same weekday in the previous [n] weeks,
     * WEEK → previous weeks, MONTH → previous calendar months, CUSTOM → back-to-back ranges of the same length.
     */
    fun baseline(n: Int): List<Period> = (1..n).map { k ->
        when (granularity) {
            Granularity.DAY -> day(start.minusWeeks(k.toLong()))
            Granularity.WEEK -> Period(Granularity.WEEK, DateRange(start.minusWeeks(k.toLong()), end.minusWeeks(k.toLong())))
            Granularity.MONTH -> month(YearMonth.from(start).minusMonths(k.toLong()))
            Granularity.CUSTOM -> {
                val shift = range.days * k
                Period(Granularity.CUSTOM, DateRange(start.minusDays(shift), end.minusDays(shift)))
            }
        }
    }

    /** The period right before this one (for "change vs the previous period"). */
    fun previous(): Period = when (granularity) {
        Granularity.DAY -> day(start.minusDays(1))
        else -> baseline(1).single()
    }

    fun baselineSize(settings: SpendlyProperties.Reports): Int = when (granularity) {
        Granularity.DAY -> settings.baselineDays
        Granularity.WEEK -> settings.baselineWeeks
        Granularity.MONTH -> settings.baselineMonths
        Granularity.CUSTOM -> settings.baselineMonths
    }

    companion object {
        fun day(d: LocalDate) = Period(Granularity.DAY, DateRange(d, d))

        fun month(m: YearMonth) = Period(Granularity.MONTH, DateRange(m.atDay(1), m.atEndOfMonth()))

        /** Validates a request; `to` may be omitted for DAY, WEEK and MONTH. Throws 400 with field errors. */
        fun of(granularity: Granularity, from: LocalDate, to: LocalDate?, maxCustomDays: Long): Period {
            fun bad(field: String, message: String): Nothing =
                throw BadRequestException("VALIDATION_ERROR", "Invalid $field: $message", listOf(FieldViolation(field, message)))

            val expectedTo = when (granularity) {
                Granularity.DAY -> from
                Granularity.WEEK -> {
                    if (from.dayOfWeek != DayOfWeek.MONDAY) bad("from", "a WEEK starts on a Monday")
                    from.plusDays(6)
                }
                Granularity.MONTH -> {
                    if (from.dayOfMonth != 1) bad("from", "a MONTH starts on the 1st")
                    YearMonth.from(from).atEndOfMonth()
                }
                Granularity.CUSTOM -> to ?: bad("to", "required for CUSTOM")
            }
            if (to != null && to != expectedTo) bad("to", "must be $expectedTo for $granularity")
            if (expectedTo.isBefore(from)) bad("to", "must not be before from")
            val range = DateRange(from, expectedTo)
            if (range.days > maxCustomDays) bad("to", "at most $maxCustomDays days")
            return Period(granularity, range)
        }
    }
}
