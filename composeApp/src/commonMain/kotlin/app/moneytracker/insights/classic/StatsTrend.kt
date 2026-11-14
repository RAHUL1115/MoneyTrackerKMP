package app.moneytracker.insights.classic

import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.classify
import app.moneytracker.insights.InsightsSpan
import app.moneytracker.insights.InsightsState
import app.moneytracker.insights.dayCount
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Bars for the Stats trend card, and the month a tapped bar opens, if any. */
internal class StatsTrend(
    val values: List<Long>, val labels: List<String>, val highlight: Int?, val subtitle: String,
    val open: ((Int) -> YearMonth)?,
)

/** Stats charts the six months up to the shown one for Monthly, a year by month, and other periods by day or month. */
internal fun statsTrendOf(state: InsightsState): StatsTrend {
    val choice = state.choice
    val ps = state.periods
    val range = state.range
    val ym = choice.month
    val shown = if (choice.span == InsightsSpan.MONTH) DateRange(ps.month(ym.plus(-5)).start, range.endInclusive) else range
    val dated = state.trendRows.filter { classify(it) == choice.side.flow }.mapNotNull { r -> r.date?.takeIf { it in shown }?.let { it to r.amount } }
    fun byMonth(months: List<YearMonth>): List<Long> {
        val sums = dated.groupBy({ ps.labelFor(it.first) }, { it.second }).mapValues { (_, v) -> v.sum() }
        return months.map { sums[it] ?: 0L }
    }
    fun monthBars(months: List<YearMonth>, highlight: Int?, subtitle: String) =
        StatsTrend(byMonth(months), months.map { monthShort(it.month) }, highlight, subtitle) { i -> months[i] }
    val thisMonth = ps.labelFor(state.today)
    return when {
        choice.span == InsightsSpan.MONTH -> monthBars((5 downTo 0).map { ym.plus(-it) }, 5, tr("Last 6 months"))
        choice.span == InsightsSpan.YEAR -> (1..12).map { YearMonth(ym.year, it) }.let { monthBars(it, it.indexOf(thisMonth).takeIf { i -> i >= 0 }, tr("By month")) }
        range.dayCount() <= 62 -> dayBars(range, state.today, dated)
        else -> {
            val months = generateSequence(ps.labelFor(range.start)) { it.plus(1) }.takeWhile { it <= ps.labelFor(range.endInclusive) }.toList()
            monthBars(months, months.indexOf(thisMonth).takeIf { it >= 0 }, tr("By month"))
        }
    }
}

private fun dayBars(range: DateRange, today: LocalDate, dated: List<Pair<LocalDate, Long>>): StatsTrend {
    val sums = dated.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
    val days = (0 until range.dayCount()).map { range.start.plus(DatePeriod(days = it)) }
    val every = (days.size + 7) / 8   // about eight labels
    return StatsTrend(days.map { sums[it] ?: 0L }, days.mapIndexed { i, d -> if (i % every == 0) d.dayOfMonth.toString() else "" },
        days.indexOf(today).takeIf { it >= 0 }, tr("By day"), null)
}
