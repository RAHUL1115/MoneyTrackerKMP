package app.moneytracker.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.moneytracker.db.Account as AccountRow
import app.moneytracker.db.AccountGroup as GroupRow

/** Accounts and account groups: saving, ordering, hiding, deleting and restoring them, and their balances. */
class AccountStore(private val db: Db) {
    private val q get() = db.q

    val groups: CachedFlow<List<AccountGroup>> = db.cached({ q.allGroups() }) { rows -> rows.map { it.toDomain() } }
    val deletedGroups: Flow<List<AccountGroup>> = q.deletedGroups().asFlow().mapToList(db.io).map { rows -> rows.map { it.toDomain() } }
    fun restoreGroup(id: String) = q.restoreGroup(db.now(), id)
    val accounts: CachedFlow<List<Account>> = db.cached({ q.allAccounts() }) { rows -> rows.map { it.toDomain() } }
    val deletedAccounts: Flow<List<Account>> = q.deletedAccounts().asFlow().mapToList(db.io).map { rows -> rows.map { it.toDomain() } }
    fun accountNow(id: String?): Account? = id?.let { a -> q.allAccounts().executeAsList().firstOrNull { it.id == a }?.toDomain() }
    fun accountsNow(): List<Account> = accounts.now()
    fun groupsNow(): List<AccountGroup> = groups.now()

    /** Names of every account, including deleted ones, so old transactions keep showing where they happened. */
    val accountNames: CachedFlow<Map<String, String>> = db.cached({ q.allAccountNames() }) { rows -> rows.associate { it.id to it.name } }

    /**
     * Own-row balance per account as of today, in each account's currency. Future-dated rows
     * (upcoming installments, post-dated entries) are excluded. Debit-card folding happens in domain.summarize.
     */
    val balances: CachedFlow<Map<String, Long>> get() = db.today().toEpochDays().toLong().let { day -> db.rangeCache.get("balances:$day") { balancesOn(day) } }
    /** A fresh read for background threads; doesn't touch the per-range cache. */
    fun balancesNow(): Map<String, Long> = balancesOn(db.today().toEpochDays().toLong()).now()
    private fun balancesOn(day: Long) = db.cached({ q.balances(day) }) { rows -> rows.associate { it.accountId to it.balance.toLong() } }

    fun saveAccount(a: Account) = q.upsertAccount(
        AccountRow(a.id, a.groupId, a.name, a.includeInTotals.toLong(), a.transferAsExpense.toLong(),
            a.cardSettlementDay?.toLong(), a.cardPaymentAccountId, a.sortOrder.toLong(), 0, db.now(), a.currency, a.cardLimit, a.hidden.toLong(),
            a.memo, a.cardPaymentDay?.toLong(), a.cardAutoPay.toLong(), a.usageTarget, a.usageTargetType.toLong(), a.smsSender, a.smsMatch)
    )

    fun deleteAccount(id: String) = q.softDeleteAccount(db.now(), id)
    fun restoreAccount(id: String) = q.restoreAccount(db.now(), id)

    fun reorderAccounts(idsInOrder: List<String>) = db.saveOrder(idsInOrder, q::setAccountOrder)
    fun reorderGroups(idsInOrder: List<String>) = db.saveOrder(idsInOrder, q::setGroupOrder)
    fun setAccountHidden(id: String, hidden: Boolean) = q.setAccountHidden(hidden.toLong(), db.now(), id)

    fun saveGroup(g: AccountGroup) = q.upsertGroup(GroupRow(g.id, g.name.trim(), g.type.name, g.sortOrder.toLong(), 0, db.now()))
    /** Only empty groups can go; returns false when accounts still use it. */
    fun deleteGroup(id: String): Boolean = db.transactionWithResult {
        if (q.allAccounts().executeAsList().any { it.groupId == id }) return@transactionWithResult false
        q.softDeleteGroup(db.now(), id); true
    }
}
