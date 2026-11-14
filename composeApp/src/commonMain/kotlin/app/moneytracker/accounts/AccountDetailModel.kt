package app.moneytracker.accounts

import app.moneytracker.data.AccountStore
import app.moneytracker.data.AccountSummary
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.ReadModels
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TagStore
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.TAG_USAGE_EXCEPTION
import app.moneytracker.domain.Txn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One account's entries in the shown period, with its full history (for summaries, charts and card usage) and the
 * running balance after each entry, worked out over that whole history.
 */
class AccountHistory(
    val entries: List<Txn>,
    val history: List<Txn>,
    /** Entries tagged to be left out of the card's usage target. */
    val usageExcluded: Set<String>,
) {
    val running: Map<String, Long> by lazy { var acc = 0L; history.associate { t -> acc += t.signedAccountValue; t.id to acc } }

    companion object {
        val Empty = AccountHistory(emptyList(), emptyList(), emptySet())
    }
}

/** An account's detail screen in both layouts: its line in the summary, its entries and history, names to show them with. */
class AccountDetailModel(
    val accountId: String,
    views: ReadModels,
    accountStore: AccountStore,
    categories: CategoryStore,
    settings: SettingsStore,
    private val txns: TxnStore,
    private val tags: TagStore,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
) {
    val summary: StateFlow<AccountSummary?> = views.accounts.stateIn(scope, SharingStarted.Eagerly, views.latestAccounts)
    val accountNames: StateFlow<Map<String, String>> = accountStore.accountNames.stateIn(scope, SharingStarted.Eagerly, accountStore.accountNames.current)
    val categoryById: StateFlow<Map<String, Category>> = categories.categoryById.stateIn(scope, SharingStarted.Eagerly, categories.categoryById.current)
    val monthStartDay: StateFlow<Int> = settings.monthStartDay.stateIn(scope, SharingStarted.Eagerly, 1)

    /** For the card's Pay sheet: where the bill can be paid from. */
    val accounts: StateFlow<List<Account>> = accountStore.accounts.stateIn(scope, SharingStarted.Eagerly, accountStore.accounts.current)
    val groups: StateFlow<List<AccountGroup>> = accountStore.groups.stateIn(scope, SharingStarted.Eagerly, accountStore.groups.current)

    /** The account's line in the summary, or null while loading or once it's deleted. */
    fun lineIn(summary: AccountSummary?): AccountLine? = summary?.lines?.firstOrNull { it.account.id == accountId }

    /** Entries in [range]; the full history is read again whenever they or any balance change. */
    fun history(range: DateRange): Flow<AccountHistory> =
        combine(txns.accountTxnsFlow(accountId, range), summary) { entries, _ -> entries }
            .map { entries -> AccountHistory(entries, txns.accountTxns(accountId), tags.txnIdsWithTag(TAG_USAGE_EXCEPTION)) }
            .flowOn(io)
}
