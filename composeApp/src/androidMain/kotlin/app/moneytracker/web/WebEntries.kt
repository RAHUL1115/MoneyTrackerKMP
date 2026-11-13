package app.moneytracker.web

import app.moneytracker.data.EntryEditor
import app.moneytracker.data.ReportStore
import app.moneytracker.data.TagStore
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Txn
import app.moneytracker.domain.classify
import java.security.SecureRandom
import app.moneytracker.domain.Flow as MoneyFlow

/** The /api/txns and /api/undo routes: list, add, edit and delete entries, and take a delete back. */
internal class WebEntries(
    private val txns: TxnStore,
    private val reports: ReportStore,
    private val tags: TagStore,
    private val editor: EntryEditor,
    private val drafts: EntryDraftReader,
    private val undos: UndoTokens,
) {
    fun list(range: DateRange, period: String): Map<String, Any?> {
        val flows = reports.reportRowsNow(range).associate { it.txnId to classify(it) }
        val tagIds = HashMap<String, List<String>>()
        val tagByName = tags.tagsNow().associate { it.name to it.id }
        tags.txnTagNamesNow().forEach { (id, names) -> tagIds[id] = names.mapNotNull { tagByName[it] } }
        val rows = txns.txnsNow(range).filter { it.kind.isListed }.map { t -> entryRow(t, flows[t.id], tagIds[t.id].orEmpty()) }
        return mapOf("from" to range.start.toString(), "to" to range.endInclusive.toString(), "period" to period, "rows" to rows)
    }

    fun create(b: Map<*, *>): Map<String, Any?> {
        val id = editor.save(drafts.read(b, null))
        return mapOf("id" to id)
    }

    fun update(id: String, b: Map<*, *>): Map<String, Any?> {
        val t = editable(id)
        val base = editor.open(t.id)?.draft
        val newId = editor.save(drafts.read(b, base), existing = t)   // keeps its photos and location
        return mapOf("id" to newId)
    }

    fun delete(id: String): Map<String, Any?> {
        val t = editable(id)
        val gone = editor.delete(t) ?: return emptyMap()   // balance corrections are locked above, so never null here
        return mapOf("undo" to undos.keep(gone))
    }

    fun undo(token: String): Map<String, Any?> {
        val deleted = undos.take(token) ?: throw ApiError(410, "Too late to undo")
        val id = editor.restore(deleted)
        return mapOf("id" to id)
    }

    private fun editable(id: String): Txn {
        val t = txns.liveTxn(id) ?: throw ApiError(404, "This entry was deleted on the phone. Reload to see the latest.")
        lockReason(t)?.let { throw ApiError(409, it) }
        return t
    }
}

/** Deleted entries the page can still bring back, each under a random token for [WebApi.UNDO_MS]. */
internal class UndoTokens(private val now: () -> Long) {
    private class Undo(val deleted: EntryEditor.Deleted, val expires: Long)

    private val undos = HashMap<String, Undo>()
    private val random = SecureRandom()

    /** Keeps [deleted] for undo; returns its token. */
    fun keep(deleted: EntryEditor.Deleted): String {
        val token = random.hexId(12)
        synchronized(undos) {
            undos.values.removeAll { it.expires < now() }
            undos[token] = Undo(deleted, now() + WebApi.UNDO_MS)
        }
        return token
    }

    /** The entry kept under [token], once, or null when it is unknown or too late. */
    fun take(token: String): EntryEditor.Deleted? =
        synchronized(undos) { undos.remove(token) }?.takeIf { it.expires >= now() }?.deleted
}

/** One entry as the page's table shows it; "lock" says why the page can't edit it. */
internal fun entryRow(t: Txn, flow: MoneyFlow?, tags: List<String>): Map<String, Any?> = mapOf(
    "id" to t.id, "kind" to t.kind.name, "date" to t.date.toString(), "time" to t.timeOfDay,
    "account" to t.accountId, "to" to t.toAccountId, "category" to t.categoryId,
    "amount" to t.amount, "entered" to t.enteredAmount, "currency" to t.currency, "accountAmount" to t.accountValue,
    "note" to t.note, "memo" to t.memo, "tags" to tags, "transfer" to (t.transferGroupId != null),
    "installment" to t.installmentLabel, "repeat" to (t.recurringRuleId != null),
    "flow" to when (flow) { MoneyFlow.INCOME -> "income"; MoneyFlow.EXPENSE -> "expense"; else -> "none" },
    "lock" to lockReason(t),
)

/** Entries only the phone edits, with the reason shown on the page; null when the page may edit it. */
internal fun lockReason(t: Txn): String? = when {
    t.kind.isAdjustment -> "Balance adjustments are edited on the phone."
    t.installmentGroupId != null -> "Instalment plans are edited on the phone."
    t.isTransferFee -> "This is a transfer fee: edit the transfer instead."
    else -> null
}
