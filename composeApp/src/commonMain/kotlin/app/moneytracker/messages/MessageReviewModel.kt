package app.moneytracker.messages

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CaptureStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.MessageIntake
import app.moneytracker.data.Prefs
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.Category
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/** The bank messages waiting for review, the names to show them with, and whether detection is on. */
data class MessageReviewState(
    val captures: List<Capture>,
    val categoryById: Map<String, Category>,
    val accountNames: Map<String, String>,
    val captureEnabled: Boolean,
    val importDays: Int,
)

/** The bank SMS review queue: each waiting message with the entry it suggests, dismissing one, and pasting one in. */
class MessageReviewModel(
    private val captures: CaptureStore,
    private val intake: MessageIntake,
    settings: SettingsStore,
    accounts: AccountStore,
    categories: CategoryStore,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<MessageReviewState> = combine(
        captures.pendingCaptures, categories.categoryById, accounts.accountNames,
        settings.setting(Prefs.CAPTURE_ENABLED), settings.setting(Prefs.SMS_IMPORT_DAYS), ::MessageReviewState,
    ).stateIn(
        scope, SharingStarted.Eagerly,
        MessageReviewState(
            captures.pendingCaptures.current, categories.categoryById.current, accounts.accountNames.current,
            Prefs.CAPTURE_ENABLED.default, Prefs.SMS_IMPORT_DAYS.default,
        ),
    )

    private val draftsByCapture = MutableStateFlow(emptyMap<String, TxnDraft>())

    /**
     * Every waiting message's suggested entry, by capture id. The previous map stays while a new one is worked out,
     * so cards never flash half-filled.
     */
    val drafts: StateFlow<Map<String, TxnDraft>> = draftsByCapture.asStateFlow()

    init {
        scope.launch {
            captures.pendingCaptures.collectLatest { list ->
                draftsByCapture.value = withContext(io) { list.associate { it.id to intake.draftFromCapture(it) } }
            }
        }
    }

    /** Sets the message aside; [onDone] runs once that is stored (to offer Undo). */
    fun dismiss(c: Capture, onDone: () -> Unit) {
        scope.launch {
            withContext(io) { captures.setCaptureStatus(c.id, CaptureStatus.DISMISSED) }
            onDone()
        }
    }

    /** Undo for [dismiss]. */
    fun undoDismiss(c: Capture) {
        scope.launch { withContext(io) { captures.setCaptureStatus(c.id, CaptureStatus.PENDING) } }
    }

    /** Reads a pasted bank message; [onRead] gets the new capture, or null when it isn't a payment or was already added. */
    fun paste(text: String, onRead: (Capture?) -> Unit) {
        scope.launch { onRead(withContext(io) { intake.ingestMessage("PASTE", "", text, Clock.System.now().toEpochMilliseconds()) }) }
    }
}
