package app.moneytracker.ui

import app.moneytracker.data.NoteSuggestion
import app.moneytracker.data.CachedFlow
import app.moneytracker.data.DraftType
import app.moneytracker.data.LearningStore
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.SmsOriginal
import app.moneytracker.data.TxnStore
import app.moneytracker.data.primaryKind
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.Txn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * What the entry form offers around the fields, in both layouts: a category learned from the note's words, past notes
 * matching what is typed, recent entries (the account tray lists their accounts first), the one-time photo notice and
 * the classic account picker's layout.
 */
class EntryHints(
    private val learning: LearningStore,
    private val txns: TxnStore,
    private val settings: SettingsStore,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    /** The category the person's own entries suggest for [note] (tiny Naive Bayes, a few SQL rows). */
    suspend fun learnedCategory(note: String, type: DraftType): String? =
        if (note.length < 3 || type == DraftType.TRANSFER) null
        else withContext(io) { learning.suggest(SmsParser.words(note)) }.categoryId

    /** Up to [limit] past notes starting like [note], from entries of the same kind. */
    suspend fun noteMatches(note: String, type: DraftType, limit: Int): List<NoteSuggestion> =
        if (note.isBlank()) emptyList()
        else withContext(io) { learning.noteSuggestions(note) }.filter { it.kind == type.primaryKind() }.take(limit)

    /** Entries of the last 60 days. */
    val recentTxns: CachedFlow<List<Txn>> by lazy {
        val end = today()
        txns.txnsBetween(DateRange(end.minus(DatePeriod(days = 60)), end))
    }

    fun photoNoticeSeen(): Boolean = settings.settingNow(Prefs.PHOTO_NOTICE_SHOWN)

    fun markPhotoNoticeSeen() {
        scope.launch(io) { settings.putSetting(Prefs.PHOTO_NOTICE_SHOWN, true) }
    }

    /** The classic account picker: groups on the left and their accounts on the right, or one grid. */
    fun groupAccounts(on: Boolean) {
        scope.launch(io) { settings.putSetting(Prefs.ACCOUNTS_GROUPED, on) }
    }

    /** Settings › Bank SMS: show the message an entry came from. */
    fun showsBankMessage(): Boolean = settings.settingNow(Prefs.SMS_SHOW_ORIGINAL) != SmsOriginal.OFF
}
