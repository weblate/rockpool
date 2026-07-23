package io.rebble.libpebblecommon.sailfish.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal enum class RecurFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

internal data class ParsedRule(
    val frequency: RecurFrequency,
    val interval: Int,
    val count: Int?,
    val until: Instant?,
    val byDay: List<DayOfWeek>,
    val byDayPos: List<Int>,
    val byMonthDay: List<Int>,
    val byMonth: List<Int>,
)

/**
 * Expands mkcal recurrence rows. mkcal stores KCalendarCore RecurrenceRule fields:
 * Frequency 4..7 = daily/weekly/monthly/yearly, ByDay = space-separated Qt weekday
 * numbers (1=Mon..7=Sun) with positions in ByDayPos (0 = every).
 */
internal object RecurrenceExpander {
    const val MAX_ITERATIONS = 1000

    /** null = rule uses features we can't expand (caller falls back to base occurrence). */
    fun parse(row: MkcalRecurRow): ParsedRule? {
        if (row.hasUnsupportedParts) return null
        val frequency = when (row.frequency) {
            4 -> RecurFrequency.DAILY
            5 -> RecurFrequency.WEEKLY
            6 -> RecurFrequency.MONTHLY
            7 -> RecurFrequency.YEARLY
            else -> return null
        }
        val byDayNums = parseInts(row.byDay) ?: return null
        if (byDayNums.any { it !in 1..7 }) return null
        val byDayPos = parseInts(row.byDayPos) ?: return null
        val byMonthDay = parseInts(row.byMonthDay) ?: return null
        if (byMonthDay.any { it == 0 || it !in -31..31 }) return null
        val byMonth = parseInts(row.byMonth) ?: return null
        if (byMonth.any { it !in 1..12 }) return null
        // Weekly BYMONTHDAY has no sane expansion here.
        if (frequency == RecurFrequency.WEEKLY && byMonthDay.isNotEmpty()) return null
        return ParsedRule(
            frequency = frequency,
            interval = row.interval.coerceAtLeast(1),
            count = row.count.takeIf { it > 0 },
            until = row.until.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
            byDay = byDayNums.map { DayOfWeek.of(it) },
            byDayPos = byDayPos,
            byMonthDay = byMonthDay,
            byMonth = byMonth,
        )
    }

    /** Occurrence starts with lowInclusive <= start < highExclusive, base time-of-day preserved. */
    fun expand(
        rule: ParsedRule,
        baseStart: ZonedDateTime,
        lowInclusive: Instant,
        highExclusive: Instant,
    ): List<ZonedDateTime> {
        val zone = baseStart.zone
        val time = baseStart.toLocalTime()
        val baseDate = baseStart.toLocalDate()
        val baseInstant = baseStart.toInstant()
        val results = mutableListOf<ZonedDateTime>()
        var occurrences = 0
        var iterations = 0
        // COUNT must be tallied from the base occurrence; otherwise skip ahead to the window.
        var period = if (rule.count == null) {
            fastForwardPeriods(rule, baseDate, lowInclusive, zone)
        } else {
            0L
        }

        loop@ while (iterations < MAX_ITERATIONS) {
            iterations++
            val periodStart = periodStartDate(rule, baseDate, period)
            if (periodStart.atStartOfDay(zone).toInstant() > highExclusive) break
            val candidates = candidatesFor(rule, baseDate, baseStart.dayOfWeek, periodStart)
                .filter { matchesFilters(rule, it) }
                .map { ZonedDateTime.of(it, time, zone) }
                .sortedBy { it.toInstant() }
            for (candidate in candidates) {
                iterations++
                if (iterations >= MAX_ITERATIONS) break@loop
                val instant = candidate.toInstant()
                if (instant < baseInstant) continue
                if (rule.until != null && instant > rule.until) break@loop
                if (rule.count != null) {
                    occurrences++
                    if (occurrences > rule.count) break@loop
                }
                if (instant >= lowInclusive && instant < highExclusive) results.add(candidate)
            }
            period++
        }
        return results
    }

    private fun periodStartDate(rule: ParsedRule, baseDate: LocalDate, period: Long): LocalDate =
        when (rule.frequency) {
            RecurFrequency.DAILY -> baseDate.plusDays(period * rule.interval)
            RecurFrequency.WEEKLY -> baseDate
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(period * rule.interval)
            RecurFrequency.MONTHLY -> baseDate.withDayOfMonth(1).plusMonths(period * rule.interval)
            RecurFrequency.YEARLY -> baseDate.withDayOfYear(1).plusYears(period * rule.interval)
        }

