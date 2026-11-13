package app.moneytracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.moneytracker.data.AccountStore
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TagStore
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Account
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Tag
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.classify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

enum class SearchType(val title: String) { INCOME("Income"), EXPENSES("Expenses"), TRANSFERS("Transfers") }

enum class SearchDates(val title: String) { MONTH("This month"), LAST("Last month"), THREE("Last 3 months"), YEAR("This year"), ALL("All time"), CUSTOM("Custom") }

private val Earliest = LocalDate(1970, 1, 1)
private val Latest = LocalDate(2200, 1, 1)

/** What the search screen is asked: the typed text and every filter, as typed (amounts are parsed when searching). */
data class SearchQuery(
    val text: String = "",
    val types: Set<SearchType> = emptySet(),
    val accountIds: Set<String> = emptySet(),
    val tagIds: List<String> = emptyList(),
    val minText: String = "",
    val maxText: String = "",
    val dates: SearchDates = SearchDates.ALL,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/** The dates [d] covers today; custom dates use [from] and [to] (either way round), open ends reach any day. */
fun searchRange(d: SearchDates, ps: PeriodSettings, today: LocalDate, from: LocalDate? = null, to: LocalDate? = null): DateRange {
    val now = ps.labelFor(today)
    return when (d) {
        SearchDates.MONTH -> ps.month(now)
        SearchDates.LAST -> ps.month(now.plus(-1))
        SearchDates.THREE -> DateRange(ps.month(now.plus(-2)).start, ps.month(now).endInclusive)
        SearchDates.YEAR -> ps.year(today.year)
        SearchDates.ALL -> DateRange(Earliest, Latest)
        SearchDates.CUSTOM -> {
            val a = from ?: Earliest
            val b = to ?: Latest
            if (a <= b) DateRange(a, b) else DateRange(b, a)
        }
    }
}

private fun Txn.searchType(): SearchType? = when (kind) {
    TxnKind.INCOME -> SearchType.INCOME
    TxnKind.EXPENSE -> SearchType.EXPENSES
    TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN -> SearchType.TRANSFERS
    TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> null
}

/** Narrows what the database found: each transfer once (its outgoing side), the type and account chips, newest first. */
fun searchResults(found: List<Txn>, q: SearchQuery): List<Txn> =
    found.sortedBy { if (it.kind.isListed) 0 else 1 }.distinctBy { it.transferGroupId ?: it.id }
        .filter { t ->
            (q.types.isEmpty() || t.searchType() in q.types) &&
                (q.accountIds.isEmpty() || t.accountId in q.accountIds || t.toAccountId in q.accountIds)
        }
        .sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.createdAt })

/** The search screen's results and what the filters offer. [active] is false until something is typed or picked. */
data class SearchState(
    val range: DateRange,
    val active: Boolean,
    val results: List<Txn>,
    /** Accounts to offer as chips: visible ones, plus hidden ones already picked. */
    val accounts: List<Account>,
    val tags: List<Tag>,
    val periods: PeriodSettings,
    val today: LocalDate,
    val lookups: EntryLookups,
) {
    private val rows get() = results.map { lookups.rules.row(it) }
    val earned: Long get() = rows.filter { classify(it) == Flow.INCOME }.sumOf { it.amount }
    val spent: Long get() = rows.filter { classify(it) == Flow.EXPENSE }.sumOf { it.amount }

    /** What [d] would cover, for the dates sheet. */
    fun rangeOf(d: SearchDates): DateRange = searchRange(d, periods, today)

    /** The Activity tab filter matching this search: accounts, tags, a quick filter from the types and the month searched. */
    fun activityRequest(q: SearchQuery): ActivityFilterRequest {
        val kind = when (q.types) {
            setOf(SearchType.EXPENSES) -> ActKind.OUT
            setOf(SearchType.TRANSFERS) -> ActKind.MOVED
            else -> ActKind.ALL
        }
        val month = if (q.dates == SearchDates.ALL) null else periods.labelFor(minOf(range.endInclusive, today).coerceAtLeast(range.start)).key
        return ActivityFilterRequest(TxnFilter(accountIds = q.accountIds, tagIds = q.tagIds.toSet()), kind, month)
    }
}

