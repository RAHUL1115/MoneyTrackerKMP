package app.moneytracker.ui

import app.moneytracker.data.Suggestion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.moneytracker.ai.SmartParser
import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.LearningStore
import app.moneytracker.data.MoneyRepository
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

internal enum class ListenState { LISTENING, DONE, ERROR }

/** What the listening sheet shows: the words so far, how listening is going, and what Smart entry understood. */
internal data class ListenProgress(
    val heard: String = "",
    val state: ListenState = ListenState.LISTENING,
    val error: SpeechError? = null,
    val level: Float = 0f,
    val guess: SmartGuess? = null,
    /** Type mode: the same sheet with a text box; the fields fill in as you type. */
    val typing: Boolean = false,
    /** Bumped to start listening again. */
    val attempt: Int = 0,
)

/**
 * The listening sheet's state: turns what is heard (or typed) into a [SmartGuess] with the person's own learned
 * categories and usual accounts. The speech session itself stays with the sheet; this only receives its words.
 */
internal class ListenModel(
    private val learning: LearningStore,
    private val accounts: AccountStore,
    private val categories: CategoryStore,
    private val currency: CurrencyFormat,
    private val smartModels: SmartModels,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    startTyping: Boolean,
) {
    var progress by mutableStateOf(ListenProgress(typing = startTyping))
        private set

    private var parser: SmartParser? = null
    private var parseJob: Job? = null

    private val learned: suspend (List<String>) -> Suggestion? = { words -> withContext(io) { learning.suggest(words) } }
    private val usual: suspend (TxnKind, String?, String, Long) -> String? =
        { kind, category, note, amount -> withContext(io) { learning.habitAccount(kind, category, note = note, amount = amount.takeIf { it > 0 }) } }

    private suspend fun guessFor(p: SmartParser, text: String): SmartGuess =
        SmartEntry.guess(p, text, today(), currency, categories.categories.current, accounts.accounts.current, learned, usual)

    /** Loads the Smart entry model, then reads what was heard meanwhile. */
    suspend fun loadParser() {
        parser = SmartEntry.parser(smartModels)
        reparse(progress.heard)
    }

    private fun reparse(text: String) {
        val p = parser
        parseJob?.cancel()
        if (p == null || text.isBlank()) return
        parseJob = scope.launch {
            val g = guessFor(p, text)
            progress = progress.copy(guess = g)
        }
    }

    /** Understands [text] once more (loading the model if needed) and hands the result over; null when nothing was said. */
    fun finish(text: String, onReady: (SmartGuess?) -> Unit) {
        scope.launch {
            val p = parser ?: SmartEntry.parser(smartModels)
            onReady(if (p != null && text.isNotBlank()) guessFor(p, text) else null)
        }
    }

    /** A new listening attempt begins. */
    fun listening() {
        progress = progress.copy(state = ListenState.LISTENING, error = null, level = 0f)
    }

    fun onLevel(level: Float) {
        if (progress.state == ListenState.LISTENING) progress = progress.copy(level = level)
    }

    fun onPartial(text: String) {
        if (progress.typing) return
        progress = progress.copy(heard = text)
        reparse(text)
    }

    /** The final words. Returns true when something was heard and the editor should open with it. */
    fun onFinal(text: String): Boolean {
        if (progress.typing) return false
        val heard = text.ifBlank { progress.heard }
        progress = progress.copy(heard = heard)
        if (heard.isBlank()) { fail(SpeechError.NO_MATCH); return false }
        progress = progress.copy(state = ListenState.DONE)
        return true
    }

    fun fail(e: SpeechError) {
        progress = progress.copy(state = ListenState.ERROR, error = e)
    }

    fun onTyped(text: String) {
        progress = progress.copy(heard = text)
        reparse(text)
    }

    fun startTyping() {
        progress = progress.copy(typing = true)
    }

    fun startSpeaking(typed: String) {
        progress = progress.copy(typing = false, heard = typed, attempt = progress.attempt + 1)
    }

    /** After an error: forget what was heard and listen again. */
    fun retry() {
        progress = progress.copy(heard = "", guess = null, attempt = progress.attempt + 1)
    }
}
