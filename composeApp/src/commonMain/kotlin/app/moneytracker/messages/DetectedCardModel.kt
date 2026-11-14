package app.moneytracker.messages

import app.moneytracker.data.CaptureStore
import app.moneytracker.data.MessageIntake
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Capture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The newest detected bank SMS payment, the entry it would make, and how many are waiting in all. */
class DetectedCard(val capture: Capture, val draft: TxnDraft, val waiting: Int)

/** The detected-payment card at the top of the classic Daily list; null while nothing is waiting. */
class DetectedCardModel(captures: CaptureStore, private val intake: MessageIntake, private val io: CoroutineDispatcher, scope: CoroutineScope) {
    /** The draft is worked out once per capture, as it arrives. */
    private var drafted: Pair<String, TxnDraft>? = null

    val state: StateFlow<DetectedCard?> = captures.pendingCaptures.map { list ->
        // Newest first: the payment that just arrived is the one on screen.
        val newest = list.maxByOrNull { it.receivedAt } ?: return@map null
        DetectedCard(newest, draftFor(newest), list.size)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private suspend fun draftFor(c: Capture): TxnDraft {
        drafted?.let { (id, draft) -> if (id == c.id) return draft }
        return withContext(io) { intake.draftFromCapture(c) }.also { drafted = c.id to it }
    }
}
