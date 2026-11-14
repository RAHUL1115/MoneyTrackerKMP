package app.moneytracker.insights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.outlivingScope
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.MonthBudget
import app.moneytracker.data.Prefs
import app.moneytracker.data.ReadModels
import app.moneytracker.data.ReportStore
import app.moneytracker.data.SettingsStore
import app.moneytracker.designsystem.classic.flattenCategories
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * The Budget screens of both layouts: one month's budgets with what is spent, the month before, and the categories a
 * budget can be set for. Budgets are set from the shown month onward.
 */
class BudgetModel(
    private val reports: ReportStore,
    private val categories: CategoryStore,
    private val settings: SettingsStore,
    private val views: ReadModels,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    start: YearMonth,
) {
    private val _state = MutableStateFlow(Sources(start).now())
    val state: StateFlow<BudgetState> = _state.asStateFlow()
    private var watching: Job? = null

    init { watch() }

    fun step(dir: Int) = show(_state.value.month.plus(dir))
    fun pickMonth(month: YearMonth) = show(month)

    /** Moving between months, as plain callbacks for the drawing composables. */
    val actions = BudgetActions(::step, ::pickMonth)

    /** Sets [key]'s monthly amount (0 removes it) from the shown month onward; the result says what changed, for Undo. */
    fun save(key: String, amount: Long): BudgetChange {
        val month = _state.value.month
        val change = BudgetChange(key, month, _state.value.budget.budgets[key] ?: 0L, amount)
        scope.launch { withContext(io) { reports.setBudget(key, month, amount) } }
        return change
    }

    /** Puts the amount back as it was. Runs apart from the screen, so Undo still works after it closed. */
    fun undo(change: BudgetChange) {
        outlivingScope(io).launch { reports.setBudget(change.key, change.month, change.previous) }
    }

    /** The new month right away from the cached rows (no empty frame), then kept up to date. */
    private fun show(month: YearMonth) {
        _state.value = Sources(month).now()
        watch()
    }

    private fun watch() {
        val sources = Sources(_state.value.month)
        watching?.cancel()
        watching = scope.launch { sources.updates().collect { _state.value = it } }
    }

    /** The queries one month needs, read now or followed. */
    private inner class Sources(val month: YearMonth) {
        val budget = views.budget(month)
        val before = views.budget(month.plus(-1))

        fun now() = build(budget.current, before.current, categories.categories.current, categories.categoryById.current,
            settings.settingNow(Prefs.USE_SUBCATEGORIES))

        fun updates() = combine(budget, before, categories.categories, categories.categoryById, settings.useSubcategories) { b, p, all, byId, subs ->
            build(b, p, all, byId, subs)
        }

        private fun build(budget: MonthBudget, before: MonthBudget, all: List<Category>, catById: Map<String, Category>, useSubcategories: Boolean): BudgetState {
            val ps = settings.periodSettings.current
            val options = flattenCategories(all.filter { it.kind == CategoryKind.EXPENSE }).filter { useSubcategories || it.parentId == null }
            return BudgetState(month, ps.month(month), ps.labelFor(today()), budget, before, catById, options)
        }
    }

    companion object {
        /** Keeps the shown month when the screen is left for a while or the app is closed in the background. */
        fun saver(make: (YearMonth) -> BudgetModel) = Saver<BudgetModel, Int>(
            save = { it.state.value.month.key },
            restore = { make(YearMonth.fromKey(it)) },
        )
    }
}

/**
 * One month's budgets. [budget] is the shown month and [before] the month before it; [thisMonth] is today's month.
 * [options] are the expense categories a budget can be set for (sub-categories only when they're in use).
 */
class BudgetState(
    val month: YearMonth,
    val range: DateRange,
    val thisMonth: YearMonth,
    val budget: MonthBudget,
    val before: MonthBudget,
    val catById: Map<String, Category>,
    val options: List<Category>,
) {
    /** The categories that have no budget yet. */
    val unbudgeted: List<Category> get() = options.filter { it.id !in budget.budgets }
}

/** Moving between months on a Budget screen. */
class BudgetActions(val step: (Int) -> Unit, val pickMonth: (YearMonth) -> Unit)

/** A budget change: [previous] was the amount before (0: none), [amount] the new one (0: removed). */
class BudgetChange(val key: String, val month: YearMonth, val previous: Long, val amount: Long) {
    val removed: Boolean get() = amount <= 0
    val added: Boolean get() = !removed && previous == 0L
}

/** A typed budget amount: empty means 0 (remove); null when it isn't a number. */
fun budgetAmount(text: String, currency: CurrencyFormat): Long? = if (text.isBlank()) 0L else currency.parse(text)

/** The Budget holder for this screen, starting at [startKey] (default: this month) and kept with the screen's saved state. */
@Composable
fun rememberBudgetModel(startKey: Int? = null): BudgetModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val make = { month: YearMonth ->
        BudgetModel(repo.reportStore, repo.categoryStore, repo.settingsStore, repo.views, repo.today, repo.io, scope, month)
    }
    return rememberSaveable(repo, saver = BudgetModel.saver(make)) {
        make(startKey?.let(YearMonth::fromKey) ?: repo.settingsStore.periodSettingsNow().labelFor(repo.today()))
    }
}
