package app.moneytracker.data

import app.moneytracker.core.Log
import app.moneytracker.db.Capture as CaptureRow
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus

/** Detected payments: bank messages read and waiting until they are added or dismissed. */
class CaptureStore(private val db: Db) {
    private val q get() = db.q

    val pendingCaptures: CachedFlow<List<Capture>> = db.cached({ q.pendingCaptures() }) { rows -> rows.map { it.toDomain() } }

    /** Stores a parsed message unless the same text from the same sender arrived in the last 2 minutes. */
    fun addCapture(c: Capture): Boolean = db.transactionWithResult {
        if (q.captureBySenderAndText(c.sender, c.text, c.receivedAt - 120_000).executeAsOneOrNull() != null) return@transactionWithResult false
        q.insertCapture(CaptureRow(c.id, c.receivedAt, c.source, c.sender, c.text, c.amount, c.kind?.name, c.merchant,
            c.accountId, c.status.name, c.txnId))
        true
    }

    fun setCaptureStatus(id: String, status: CaptureStatus, txnId: String? = null) {
        Log.d("Sms") { "capture $id → $status" }
        q.setCaptureStatus(status.name, txnId, id)
    }
}
