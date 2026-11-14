package app.moneytracker.activity

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.moneytracker.activity.classic.FilterNames
import app.moneytracker.activity.classic.TxnFilter
import app.moneytracker.activity.classic.names
import app.moneytracker.data.AccountStore
import app.moneytracker.data.CaptureStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.Prefs
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.StartScreen
import app.moneytracker.data.TagStore
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Tag
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

/** Quick filter on the list: spending (and refunds) only, or only transfers. */
enum class ActKind(val title: String) { ALL("All"), OUT("Out"), MOVED("Moved") }

/** Search › "Apply filter to Activity" hands the tab this filter, quick filter and (optionally) month. */
data class ActivityFilterRequest(val filter: TxnFilter, val kind: ActKind, val periodKey: Int?)

/** Which entries a view lists: the month's dates, the filter and the quick kind filter. */
data class EntryQuery(val range: DateRange, val filter: TxnFilter, val kind: ActKind)

/** What the Activity tab (classic Trans. tab) shows: the month, the filter and how it reads, and detected payments waiting. */
data class ActivityState(
    val period: YearMonth,
    val periods: PeriodSettings,
    val filter: TxnFilter,
    val kind: ActKind,
    private val names: FilterNames,
    val pendingCaptures: Int,
) {
    val range: DateRange get() = periods.month(period)
    val query: EntryQuery get() = EntryQuery(range, filter, kind)

    /** The filter's categories, accounts and #tags by name, for the bar under the tabs. */
    fun filterNames(includeDeletedCategories: Boolean = true): List<String> = filter.names(names, includeDeletedCategories)
}

/**
 * The Activity tab's choices, shared by both layouts: the month shown, the category/account/tag filter and the quick
 * kind filter. The views (list, calendar, months) read [state] through their own holders.
 */
class ActivityModel(
    settings: SettingsStore,
    categories: CategoryStore,
    accounts: AccountStore,
    tags: TagStore,
    captures: CaptureStore,
    today: () -> LocalDate,
    scope: CoroutineScope,
    saved: List<Int>? = null,
) {
    private val period = MutableStateFlow(saved?.let { YearMonth.fromKey(it[0]) } ?: settings.periodSettingsNow().labelFor(today()))
    private val kind = MutableStateFlow(saved?.let { ActKind.entries[it[1]] } ?: ActKind.ALL)
    private val filter = MutableStateFlow(TxnFilter())

    /** The Start screen setting, read once when the tab opens. */
    val startScreen: StartScreen = settings.settingNow(Prefs.START_SCREEN)

    private val choice = combine(period, kind, filter) { ym, k, f -> Choice(ym, k, f) }

    private val names = combine(categories.categories, categories.categoryById, accounts.accountNames, tags.tags, ::filterNames)

    val state: StateFlow<ActivityState> = combine(choice, settings.periodSettings, names, captures.pendingCaptures) { c, ps, n, pending ->
        ActivityState(c.period, ps, c.filter, c.kind, n, pending.size)
    }.stateIn(scope, SharingStarted.Eagerly, initialState(settings, categories, accounts, tags, captures))

    fun showMonth(ym: YearMonth) { period.value = ym }

    /** Moves the month by [months] (12 for a year). */
    fun stepMonths(months: Int) { period.value = period.value.plus(months) }

    fun setKind(k: ActKind) { kind.value = k }

    fun setFilter(f: TxnFilter) { filter.value = f }

    fun clearFilter() { filter.value = TxnFilter() }

    /** Takes over the filter Search left for the tab. */
    fun apply(request: ActivityFilterRequest) {
        filter.value = request.filter
        kind.value = request.kind
        request.periodKey?.let { period.value = YearMonth.fromKey(it) }
    }

    private fun initialState(settings: SettingsStore, categories: CategoryStore, accounts: AccountStore, tags: TagStore, captures: CaptureStore) =
        ActivityState(
            period.value, settings.periodSettings.current, filter.value, kind.value,
            filterNames(categories.categories.current, categories.categoryById.current, accounts.accountNames.current, tags.tags.current),
            captures.pendingCaptures.current.size,
        )

    private fun filterNames(live: List<Category>, catById: Map<String, Category>, accountName: Map<String, String>, tagList: List<Tag>) =
        FilterNames(catById, live.mapTo(mutableSetOf()) { it.id }, accountName, tagList.associate { it.id to it.name })

    private class Choice(val period: YearMonth, val kind: ActKind, val filter: TxnFilter)

    companion object {
        /** Keeps the month and the quick filter across the screen being recreated; [create] builds the model from them. */
        fun saver(create: (List<Int>?) -> ActivityModel): Saver<ActivityModel, Any> = listSaver(
            save = { listOf(it.period.value.key, it.kind.value.ordinal) },
            restore = { create(it) },
        )
    }
}
