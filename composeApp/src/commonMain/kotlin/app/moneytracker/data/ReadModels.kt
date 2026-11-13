package app.moneytracker.data

import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.BudgetEntry
import app.moneytracker.domain.BudgetLine
import app.moneytracker.domain.CardStatus
import app.moneytracker.domain.Category
import app.moneytracker.domain.Currency
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow as MoneyFlow
import app.moneytracker.domain.Fx
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.NetWorth
import app.moneytracker.domain.Periods
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.cardStatus
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.domain.summarize
import app.moneytracker.domain.totals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Every account with its balance (and card status), and the net worth they add up to. */
data class AccountSummary(val lines: List<AccountLine>, val worth: NetWorth)

/** A month's budgets: the effective amounts, each line with what's spent, and spending by main and sub-category. */
class MonthBudget(
    val budgets: Map<String, Long>,
    val lines: List<BudgetLine>,
    val spentByMain: Map<String, Long>,
    val spentBySub: Map<String, Long>,
) {
    val spent: Long get() = spentByMain.values.sum()
    val total: BudgetLine? get() = lines.firstOrNull { it.categoryId == TOTAL_BUDGET }
    val categoryLines: List<BudgetLine> get() = lines.filter { it.categoryId != TOTAL_BUDGET }
}

/** One month's figures for the Total card: in and out, card vs other spending, money moved to savings, last month. */
class MonthTotals(
    val totals: Totals,
    val cardExpense: Long,
    val otherExpense: Long,
    val saved: Long,
    val previousExpense: Long,
    val budget: MonthBudget,
) {
    /** This month's spending as a percentage of last month's, or null when last month had none. */
    val comparedToLastMonth: Int? get() = if (previousExpense == 0L) null else (totals.expense * 100 / previousExpense).toInt()
}

/** A year's totals by month and, inside each month, by week (Months views in both layouts). */
class YearRollup(val year: Int, val totals: Totals, val months: List<MonthRollup>)
class MonthRollup(val month: YearMonth, val range: DateRange, val totals: Totals, val weeks: List<Pair<DateRange, Totals>>)

/**
 * The finished numbers screens, the web dashboard and widgets show. Each view has one mapping and two forms:
 * a Flow for screens (re-runs when its tables change; first frame from cached data) and a `…Now()` snapshot for
 * background threads. Built on the pure rules in domain/Rules.kt.
 */
