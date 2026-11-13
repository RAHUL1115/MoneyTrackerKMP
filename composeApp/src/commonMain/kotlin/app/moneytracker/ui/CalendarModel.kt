package app.moneytracker.ui

import app.moneytracker.domain.DateRange
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.domain.totals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The first day the calendar draws: the week-start day on or before [range]'s first day. */
fun calendarStart(range: DateRange, weekStart: DayOfWeek): LocalDate {
    var first = range.start
    while (first.dayOfWeek != weekStart) first = first.minus(DatePeriod(days = 1))
    return first
}

/** The six weeks a calendar can show for [range], neighbouring months' days included. */
fun calendarGrid(range: DateRange, weekStart: DayOfWeek): DateRange {
    val first = calendarStart(range, weekStart)
    return DateRange(first, first.plus(DatePeriod(days = 41)))
}

/** A month as a calendar: the month's totals, each day's totals and entry count, and the entries to list per day. */
class CalendarState(
    val range: DateRange,
    val weekStart: DayOfWeek,
    val totals: Totals,
    /** Every day of the six-week grid, neighbouring months included. */
    val dayTotals: Map<LocalDate, Totals>,
    val dayCounts: Map<LocalDate, Int>,
    private val entries: List<Txn>,
    val lookups: EntryLookups,
) {
    val firstDay: LocalDate get() = calendarStart(range, weekStart)

    /** [day]'s entries as listed: each transfer once. */
    fun entriesOn(day: LocalDate): List<Txn> = entries.filter { it.date == day && it.kind.isListed }
}

/** The Calendar view in both layouts: the month grid with each day's amounts, and one day's entries. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarModel(activity: StateFlow<ActivityState>, private val feed: EntryFeed, scope: CoroutineScope) {
    private data class Month(val query: EntryQuery, val weekStart: DayOfWeek)

    val state: StateFlow<CalendarState> = activity.map { Month(it.query.copy(kind = ActKind.ALL), it.periods.weekStart) }
        .distinctUntilChanged()
        .flatMapLatest(::calendarFor)
        .stateIn(scope, SharingStarted.Eagerly, initialState(activity.value))

    private fun calendarFor(m: Month): Flow<CalendarState> {
        val range = m.query.range
        val filter = m.query.filter
        val grid = calendarGrid(range, m.weekStart)
        return combine(feed.rows(range, filter), feed.rows(grid, filter), feed.entries(grid, filter), feed.lookups) { rows, gridRows, entries, lookups ->
            build(range, m.weekStart, rows, gridRows, entries, lookups)
        }
    }

    private fun initialState(a: ActivityState): CalendarState {
        val grid = calendarGrid(a.range, a.periods.weekStart)
        return build(a.range, a.periods.weekStart, feed.rowsNow(a.range, a.filter), feed.rowsNow(grid, a.filter), emptyList(), feed.lookupsNow())
    }

    private fun build(
        range: DateRange, weekStart: DayOfWeek, rows: List<ReportRow>, gridRows: List<ReportRow>, entries: List<Txn>, lookups: EntryLookups,
    ): CalendarState {
        val byDay = gridRows.groupBy { it.date }
        return CalendarState(
            range, weekStart, totals(rows),
            dayTotals = byDay.mapNotNull { (d, list) -> d?.let { it to totals(list) } }.toMap(),
            dayCounts = byDay.mapNotNull { (d, list) -> d?.let { it to list.size } }.toMap(),
            entries = entries, lookups = lookups,
        )
    }
}
