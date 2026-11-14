package app.moneytracker.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.data.BookmarkStore
import app.moneytracker.data.CaptureStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.CurrencyStore
import app.moneytracker.data.RepeatStore
import app.moneytracker.data.ReportStore
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TagStore
import app.moneytracker.domain.BudgetEntry
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Currency
import app.moneytracker.domain.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** What the settings tiles and hubs summarise: how many of each thing there is now. */
data class SettingsOverview(
    val otherCurrencies: List<String>,
    val expenseCategories: Int,
    val incomeCategories: Int,
    val tags: Int,
    val repeats: Int,
    val bookmarks: Int,
    val toReview: Int,
    /** Categories with a budget above zero this month. */
    val budgeted: Int,
)

/**
 * The counts behind [SettingsOverview], live. [overviewNow] gives the first frame real numbers, the way each
 * cached store flow does on its own.
 */
class SettingsOverviewModel(
    private val currencies: CurrencyStore,
    private val categories: CategoryStore,
    private val tags: TagStore,
    private val repeats: RepeatStore,
    private val bookmarks: BookmarkStore,
    private val captures: CaptureStore,
    private val reports: ReportStore,
    private val settings: SettingsStore,
    private val today: () -> LocalDate,
) {
    private class Labels(val currencies: List<Currency>, val categories: List<Category>, val tags: List<Tag>)

    val overview: Flow<SettingsOverview> = combine(
        combine(currencies.currencies, categories.categories, tags.tags, ::Labels),
        repeats.rules, bookmarks.templates, captures.pendingCaptures, budgetedThisMonth(),
    ) { labels, rules, templates, pending, budgeted ->
        overviewOf(labels.currencies, labels.categories, labels.tags.size, rules.size, templates.size, pending.size, budgeted)
    }

    fun overviewNow(): SettingsOverview = overviewOf(
        currencies.currencies.current, categories.categories.current, tags.tags.current.size, repeats.rules.current.size,
        bookmarks.templates.current.size, captures.pendingCaptures.current.size, budgetedCount(budgetEntriesThisMonth().current),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun budgetedThisMonth(): Flow<Int> =
        settings.periodSettings.flatMapLatest { ps -> reports.budgetEntries(ps.labelFor(today())) }.map(::budgetedCount)

    private fun budgetEntriesThisMonth() = reports.budgetEntries(settings.periodSettings.current.labelFor(today()))
}

/** Categories whose latest budget, up to this month, is above zero. */
internal fun budgetedCount(entries: List<BudgetEntry>): Int =
    entries.groupBy { it.categoryId }.count { (_, e) -> (e.maxByOrNull { it.period }?.amount ?: 0L) > 0L }

private fun overviewOf(
    currencies: List<Currency>, categories: List<Category>, tags: Int, repeats: Int, bookmarks: Int, toReview: Int, budgeted: Int,
): SettingsOverview {
    fun mains(k: CategoryKind) = categories.count { it.kind == k && it.parentId == null }
    return SettingsOverview(
        otherCurrencies = currencies.map { it.code },
        expenseCategories = mains(CategoryKind.EXPENSE),
        incomeCategories = mains(CategoryKind.INCOME),
        tags = tags, repeats = repeats, bookmarks = bookmarks, toReview = toReview, budgeted = budgeted,
    )
}

/** The settings overview as Compose state, live from the stores. */
@Composable
fun rememberSettingsOverview(): State<SettingsOverview> {
    val repo = LocalRepo.current
    val model = remember(repo) {
        SettingsOverviewModel(
            repo.currencyStore, repo.categoryStore, repo.tagStore, repo.repeatStore, repo.bookmarkStore, repo.captureStore,
            repo.reportStore, repo.settingsStore, repo.today,
        )
    }
    val first = remember(model) { model.overviewNow() }
    return model.overview.collectAsState(first)
}