class ReadModels(
    private val settingsStore: SettingsStore,
    private val currencyStore: CurrencyStore,
    private val accountStore: AccountStore,
    private val categoryStore: CategoryStore,
    private val tagStore: TagStore,
    private val txnStore: TxnStore,
    private val reportStore: ReportStore,
    private val io: CoroutineDispatcher,
    private val today: () -> LocalDate,
) {

    // ───────────── accounts

    /** Latest summary seen, so a reopened screen draws at once while the fresh one is worked out. */
    @kotlin.concurrent.Volatile var latestAccounts: AccountSummary? = null
        private set

    /** Re-runs off the main thread when accounts, groups, balances, currencies or tags change. */
    val accounts: Flow<AccountSummary> get() =
        combine(accountStore.groups, accountStore.accounts, accountStore.balances, currencyStore.currencies, tagStore.txnTagNames) { g, a, b, c, _ -> Inputs(g, a, b, c) }
            .map { accountSummary(it.groups, it.accounts, it.balances, it.currencies) }
            .flowOn(io)

    fun accountsNow(): AccountSummary = accountSummary(accountStore.groupsNow(), accountStore.accountsNow(), accountStore.balancesNow(), currencyStore.currenciesNow())

    /** One card's statement status (same rules as the account summary). Blocking. */
    fun cardStatusNow(a: Account, prepaid: Set<String> = tagStore.txnIdsWithTag(TAG_PREPAYMENT)): CardStatus =
        cardStatus(txnStore.accountTxns(a.id), a.statementDay, today(), prepaid)

    private class Inputs(val groups: List<AccountGroup>, val accounts: List<Account>, val balances: Map<String, Long>, val currencies: List<Currency>)

    private fun accountSummary(groups: List<AccountGroup>, accounts: List<Account>, balances: Map<String, Long>, currencies: List<Currency>): AccountSummary {
        val prepaid = tagStore.txnIdsWithTag(TAG_PREPAYMENT)
        val main = settingsStore.mainDecimals()
        val (lines, worth) = summarize(groups, accounts, balances,
            cardStatusOf = { a -> cardStatusNow(a, prepaid) },
            toMain = { a, v -> Fx.toMain(v, currencies.firstOrNull { it.code == a.currency }, main) },
        )
        return AccountSummary(lines, worth).also { latestAccounts = it }
    }

    // ───────────── budget

    /** A month's budget. Main thread (it shares the per-range caches); first frame comes from cached rows. */
    fun budget(ym: YearMonth): CachedFlow<MonthBudget> {
        val range = settingsStore.periodSettingsNow().month(ym)
        val rows = reportStore.reportRows(range); val entries = reportStore.budgetEntries(ym); val cats = categoryStore.categoryById
        return CachedFlow(combine(rows, entries, cats) { r, e, c -> monthBudget(ym, r, e, c) }) {
            monthBudget(ym, rows.current, entries.current, cats.current)
        }
    }

    fun budgetNow(ym: YearMonth): MonthBudget =
        monthBudget(ym, reportStore.reportRowsNow(settingsStore.periodSettingsNow().month(ym)), reportStore.budgetEntriesNow(ym), categoryStore.categoryByIdNow())

    // ───────────── month totals

    /** The Total card's figures for [ym]. Main thread, like [budget]. */
    fun month(ym: YearMonth): CachedFlow<MonthTotals> {
        val ps = settingsStore.periodSettingsNow()
        val range = ps.month(ym)
        val rows = reportStore.reportRows(range); val prev = reportStore.reportRows(ps.month(ym.plus(-1)))
        val txns = txnStore.txnsBetween(range); val cardIds = cardAccountIds()
        val budget = budget(ym)
        return CachedFlow(combine(rows, prev, txns, cardIds, budget) { r, p, t, c, b -> monthTotals(r, p, t, c, b) }) {
            monthTotals(rows.current, prev.current, txns.current, cardIds.current, budget.current)
        }
    }

    fun monthNow(ym: YearMonth): MonthTotals {
        val ps = settingsStore.periodSettingsNow()
        val range = ps.month(ym)
        val cards = cardIdsOf(accountStore.accountsNow(), accountStore.groupsNow())
        return monthTotals(reportStore.reportRowsNow(range), reportStore.reportRowsNow(ps.month(ym.plus(-1))), txnStore.txnsNow(range), cards, budgetNow(ym))
    }

    private fun cardAccountIds(): CachedFlow<Set<String>> =
        CachedFlow(combine(accountStore.accounts, accountStore.groups) { a, g -> cardIdsOf(a, g) }) { cardIdsOf(accountStore.accounts.current, accountStore.groups.current) }

    companion object {
        /** Spending per main category (no category: [UNCATEGORIZED]; balance corrections keep their own key). */
        const val UNCATEGORIZED = "__uncategorized"

        internal fun cardIdsOf(accounts: List<Account>, groups: List<AccountGroup>): Set<String> {
            val cardGroups = groups.filter { it.type == AccountType.CREDIT_CARD }.map { it.id }.toSet()
            return accounts.filter { it.groupId in cardGroups }.map { it.id }.toSet()
        }

        /** The one budget mapping: expense rows (by the report rules) per main and sub-category, against the budgets. */
        fun monthBudget(ym: YearMonth, rows: List<ReportRow>, entries: List<BudgetEntry>, catById: Map<String, Category>): MonthBudget {
            val expense = rows.filter { it.kind == TxnKind.EXPENSE && classify(it) == MoneyFlow.EXPENSE }
            val byMain = expense.groupBy { r ->
                if (r.categoryId == MODIFIED_BALANCE_CATEGORY) r.categoryId
                else r.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } ?: UNCATEGORIZED
            }.mapValues { (_, v) -> v.sumOf { it.amount } }
            val bySub = expense.mapNotNull { r -> r.categoryId?.takeIf { catById[it]?.parentId != null }?.let { sub -> sub to r.amount } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, amounts) -> amounts.sum() }
            val budgets = effectiveBudgets(entries, ym)
            return MonthBudget(budgets, budgetLines(budgets, byMain, bySub) { id -> catById[id]?.parentId != null }, byMain, bySub)
        }

        fun monthTotals(rows: List<ReportRow>, previous: List<ReportRow>, txns: List<Txn>, cardIds: Set<String>, budget: MonthBudget): MonthTotals {
            val expenses = txns.filter { it.kind == TxnKind.EXPENSE }
            return MonthTotals(
                totals = totals(rows),
                cardExpense = expenses.filter { it.accountId in cardIds }.sumOf { it.amount },
                otherExpense = expenses.filter { it.accountId !in cardIds }.sumOf { it.amount },
                saved = rows.filter { it.kind == TxnKind.TRANSFER_OUT && classify(it) == MoneyFlow.EXPENSE }.sumOf { it.amount },
                previousExpense = totals(previous).expense,
                budget = budget,
            )
        }

        /**
         * A year's rows by month (up to [today]'s month, or later when future-dated rows exist) and by week.
         * Months are newest first, weeks newest first inside each month.
         */
        fun yearRollup(rows: List<ReportRow>, ps: PeriodSettings, year: Int, today: LocalDate): YearRollup {
            val todayLabel = ps.labelFor(today)
            val byMonth = rows.groupBy { ps.labelFor(it.day).month }
            val upTo = when {
                todayLabel.year == year -> todayLabel.month
                todayLabel.year > year -> 12
                else -> 1
            }
            val last = maxOf(upTo, byMonth.keys.maxOrNull() ?: 1)
            val months = (last downTo 1).map { m ->
                val ym = YearMonth(year, m)
                val range = ps.month(ym)
                val monthRows = byMonth[m].orEmpty()
                val weeks = Periods.weeks(range, ps.weekStart).reversed().map { w -> w to totals(monthRows.filter { it.day in w }) }
                MonthRollup(ym, range, totals(monthRows), weeks)
            }
            return YearRollup(year, totals(rows), months)
        }

        /** Year views go by each row's date; rows read from [ReportStore] always carry one. */
        private val ReportRow.day: LocalDate get() = checkNotNull(date) { "report row without a date" }
    }
}