/**
 * Search over every entry by text, type, account, tag, amount and dates. [query] is Compose state so the text field
 * stays in step with typing; results follow it. Built to serve both layouts' search screens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchModel(
    private val txns: TxnStore,
    accounts: AccountStore,
    tags: TagStore,
    settings: SettingsStore,
    private val feed: EntryFeed,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    initial: SearchQuery = SearchQuery(),
) {
    var query by mutableStateOf(initial)
        private set

    private class Inputs(val q: SearchQuery, val ps: PeriodSettings, val currency: CurrencyFormat, val accounts: List<Account>, val tags: List<Tag>)

    val state: StateFlow<SearchState> =
        combine(snapshotFlow { query }, settings.periodSettings, settings.currency, accounts.accounts, tags.tags) { q, ps, cur, accs, tagList ->
            Inputs(q, ps, cur, accs, tagList)
        }.mapLatest(::find)
            .combine(feed.lookups) { s, lookups -> s.copy(lookups = lookups) }
            .stateIn(scope, SharingStarted.Eagerly, emptyState(settings.periodSettings.current, accounts.accounts.current, tags.tags.current))

    private suspend fun find(i: Inputs): SearchState {
        val q = i.q
        val range = searchRange(q.dates, i.ps, today(), q.from, q.to)
        val min = i.currency.parse(q.minText)
        val max = i.currency.parse(q.maxText)
        val active = q.text.isNotBlank() || min != null || max != null || q.types.isNotEmpty() || q.accountIds.isNotEmpty() ||
            q.tagIds.isNotEmpty() || q.dates != SearchDates.ALL
        val found = if (!active) emptyList() else withContext(io) { txns.search(q.text, range, min, max, q.tagIds.toSet()) }
        val offered = i.accounts.filter { !it.hidden || it.id in q.accountIds }
        return SearchState(range, active, searchResults(found, q), offered, i.tags, i.ps, today(), feed.lookupsNow())
    }

    private fun emptyState(ps: PeriodSettings, accounts: List<Account>, tags: List<Tag>) = SearchState(
        searchRange(query.dates, ps, today(), query.from, query.to), false, emptyList(),
        accounts.filter { !it.hidden || it.id in query.accountIds }, tags, ps, today(), feed.lookupsNow(),
    )

    fun setText(text: String) { query = query.copy(text = text) }

    fun toggleType(t: SearchType) { query = query.copy(types = query.types.toggle(t)) }

    fun toggleAccount(id: String) { query = query.copy(accountIds = query.accountIds.toggle(id)) }

    /** Toggles [tag]; picking a tag while typing #name also clears the typed text. */
    fun pickTag(tag: Tag) {
        var text = query.text
        val ids = pickSearchTag(tag, query.tagIds, text) { text = it }
        query = query.copy(tagIds = ids, text = text)
    }

    fun setMin(text: String) { query = query.copy(minText = text) }

    fun setMax(text: String) { query = query.copy(maxText = text) }

    fun setDates(d: SearchDates) { query = query.copy(dates = d) }

    fun setFrom(d: LocalDate?) { query = query.copy(from = d) }

    fun setTo(d: LocalDate?) { query = query.copy(to = d) }

    fun clear() { query = SearchQuery() }

    private fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v

    companion object {
        /** Keeps what was asked while the screen is covered or recreated; [create] builds the model from it. */
        fun saver(create: (SearchQuery) -> SearchModel): Saver<SearchModel, Any> = listSaver(
            save = { m ->
                val q = m.query
                listOf(
                    q.text, ArrayList(q.types.map { it.name }), ArrayList(q.accountIds), ArrayList(q.tagIds), q.minText, q.maxText,
                    q.dates.name, q.from?.toEpochDays(), q.to?.toEpochDays(),
                )
            },
            restore = { create(restoreQuery(it)) },
        )

        @Suppress("UNCHECKED_CAST")
        private fun restoreQuery(s: List<Any?>) = SearchQuery(
            text = s[0] as String,
            types = (s[1] as List<String>).map { SearchType.valueOf(it) }.toSet(),
            accountIds = (s[2] as List<String>).toSet(),
            tagIds = s[3] as List<String>,
            minText = s[4] as String,
            maxText = s[5] as String,
            dates = SearchDates.valueOf(s[6] as String),
            from = (s[7] as Int?)?.let { LocalDate.fromEpochDays(it) },
            to = (s[8] as Int?)?.let { LocalDate.fromEpochDays(it) },
        )
    }
}
