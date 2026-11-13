package app.moneytracker.ui

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.PhotoStore
import app.moneytracker.data.Prefs
import app.moneytracker.data.ReportStore
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TagStore
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.ReportRules
import app.moneytracker.domain.Txn
import app.moneytracker.domain.totals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** Lookups every entry row needs: names, categories, the report rules, tags and which entries have photos. */
class EntryLookups(
    val accountName: Map<String, String>,
    val catById: Map<String, Category>,
    val rules: ReportRules,
    val tags: Map<String, List<String>>,
    val photos: Set<String>,
)

/**
 * The entry lists both layouts draw: a period's entries and report rows narrowed by the Activity filter, the
 * carried-over total and the row lookups. Flows only; the state holders collect them.
 */
class EntryFeed(
    private val txns: TxnStore,
    private val reports: ReportStore,
    private val categories: CategoryStore,
    private val accounts: AccountStore,
    private val tags: TagStore,
    private val photos: PhotoStore,
    private val settings: SettingsStore,
    private val io: CoroutineDispatcher,
) {
    /** Entries in [range] narrowed by [filter]. Balance adjustments are left out, they only appear on their account's page. */
    fun entries(range: DateRange, filter: TxnFilter): Flow<List<Txn>> =
        combine(txns.txnsBetween(range), categories.categoryById) { list, catById ->
            // Looked up on every change, so entries tagged later still match.
            val tagged = filter.tagIds.flatMapTo(mutableSetOf()) { tags.txnIdsWithTag(it) }
            list.filter { !it.kind.isAdjustment && (!filter.isActive || filter.matches(it, catById, tagged)) }
        }.flowOn(io)

    /** Report rows for [range]; with a filter they are rebuilt from the matching entries. */
    fun rows(range: DateRange, filter: TxnFilter): Flow<List<ReportRow>> =
        if (!filter.isActive) reports.reportRows(range)
        // TRANSFER_IN mirrors TRANSFER_OUT; report rows count each transfer once.
        else combine(reports.reportRules, entries(range, filter)) { rules, list -> list.filter { it.kind.isListed }.map { rules.row(it) } }

    /** What [rows] shows before its first answer: cached rows without a filter, nothing with one. */
    fun rowsNow(range: DateRange, filter: TxnFilter): List<ReportRow> =
        if (filter.isActive) emptyList() else reports.reportRows(range).current

    /** Running total of everything before [range], or null while carry-over is off. */
    fun carryOver(range: DateRange): Flow<Long?> =
        combine(settings.setting(Prefs.CARRY_OVER), reports.reportRows(before(range))) { on, rows -> if (on) totals(rows).net else null }

    val lookups: Flow<EntryLookups> = combine(
        accounts.accountNames, categories.categoryById, reports.reportRules, tags.txnTagNames, photos.txnIdsWithPhotos,
    ) { names, catById, rules, tagNames, withPhotos -> EntryLookups(names, catById, rules, tagNames, withPhotos) }

    /** The latest lookups without waiting, for a first frame with real names. */
    fun lookupsNow() = EntryLookups(
        accounts.accountNames.current, categories.categoryById.current, reports.reportRules.current,
        tags.txnTagNames.current, photos.txnIdsWithPhotos.current,
    )

    private fun before(range: DateRange) = DateRange(LocalDate(1970, 1, 1), range.start.minus(DatePeriod(days = 1)))
}
