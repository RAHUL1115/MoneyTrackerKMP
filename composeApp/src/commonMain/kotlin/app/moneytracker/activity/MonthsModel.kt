package app.moneytracker.activity

import app.moneytracker.activity.classic.TxnFilter
import app.moneytracker.data.MonthTotals
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.ReadModels
import app.moneytracker.data.YearRollup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

/**
 * The year's months and weeks (Monthly in the classic layout, Months in the new one) and the picked month's Total card.
 * Each figure is only read once a view asks for it, and only while it is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonthsModel(
    private val activity: StateFlow<ActivityState>,
    private val feed: EntryFeed,
    private val views: ReadModels,
    private val today: () -> LocalDate,
    private val scope: CoroutineScope,
) {
    private data class Year(val year: Int, val periods: PeriodSettings, val filter: TxnFilter)

    /** The picked month's year, by month and week, narrowed by the filter. */
    val year: StateFlow<YearRollup> by lazy {
        activity.map { Year(it.period.year, it.periods, it.filter) }.distinctUntilChanged()
            .flatMapLatest { y -> feed.rows(y.periods.year(y.year), y.filter).map { rows -> ReadModels.yearRollup(rows, y.periods, y.year, today()) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(), initialYear(activity.value))
    }

    /** The Total card's figures for the picked month (not filtered). */
    val month: StateFlow<MonthTotals> by lazy {
        activity.map { it.period to it.range }.distinctUntilChanged()
            .flatMapLatest { (ym, _) -> views.month(ym) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), views.month(activity.value.period).current)
    }

    private fun initialYear(a: ActivityState): YearRollup {
        val year = a.period.year
        return ReadModels.yearRollup(feed.rowsNow(a.periods.year(year), a.filter), a.periods, year, today())
    }
}
