package app.moneytracker.ui.accounts

import app.moneytracker.data.EntryEditor
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Txn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The money actions on an account's detail screen: correcting its balance and paying a card bill. Each runs past the
 * screen's end and hands back what it added, so the toast's Undo can take it away again.
 */
class AccountActionsModel(
    private val accountId: String,
    private val txns: TxnStore,
    private val editor: EntryEditor,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
) {
    private val outliving = scope.outliving()

    /** Sets the balance to [target], recording the difference as income or expense when [asIncomeExpense]; [onDone] gets the entries added. */
    fun adjustBalance(target: Long, asIncomeExpense: Boolean, onDone: (List<Txn>) -> Unit = {}) {
        outliving.launch {
            val added = withContext(io) {
                val before = txns.accountTxns(accountId).map { it.id }.toSet()
                txns.adjustBalance(accountId, target, asIncomeExpense)
                txns.accountTxns(accountId).filter { it.id !in before }
            }
            onDone(added)
        }
    }

    fun undoAdjust(added: List<Txn>) {
        outliving.launch { withContext(io) { added.forEach { txns.delete(it) } } }
    }

    /** Saves a card payment; [onPaid] gets its id. */
    fun pay(payment: TxnDraft, onPaid: (String) -> Unit) {
        outliving.launch { onPaid(withContext(io) { editor.save(payment) }) }
    }

    fun undoPay(id: String) {
        outliving.launch { withContext(io) { editor.delete(id) } }
    }
}
