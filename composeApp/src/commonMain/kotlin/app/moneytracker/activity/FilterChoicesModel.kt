package app.moneytracker.activity

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Txn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One checklist line in the filter sheet: what it filters by and this period's amount (null = none). */
class FilterLine(val id: String, val label: String, val amount: Long?)

/** The filter sheet's three checklists: Income and Expenses (main categories) and Account. */
class FilterChoices(val income: List<FilterLine>, val expense: List<FilterLine>, val accounts: List<FilterLine>) {
    /** The list for tab [tab]: 0 Income, 1 Expenses, 2 Account. */
    fun forTab(tab: Int): List<FilterLine> = when (tab) {
        0 -> income
        1 -> expense
        else -> accounts
    }
}

/**
 * What can be filtered by in one period, with each line's amount. Deleted categories, and hidden or deleted accounts,
 * still show while they have entries in the period.
 */
fun filterChoices(
    txns: List<Txn>,
    categories: List<Category>,
    catById: Map<String, Category>,
    accounts: List<Account>,
    accountNames: Map<String, String>,
): FilterChoices {
    val mainOf = { t: Txn -> t.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } }
    val perCategory = txns.filter { it.kind.isIncomeOrExpense }.groupBy { mainOf(it) }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val perAccount = txns.groupBy { it.accountId }.mapValues { (_, v) -> v.sumOf { it.signedAccountValue } }
    fun categoryLines(kind: CategoryKind): List<FilterLine> {
        val deleted = perCategory.keys.mapNotNull { id -> id?.let { catById[it] } }
            .filter { it.kind == kind && it.parentId == null && categories.none { c -> c.id == it.id } }
        return (categories.filter { it.kind == kind && it.parentId == null } + deleted).map { FilterLine(it.id, it.name, perCategory[it.id]) }
    }
    val accountLines = accounts.filter { !it.hidden || it.id in perAccount }.map { FilterLine(it.id, it.name, perAccount[it.id]) } +
        perAccount.keys.filter { id -> accounts.none { it.id == id } }.map { FilterLine(it, accountNames[it] ?: it, perAccount[it]) }
    return FilterChoices(categoryLines(CategoryKind.INCOME), categoryLines(CategoryKind.EXPENSE), accountLines)
}

/** The filter sheet's checklists for [range], kept up to date while the sheet is open. */
class FilterChoicesModel(range: DateRange, txns: TxnStore, categories: CategoryStore, accounts: AccountStore, scope: CoroutineScope) {
    private val entries = txns.txnsBetween(range)

    val state: StateFlow<FilterChoices> = combine(
        entries, categories.categories, categories.categoryById, accounts.accounts, accounts.accountNames,
    ) { list, live, catById, accountList, names -> filterChoices(list, live, catById, accountList, names) }.stateIn(
        scope, SharingStarted.Eagerly,
        filterChoices(
            entries.current, categories.categories.current, categories.categoryById.current,
            accounts.accounts.current, accounts.accountNames.current,
        ),
    )
}