    private fun candidatesFor(
        rule: ParsedRule,
        baseDate: LocalDate,
        baseDayOfWeek: DayOfWeek,
        periodStart: LocalDate,
    ): List<LocalDate> = when (rule.frequency) {
        RecurFrequency.DAILY -> listOf(periodStart)
        RecurFrequency.WEEKLY -> {
            val days = rule.byDay.ifEmpty { listOf(baseDayOfWeek) }
            days.map { periodStart.plusDays((it.value - 1).toLong()) }
        }
        RecurFrequency.MONTHLY -> monthlyCandidates(rule, baseDate.dayOfMonth, periodStart)
        RecurFrequency.YEARLY -> {
            val months = rule.byMonth.ifEmpty { listOf(baseDate.monthValue) }
            months.flatMap { m ->
                monthlyCandidates(rule, baseDate.dayOfMonth, LocalDate.of(periodStart.year, m, 1))
            }
        }
    }

    private fun monthlyCandidates(
        rule: ParsedRule,
        baseDayOfMonth: Int,
        monthStart: LocalDate,
    ): List<LocalDate> {
        val length = monthStart.lengthOfMonth()
        return when {
            rule.byMonthDay.isNotEmpty() -> rule.byMonthDay.mapNotNull { dom ->
                val day = if (dom < 0) length + dom + 1 else dom
                if (day in 1..length) monthStart.withDayOfMonth(day) else null
            }
            rule.byDay.isNotEmpty() -> rule.byDay.flatMapIndexed { i, dow ->
                when (val pos = rule.byDayPos.getOrElse(i) { 0 }) {
                    // pos 0 = every matching weekday in the month
                    0 -> (1..5).mapNotNull { n ->
                        monthStart.with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
                            .takeIf { it.month == monthStart.month }
                    }
                    else -> monthStart.with(TemporalAdjusters.dayOfWeekInMonth(pos, dow))
                        .takeIf { it.month == monthStart.month }
                        ?.let { listOf(it) } ?: emptyList()
                }
            }
            else ->
                if (baseDayOfMonth <= length) listOf(monthStart.withDayOfMonth(baseDayOfMonth))
                else emptyList()
        }
    }

    // BYDAY on DAILY and BYMONTH on sub-yearly frequencies act as filters (RFC 5545).
    private fun matchesFilters(rule: ParsedRule, date: LocalDate): Boolean {
        if (rule.frequency == RecurFrequency.DAILY &&
            rule.byDay.isNotEmpty() && date.dayOfWeek !in rule.byDay
        ) return false
        if (rule.frequency != RecurFrequency.YEARLY &&
            rule.byMonth.isNotEmpty() && date.monthValue !in rule.byMonth
        ) return false
        return true
    }

    private fun fastForwardPeriods(
        rule: ParsedRule,
        baseDate: LocalDate,
        lowInclusive: Instant,
        zone: java.time.ZoneId,
    ): Long {
        val lowDate = lowInclusive.atZone(zone).toLocalDate()
        if (lowDate <= baseDate) return 0L
        val units = when (rule.frequency) {
            RecurFrequency.DAILY -> ChronoUnit.DAYS.between(baseDate, lowDate)
            RecurFrequency.WEEKLY -> ChronoUnit.WEEKS.between(baseDate, lowDate)
            RecurFrequency.MONTHLY ->
                ChronoUnit.MONTHS.between(baseDate.withDayOfMonth(1), lowDate.withDayOfMonth(1))
            RecurFrequency.YEARLY -> (lowDate.year - baseDate.year).toLong()
        }
        return ((units / rule.interval) - 1).coerceAtLeast(0L)
    }

    /** Space-separated ints as written by mkcal; null input = empty; garbage = null (unsupported). */
    private fun parseInts(value: String?): List<Int>? {
        if (value.isNullOrBlank()) return emptyList()
        val parts = value.trim().split(' ').filter { it.isNotBlank() }
        val ints = parts.mapNotNull { it.toIntOrNull() }
        return if (ints.size == parts.size) ints else null
    }
}
