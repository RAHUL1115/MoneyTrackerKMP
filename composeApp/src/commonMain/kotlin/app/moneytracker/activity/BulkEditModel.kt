package app.moneytracker.activity

import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Txn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** What a change to several entries did: [changed] of [total] took it (null = all could), and how to put them back. */
class BulkResult(val changed: Int?, val total: Int, val undo: () -> Unit) {
    /** Some entries kept their old value: transfers keep their accounts and have no category. */
    val partial: Boolean get() = changed != null && changed < total
}

/**
 * Changes the selected entries at once (date, category, account, delete), in both layouts. Each change remembers the
 * old values so it can be undone. Pass a [scope] that outlives the screen when Undo can come after it closes.
 */
class BulkEditModel(private val txns: TxnStore, private val io: CoroutineDispatcher, private val scope: CoroutineScope) {

    fun setDate(selected: List<Txn>, date: LocalDate, onDone: (BulkResult) -> Unit) {
        val ids = selected.map { it.id }
        val before = selected.groupBy({ it.date }, { it.id })
        change(ids.size, onDone, { txns.setDateMany(ids, date); null }) {
            before.forEach { (d, list) -> txns.setDateMany(list, d) }
        }
    }

    fun setCategory(selected: List<Txn>, categoryId: String, onDone: (BulkResult) -> Unit) {
        val ids = selected.map { it.id }
        val before = selected.mapNotNull { t -> t.categoryId?.let { it to t.id } }.groupBy({ it.first }, { it.second })
        change(ids.size, onDone, { txns.setCategoryMany(ids, categoryId) }) {
            before.forEach { (cat, list) -> txns.setCategoryMany(list, cat) }
        }
    }

    fun setAccount(selected: List<Txn>, accountId: String, onDone: (BulkResult) -> Unit) {
        val ids = selected.map { it.id }
        val before = selected.groupBy({ it.accountId }, { it.id })
        change(ids.size, onDone, { txns.setAccountMany(ids, accountId) }) {
            before.forEach { (acc, list) -> txns.setAccountMany(list, acc) }
        }
    }

    /** Deletes the selected entries (transfers with both sides); no undo. */
    fun delete(selected: List<Txn>, onDone: () -> Unit) {
        val ids = selected.map { it.id }
        scope.launch {
            withContext(io) { txns.deleteMany(ids) }
            onDone()
        }
    }

    private fun change(total: Int, onDone: (BulkResult) -> Unit, apply: () -> Int?, undo: () -> Unit) {
        scope.launch {
            val changed = withContext(io) { apply() }
            onDone(BulkResult(changed, total) { scope.launch { withContext(io) { undo() } } })
        }
    }
}
