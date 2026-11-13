package app.moneytracker.ui.accounts

import app.moneytracker.data.AccountStore
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Groups and accounts in their order, with the deleted ones that can be restored. */
data class ManageAccountsState(
    val groups: List<AccountGroup>,
    val accounts: List<Account>,
    val deletedAccounts: List<Account> = emptyList(),
    val deletedGroups: List<AccountGroup> = emptyList(),
)

/**
 * Ordering, hiding, restoring and editing groups and accounts: the Manage accounts sheet, the Accounts list and the
 * Account groups screen. Writes outlive the screen so their Undo keeps working.
 */
class ManageAccountsModel(private val store: AccountStore, private val io: CoroutineDispatcher, private val scope: CoroutineScope) {
    private val outliving = scope.outliving()

    val state: StateFlow<ManageAccountsState> =
        combine(store.groups, store.accounts, store.deletedAccounts, store.deletedGroups, ::ManageAccountsState)
            .stateIn(scope, SharingStarted.Eagerly, ManageAccountsState(store.groups.current, store.accounts.current))

    /** Swaps an account with its neighbour [delta] places away inside its group; nothing happens at either end. */
    fun moveAccount(id: String, delta: Int) {
        val all = state.value.accounts
        val account = all.firstOrNull { it.id == id } ?: return
        val inGroup = all.filter { it.groupId == account.groupId }
        val other = inGroup.getOrNull(inGroup.indexOf(account) + delta) ?: return
        val order = all.map { it.id }.toMutableList()
        val x = order.indexOf(account.id)
        val y = order.indexOf(other.id)
        order[x] = other.id
        order[y] = account.id
        write { store.reorderAccounts(order) }
    }

    /** Swaps the group at [index] with the one [delta] places away; nothing happens at either end. */
    fun moveGroup(index: Int, delta: Int) {
        val order = state.value.groups.map { it.id }.toMutableList()
        val j = index + delta
        if (index !in order.indices || j !in order.indices) return
        order[index] = order[j].also { order[j] = order[index] }
        write { store.reorderGroups(order) }
    }

    fun setHidden(id: String, hidden: Boolean) = write { store.setAccountHidden(id, hidden) }

    /** Hides or shows every account in a group; returns each account's previous setting, for [restoreHidden]. */
    fun setGroupHidden(groupId: String, hidden: Boolean): Map<String, Boolean> {
        val inGroup = state.value.accounts.filter { it.groupId == groupId }
        write { inGroup.forEach { store.setAccountHidden(it.id, hidden) } }
        return inGroup.associate { it.id to it.hidden }
    }

    fun restoreHidden(before: Map<String, Boolean>) = write { before.forEach { (id, hidden) -> store.setAccountHidden(id, hidden) } }

    fun restoreAccount(id: String) = write { store.restoreAccount(id) }

    fun deleteAccount(id: String) = write { store.deleteAccount(id) }

    /** A blank group placed after the others, for the editor; nothing is stored until [saveGroup]. */
    fun newGroup(): AccountGroup = AccountGroup(newId(), "", AccountType.OTHER, state.value.groups.size)

    fun saveGroup(g: AccountGroup) = write { store.saveGroup(g) }

    /** Deletes an empty group; [onRefused] runs when it still has accounts. */
    fun deleteGroup(id: String, onRefused: () -> Unit) {
        scope.launch {
            val ok = withContext(io) { store.deleteGroup(id) }
            if (!ok) onRefused()
        }
    }

    fun restoreGroup(id: String) = write { store.restoreGroup(id) }

    private fun write(block: () -> Unit) {
        outliving.launch { withContext(io) { block() } }
    }
}
