package app.moneytracker.ui

import app.moneytracker.data.MonthBudget
import app.moneytracker.data.PeriodSettings
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.Totals
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import kotlinx.datetime.LocalDate

/** The shown period's rows: the period itself, the one before, and every day the trend bars cover. */
class InsightsRows(val rows: List<ReportRow>, val previous: List<ReportRow>, val trend: List<ReportRow>)

/** What rows are named by: categories, accounts and each entry's tags. */
class InsightsNames(val catById: Map<String, Category>, val accounts: List<Account>, val tagNames: Map<String, List<String>>)

/** Everything Insights and Stats draw for one choice. Figures follow [InsightsChoice.side]. */
class InsightsState(
    val choice: InsightsChoice,
    val periods: PeriodSettings,
    val today: LocalDate,
    val slots: List<TrendSlot>,
    val budgetMonth: YearMonth,
    val budget: MonthBudget,
    rows: InsightsRows,
    names: InsightsNames,
) {
    val range: DateRange = choice.range(periods)
    val catById: Map<String, Category> = names.catById
    val accountName: Map<String, String> = names.accounts.associate { it.id to it.name }
    val totals: Totals = totals(rows.rows)
    val previousTotals: Totals = totals(rows.previous)
    val parts: List<BreakdownPart> = breakdownOf(choice.groupBy, rows.rows, rows.previous, choice.side.flow, catById, accountName, names.tagNames)
    val top: List<ReportRow> = topEntriesOf(rows.rows, choice.side.flow)
    val trendRows: List<ReportRow> = rows.trend
    val total: Long = choice.side.pick(totals)
    val previousTotal: Long = choice.side.pick(previousTotals)

    /** Days of the period gone so far: none before it starts, all of it once it's over. */
    val elapsedDays: Int = when {
        today < range.start -> 0
        today > range.endInclusive -> range.dayCount()
        else -> today.toEpochDays() - range.start.toEpochDays() + 1
    }
    val dailyAverage: Long = if (elapsedDays > 0) total / elapsedDays else 0L

    /** Where the period is heading at this pace, while it is still running. */
    val projected: Long? = if (elapsedDays in 1 until range.dayCount()) total * range.dayCount() / elapsedDays else null

    /** Net as a percentage of income, when there is income. */
    val savedPercent: Long? = if (totals.income > 0) totals.net * 100 / totals.income else null

    /** A custom period longer than a month, scaled to 30 days. */
    val monthlyAverage: Long? = if (choice.span == InsightsSpan.CUSTOM && range.dayCount() > 31) total * 30 / range.dayCount() else null

    /** Each trend bar's figure, and the bar of the shown period (the last one when it isn't among them). */
    val slotValues: List<Long> = slots.map { s -> choice.side.pick(totals(trendRows.filter { r -> r.date?.let { it in s.range } == true })) }
    val slotHighlight: Int = slots.indexOfFirst { it.range.start == range.start }.takeIf { it >= 0 } ?: slots.lastIndex
}
