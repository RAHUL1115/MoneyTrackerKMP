package app.moneytracker.ui

import app.moneytracker.data.PeriodSettings
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Totals
import app.moneytracker.domain.YearMonth
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import app.moneytracker.domain.Flow as MoneyFlow

/** How long a period Insights (new layout) and Stats (classic) show. Each layout words the choices itself. */
enum class InsightsSpan { WEEK, MONTH, YEAR, CUSTOM }

/** Which money the figures follow. Classic Stats offers only spending and income. */
enum class InsightsSide {
    SPENDING, INCOME, NET;

    /** The rows the breakdown and top entries draw from: income for [INCOME], spending otherwise. */
    val flow: MoneyFlow get() = if (this == INCOME) MoneyFlow.INCOME else MoneyFlow.EXPENSE

    fun pick(t: Totals): Long = when (this) {
        SPENDING -> t.expense
        INCOME -> t.income
        NET -> t.income - t.expense
    }
}

/** What the breakdown groups by. */
enum class InsightsGroup { CATEGORY, ACCOUNT, TAG }

/**
 * What the person picked on Insights or Stats: span, side, grouping and which period. [weekDay] is any day of the
 * shown week and [customStart] / [customEnd] the custom dates, all as epoch days; [periodKey] is the month (or year).
 */
data class InsightsChoice(
    val span: InsightsSpan,
    val side: InsightsSide,
    val groupBy: InsightsGroup,
    val periodKey: Int,
    val weekDay: Int,
    val customStart: Int,
    val customEnd: Int,
) {
    val month: YearMonth get() = YearMonth.fromKey(periodKey)

    fun range(ps: PeriodSettings): DateRange = when (span) {
        InsightsSpan.WEEK -> weekContaining(LocalDate.fromEpochDays(weekDay), ps)
        InsightsSpan.MONTH -> ps.month(month)
        InsightsSpan.YEAR -> ps.year(month.year)
        InsightsSpan.CUSTOM -> DateRange(LocalDate.fromEpochDays(minOf(customStart, customEnd)), LocalDate.fromEpochDays(maxOf(customStart, customEnd)))
    }

    /** The period before, for the comparisons. */
    fun previousRange(ps: PeriodSettings): DateRange {
        val range = range(ps)
        return when (span) {
            InsightsSpan.WEEK -> range.shifted(-7)
            InsightsSpan.MONTH -> ps.month(month.plus(-1))
            InsightsSpan.YEAR -> ps.year(month.year - 1)
            InsightsSpan.CUSTOM -> range.shifted(-range.dayCount())
        }
    }

    /** One period back ([dir] -1) or forward (+1); a custom period steps by its own length. */
    fun stepped(dir: Int, ps: PeriodSettings): InsightsChoice = when (span) {
        InsightsSpan.WEEK -> copy(weekDay = weekDay + 7 * dir)
        InsightsSpan.MONTH -> copy(periodKey = month.plus(dir).key)
        InsightsSpan.YEAR -> copy(periodKey = month.plus(12 * dir).key)
        InsightsSpan.CUSTOM -> {
            val range = range(ps)
            val len = range.dayCount()
            copy(customStart = range.start.toEpochDays() + len * dir, customEnd = range.endInclusive.toEpochDays() + len * dir)
        }
    }

    /** Plain numbers, so the choice survives the app being closed in the background ([fromInts] reads them back). */
    fun toInts() = intArrayOf(span.ordinal, side.ordinal, groupBy.ordinal, periodKey, weekDay, customStart, customEnd)

    companion object {
        /** A month of spending by category, this period; the week is this week and the custom dates this month so far. */
        fun start(ps: PeriodSettings, today: LocalDate) = InsightsChoice(
            span = InsightsSpan.MONTH,
            side = InsightsSide.SPENDING,
            groupBy = InsightsGroup.CATEGORY,
            periodKey = ps.labelFor(today).key,
            weekDay = today.toEpochDays(),
            customStart = ps.month(ps.labelFor(today)).start.toEpochDays(),
            customEnd = today.toEpochDays(),
        )

        fun fromInts(a: IntArray) = InsightsChoice(InsightsSpan.entries[a[0]], InsightsSide.entries[a[1]], InsightsGroup.entries[a[2]], a[3], a[4], a[5], a[6])
    }
}

/** The week (by the week-start setting) that holds [day]. */
fun weekContaining(day: LocalDate, ps: PeriodSettings): DateRange {
    val back = (day.dayOfWeek.ordinal - ps.weekStart.ordinal + 7) % 7
    val start = day.minus(DatePeriod(days = back))
    return DateRange(start, start.plus(DatePeriod(days = 6)))
}

fun DateRange.dayCount() = endInclusive.toEpochDays() - start.toEpochDays() + 1

fun DateRange.shifted(days: Int) = DateRange(start.plus(DatePeriod(days = days)), endInclusive.plus(DatePeriod(days = days)))
