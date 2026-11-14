package app.moneytracker.insights

import app.moneytracker.data.PeriodSettings
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.YearMonth
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** What a trend bar is labelled with; the layout turns it into words. */
sealed interface SlotLabel {
    data class Day(val date: LocalDate) : SlotLabel
    data class Month(val month: Int) : SlotLabel
    data class Year(val year: Int) : SlotLabel
}

/** A bar of the Insights trend: its period, and the choice a tap on it shows (null: tapping does nothing). */
class TrendSlot(val label: SlotLabel, val range: DateRange, val opens: InsightsChoice?)

/**
 * The trend bars for a choice: the six weeks, months or years up to the shown one; a custom period in weekly chunks
 * (up to six weeks), months (up to a year) or years.
 */
fun trendSlots(choice: InsightsChoice, ps: PeriodSettings): List<TrendSlot> {
    val range = choice.range(ps)
    val ym = choice.month
    fun monthSlot(m: YearMonth) = TrendSlot(SlotLabel.Month(m.month), ps.month(m), choice.copy(span = InsightsSpan.MONTH, periodKey = m.key))
    return when (choice.span) {
        InsightsSpan.WEEK -> (5 downTo 0).map { back ->
            range.shifted(-7 * back).let { r -> TrendSlot(SlotLabel.Day(r.start), r, choice.copy(weekDay = r.start.toEpochDays())) }
        }
        InsightsSpan.MONTH -> (5 downTo 0).map { back -> monthSlot(ym.plus(-back)) }
        InsightsSpan.YEAR -> (5 downTo 0).map { back ->
            (ym.year - back).let { y -> TrendSlot(SlotLabel.Year(y), ps.year(y), choice.copy(periodKey = YearMonth(y, ym.month).key)) }
        }
        InsightsSpan.CUSTOM -> customSlots(range, ps, ::monthSlot)
    }
}

private fun customSlots(range: DateRange, ps: PeriodSettings, monthSlot: (YearMonth) -> TrendSlot): List<TrendSlot> {
    if (range.dayCount() <= 42) {
        // Weekly chunks from the start.
        return generateSequence(range.start) { it.plus(DatePeriod(days = 7)) }.takeWhile { it <= range.endInclusive }.map { s ->
            TrendSlot(SlotLabel.Day(s), DateRange(s, minOf(s.plus(DatePeriod(days = 6)), range.endInclusive)), null)
        }.toList()
    }
    val first = ps.labelFor(range.start)
    val last = ps.labelFor(range.endInclusive)
    val months = generateSequence(first) { it.plus(1) }.takeWhile { it <= last }.toList()
    return if (months.size <= 12) months.map(monthSlot)
    else (first.year..last.year).map { y -> TrendSlot(SlotLabel.Year(y), ps.year(y), null) }
}

/** Every day the trend bars and the shown period cover, so one query feeds them all. */
fun trendRangeOf(slots: List<TrendSlot>, range: DateRange): DateRange =
    DateRange(minOf(slots.first().range.start, range.start), maxOf(slots.last().range.endInclusive, range.endInclusive))
