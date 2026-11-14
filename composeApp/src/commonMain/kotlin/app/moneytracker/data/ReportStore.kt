package app.moneytracker.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.moneytracker.db.BudgetAmount as BudgetRow
import app.moneytracker.db.ReportRows as ReportRowsRow
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.BudgetEntry
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.ReportRules
import app.moneytracker.domain.Totals
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/** The rows reports and totals are built from, and the budgets they are measured against. */
class ReportStore(private val db: Db, private val settings: SettingsStore, private val accounts: AccountStore) {
    private val q get() = db.q

    fun reportRows(range: DateRange): CachedFlow<List<ReportRow>> = db.rangeCache.get("report:$range") { reportRowsIn(range) }
    /** A fresh read for background threads; doesn't touch the per-range cache. */
    fun reportRowsNow(range: DateRange): List<ReportRow> = reportRowsIn(range).now()
    private fun reportRowsIn(range: DateRange): CachedFlow<List<ReportRow>> = run {
        val from = range.start.toEpochDays().toLong(); val to = range.endInclusive.toEpochDays().toLong()
        CachedFlow(combine(q.reportRows(from, to).asFlow().mapToList(db.io), settings.setting(Prefs.CARD_AT_PAYMENT)) { rows, cardAtPayment ->
            rows.map { it.toReportRow(cardAtPayment) }
        }) { q.reportRows(from, to).executeAsList().map { it.toReportRow(settings.settingNow(Prefs.CARD_AT_PAYMENT)) } }
    }

    /** How to build report rows from entries; same rules as [reportRows]. */
    val reportRules: CachedFlow<ReportRules> = CachedFlow(combine(accounts.accounts, accounts.groups, settings.setting(Prefs.CARD_AT_PAYMENT)) { a, g, card ->
        ReportRules.of(a, g, card)
    }) { ReportRules.of(accounts.accounts.current, accounts.groups.current, settings.settingNow(Prefs.CARD_AT_PAYMENT)) }

    private fun ReportRowsRow.toReportRow(cardAtPayment: Boolean) = ReportRow(
        TxnKind.valueOf(kind), amount, categoryId, srcTransferExpense != 0L, dstTransferExpense != 0L,
        LocalDate.fromEpochDays(date.toInt()),
        srcIsCard = srcType == AccountType.CREDIT_CARD.name, dstIsCard = dstType == AccountType.CREDIT_CARD.name,
        cardAtPayment = cardAtPayment, txnId = id, accountId = accountId, note = note,
    )

    /** Synchronous totals for widgets / notifications (same rows as [reportRows]). */
    fun totalsNow(range: DateRange): Totals = totals(reportRowsNow(range))

    // ───────────────────────── budgets

    fun budgetEntries(upTo: YearMonth): CachedFlow<List<BudgetEntry>> = db.rangeCache.get("budget:${upTo.key}") { budgetEntriesUpTo(upTo) }
    /** A fresh read for background threads; doesn't touch the per-range cache. */
    fun budgetEntriesNow(upTo: YearMonth): List<BudgetEntry> = budgetEntriesUpTo(upTo).now()
    private fun budgetEntriesUpTo(upTo: YearMonth) =
        db.cached({ q.budgetAmountsUpTo(upTo.key.toLong()) }) { rows -> rows.map { BudgetEntry(it.categoryId, it.period.toInt(), it.amount) } }

    /** Sets the budget from [from] onward (later explicit changes are replaced). 0 removes it. */
    fun setBudget(categoryId: String, from: YearMonth, amount: Long) = db.transaction {
        q.deleteBudgetFrom(categoryId, from.key.toLong())
        q.upsertBudgetAmount(BudgetRow(categoryId, from.key.toLong(), amount, db.now()))
    }
}
