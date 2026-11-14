package app.moneytracker.insights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import app.moneytracker.core.LocalRepo
import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.MonthBudget
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.ReadModels
import app.moneytracker.data.ReportStore
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TagStore
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Insights (new layout) and Stats (classic): what is picked, and the shown period's rows, totals, breakdown, top
 * entries, trend and budget. Both layouts draw this one holder.
 */
class InsightsModel(
    private val reports: ReportStore,
    private val categories: CategoryStore,
    private val accounts: AccountStore,
    private val tags: TagStore,
    private val settings: SettingsStore,
    private val views: ReadModels,
    private val today: () -> LocalDate,
    private val scope: CoroutineScope,
    start: InsightsChoice? = null,
) {
    private val _state = MutableStateFlow(Sources(start ?: InsightsChoice.start(settings.periodSettings.current, today()), settings.periodSettings.current).now())
    val state: StateFlow<InsightsState> = _state.asStateFlow()
    private var watching: Job? = null

    init { watch() }

    fun step(dir: Int) = show(choice.stepped(dir, _state.value.periods))
    fun pickSpan(span: InsightsSpan) = show(choice.copy(span = span))
    fun pickSide(side: InsightsSide) = show(choice.copy(side = side))
    fun pickGroup(groupBy: InsightsGroup) = show(choice.copy(groupBy = groupBy))
    fun pickMonth(month: YearMonth) = show(choice.copy(periodKey = month.key))
    fun pickStart(day: LocalDate) = show(choice.copy(customStart = day.toEpochDays()))
    fun pickEnd(day: LocalDate) = show(choice.copy(customEnd = day.toEpochDays()))

    /** A tapped month bar: that month, as a monthly view. */
    fun openMonth(month: YearMonth) = show(choice.copy(span = InsightsSpan.MONTH, periodKey = month.key))

    /** A tapped trend bar (new layout): the period it stands for, if it leads anywhere. */
    fun openSlot(index: Int) { _state.value.slots.getOrNull(index)?.opens?.let(::show) }

    /** The intents as plain callbacks, for the drawing composables. */
    val actions = InsightsActions(::step, ::pickSpan, ::pickSide, ::pickGroup, ::pickMonth, ::pickStart, ::pickEnd, ::openSlot, ::openMonth)

    private val choice get() = _state.value.choice

    /** The new choice's figures right away from the cached rows (no empty frame), then kept up to date. */
    private fun show(next: InsightsChoice) {
        _state.value = Sources(next, settings.periodSettings.current).now()
        watch()
    }

    private fun watch() {
        val shown = choice
        watching?.cancel()
        watching = scope.launch {
            settings.periodSettings.collectLatest { ps -> Sources(shown, ps).updates().collect { _state.value = it } }
        }
    }

    /** The queries one choice needs, read now or followed. */
    private inner class Sources(val choice: InsightsChoice, val ps: PeriodSettings) {
        val slots = trendSlots(choice, ps)
        val range = choice.range(ps)
        val budgetMonth = if (choice.span == InsightsSpan.MONTH) choice.month else ps.labelFor(today())
        val rows = reports.reportRows(range)
        val previousRows = reports.reportRows(choice.previousRange(ps))
        val trendRows = reports.reportRows(trendRangeOf(slots, range))
        val budget = views.budget(budgetMonth)

        fun now() = build(InsightsRows(rows.current, previousRows.current, trendRows.current), budget.current,
            InsightsNames(categories.categoryById.current, accounts.accounts.current, tags.txnTagNames.current))

        fun updates() = combine(
            combine(rows, previousRows, trendRows) { r, p, t -> InsightsRows(r, p, t) },
            budget,
            combine(categories.categoryById, accounts.accounts, tags.txnTagNames) { c, a, t -> InsightsNames(c, a, t) },
        ) { period, b, names -> build(period, b, names) }

        private fun build(rows: InsightsRows, budget: MonthBudget, names: InsightsNames) =
            InsightsState(choice, ps, today(), slots, budgetMonth, budget, rows, names)
    }

    companion object {
        /** Keeps the choice when the screen is left for a while or the app is closed in the background. */
        fun saver(make: (InsightsChoice?) -> InsightsModel) = Saver<InsightsModel, IntArray>(
            save = { it.choice.toInts() },
            restore = { make(InsightsChoice.fromInts(it)) },
        )
    }
}

/** The Insights / Stats holder for this screen, kept with the screen's saved state. */
@Composable
fun rememberInsightsModel(): InsightsModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val make = { start: InsightsChoice? ->
        InsightsModel(repo.reportStore, repo.categoryStore, repo.accountStore, repo.tagStore, repo.settingsStore, repo.views, repo.today, scope, start)
    }
    return rememberSaveable(repo, saver = InsightsModel.saver(make)) { make(null) }
}

/** What Insights and Stats can do, as callbacks; each layout uses the ones it shows. */
class InsightsActions(
    val step: (Int) -> Unit,
    val pickSpan: (InsightsSpan) -> Unit,
    val pickSide: (InsightsSide) -> Unit,
    val pickGroup: (InsightsGroup) -> Unit,
    val pickMonth: (YearMonth) -> Unit,
    val pickStart: (LocalDate) -> Unit,
    val pickEnd: (LocalDate) -> Unit,
    val openSlot: (Int) -> Unit,
    val openMonth: (YearMonth) -> Unit,
)
