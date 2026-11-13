package app.moneytracker.ui

import app.moneytracker.data.CurrencyStore
import app.moneytracker.data.RepeatStore
import app.moneytracker.data.SettingsStore
import app.moneytracker.domain.Category
import app.moneytracker.domain.Currency
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Fx
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
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
import kotlinx.datetime.LocalDate

/** "Repeat · Rent, To mom · 1 Nov · −30,000": repeating entries still to come in the period (not in the totals). */
class UpcomingRepeats(val names: List<String>, val first: LocalDate, val sum: Long)

/** Repeats that fall in [range] but aren't added yet, summed in the main currency; null when there are none. */
fun upcomingRepeats(
    rules: List<RecurringRule>,
    range: DateRange,
    catById: Map<String, Category>,
    currencies: Map<String, Currency>,
    mainDecimals: Int,
): UpcomingRepeats? {
    val pending = rules.flatMap { r -> Recurrence.upcoming(r, range).map { r to it } }
    if (pending.isEmpty()) return null
    val names = pending.map { (r, _) -> r }.distinct().map { r -> r.note.ifBlank { categoryPath(r.categoryId, catById) } }
    val sum = pending.sumOf { (r, _) ->
        val main = Fx.toMain(r.amount, r.currency?.let { currencies[it] }, mainDecimals)
        if (r.kind == TxnKind.INCOME) main else -main
    }
    return UpcomingRepeats(names, pending.minOf { it.second }, sum)
}

/** A month's entries day by day, as the list views draw them. */
class EntryListState(
    val query: EntryQuery,
    /** Every matching entry, for looking up the selected ones. */
    val entries: List<Txn>,
    /** What the list shows: each transfer once (TRANSFER_IN mirrors TRANSFER_OUT), narrowed by the quick kind filter. */
    val visible: List<Txn>,
    val totals: Totals,
    val dayTotals: Map<LocalDate, Totals>,
    /** Running total before the month, or null while carry-over is off. */
    val carryOver: Long?,
    val upcoming: UpcomingRepeats?,
    val lookups: EntryLookups,
) {
    val days: Map<LocalDate, List<Txn>> get() = visible.groupBy { it.date }
}

/** The Daily list (classic) and the List view (new layout): the month's entries, totals, carry-over and repeats to come. */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryListModel(
    activity: StateFlow<ActivityState>,
    private val feed: EntryFeed,
    private val repeats: RepeatStore,
    private val currencies: CurrencyStore,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    val state: StateFlow<EntryListState> = activity.map { it.query }.distinctUntilChanged()
        .flatMapLatest(::entriesFor)
        .stateIn(scope, SharingStarted.Eagerly, initialState(activity.value.query))

    private fun entriesFor(q: EntryQuery): Flow<EntryListState> {
        val extras = combine(repeats.rules, currencies.currencies, settings.currency) { rules, list, format ->
            Extras(rules, list.associateBy { it.code }, format.decimals)
        }
        return combine(feed.entries(q.range, q.filter), feed.rows(q.range, q.filter), feed.carryOver(q.range), feed.lookups, extras) { e, rows, carry, lookups, x ->
            build(q, e, rows, carry, lookups, x)
        }
    }

    private fun initialState(q: EntryQuery) = build(
        q, emptyList(), feed.rowsNow(q.range, q.filter), null, feed.lookupsNow(),
        Extras(repeats.rules.current, currencies.currencies.current.associateBy { it.code }, settings.currency.current.decimals),
    )

    private class Extras(val rules: List<RecurringRule>, val currencies: Map<String, Currency>, val mainDecimals: Int)

    private fun build(q: EntryQuery, entries: List<Txn>, rows: List<ReportRow>, carry: Long?, lookups: EntryLookups, x: Extras) = EntryListState(
        query = q,
        entries = entries,
        visible = entries.filter { it.kind.isListed && q.kind.matches(it) },
        totals = totals(rows),
        dayTotals = rows.groupBy { it.date }.mapNotNull { (d, list) -> d?.let { it to totals(list) } }.toMap(),
        carryOver = carry,
        upcoming = upcomingRepeats(x.rules, q.range, lookups.catById, x.currencies, x.mainDecimals),
        lookups = lookups,
    )
}

/** Whether [t] passes the quick filter. */
fun ActKind.matches(t: Txn): Boolean = when (this) {
    ActKind.ALL -> true
    ActKind.OUT -> t.kind == TxnKind.EXPENSE
    ActKind.MOVED -> t.kind == TxnKind.TRANSFER_OUT
}
