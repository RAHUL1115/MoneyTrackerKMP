package app.moneytracker.ui.fresh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.moneytracker.data.CaptureStore
import app.moneytracker.data.MonthBudget
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.ReadModels
import app.moneytracker.data.ReportStore
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.BudgetLine
import app.moneytracker.domain.Capture
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import app.moneytracker.ui.LocalRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus

/** Home (new layout): spent today, yesterday and this period, the budget left, bank SMS waiting, and the last two days' entries. */
class HomeModel(
    private val reports: ReportStore,
    private val txns: TxnStore,
    private val captures: CaptureStore,
    private val settings: SettingsStore,
    private val views: ReadModels,
    private val today: () -> LocalDate,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(Sources(settings.periodSettings.current).now())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        scope.launch { settings.periodSettings.collectLatest { ps -> Sources(ps).updates().collect { _state.value = it } } }
    }

    /** The queries Home needs, read now or followed. */
    private inner class Sources(val ps: PeriodSettings) {
        val day = today()
        val month = ps.labelFor(day)
        val monthRange = ps.month(month)
        val recentRange = DateRange(day.minus(DatePeriod(days = 1)), day)
        val monthRows = reports.reportRows(monthRange)
        val recentRows = reports.reportRows(recentRange)
        val recentTxns = txns.txnsBetween(recentRange)
        val budget = views.budget(month)

        fun now() = build(monthRows.current, recentRows.current, recentTxns.current, budget.current, captures.pendingCaptures.current)

        fun updates() = combine(monthRows, recentRows, recentTxns, budget, captures.pendingCaptures) { m, r, t, b, p -> build(m, r, t, b, p) }

        private fun build(monthRows: List<ReportRow>, recentRows: List<ReportRow>, recentTxns: List<Txn>, budget: MonthBudget, pending: List<Capture>): HomeState {
            val yesterday = recentRange.start
            // Entry counts skip the second half of a transfer and balance corrections, like the lists do.
            fun spentOn(d: LocalDate) = totals(recentRows.filter { it.date == d && it.kind.isListed && !it.kind.isAdjustment }).expense
            return HomeState(
                today = day,
                month = month,
                spentToday = spentOn(day),
                spentYesterday = spentOn(yesterday),
                monthSpent = totals(monthRows).expense,
                budget = budget.total,
                daysLeft = (day.daysUntil(monthRange.endInclusive) + 1).coerceAtLeast(1),
                pending = pending,
                days = recentDays(day, yesterday, recentRows, recentTxns),
            )
        }
    }
}

/** Today (always) and yesterday (when it has entries), newest entry first, each with its totals. */
private fun recentDays(today: LocalDate, yesterday: LocalDate, rows: List<ReportRow>, txns: List<Txn>): List<HomeDay> {
    // Like Activity: each transfer once, and balance corrections only on their account's page.
    val shown = txns.filter { it.kind.isListed && !it.kind.isAdjustment }
    return listOf(today, yesterday).mapNotNull { day ->
        val entries = shown.filter { it.date == day }.sortedWith(compareByDescending<Txn> { it.timeOfDay ?: -1 }.thenByDescending { it.createdAt })
        if (entries.isEmpty() && day != today) null
        else HomeDay(day, day == today, entries, totals(rows.filter { it.date == day }))
    }
}

/** Home's figures. [budget] is this period's total budget line, when one is set; [daysLeft] counts today. */
class HomeState(
    val today: LocalDate,
    val month: YearMonth,
    val spentToday: Long,
    val spentYesterday: Long,
    val monthSpent: Long,
    val budget: BudgetLine?,
    val daysLeft: Int,
    val pending: List<Capture>,
    val days: List<HomeDay>,
) {
    /** What the bank SMS waiting to be added come to. */
    val pendingSum: Long get() = pending.sumOf { it.amount ?: 0L }
}

/** One day on Home: its entries and what came in and went out. */
class HomeDay(val date: LocalDate, val isToday: Boolean, val entries: List<Txn>, val totals: Totals)

@Composable
internal fun rememberHomeModel(): HomeModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo) { HomeModel(repo.reportStore, repo.txnStore, repo.captureStore, repo.settingsStore, repo.views, repo.today, scope) }
}
