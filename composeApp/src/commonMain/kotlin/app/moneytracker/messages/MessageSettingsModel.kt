package app.moneytracker.messages

import app.moneytracker.data.CaptureStore
import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.SmsOriginal
import app.moneytracker.data.TextSetting
import app.moneytracker.domain.MessageRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Bank SMS switches and choices as stored. */
data class SmsSwitches(
    val captureEnabled: Boolean = Prefs.CAPTURE_ENABLED.default,
    val alert: Boolean = Prefs.SMS_ALERT.default,
    val autoSave: Boolean = Prefs.SMS_AUTO_SAVE.default,
    val showOriginal: SmsOriginal = Prefs.SMS_SHOW_ORIGINAL.default,
    val importDays: Int = Prefs.SMS_IMPORT_DAYS.default,
)

/** The Bank SMS filters, one entry per line. */
data class SmsFilters(
    val blockedSenders: String = Prefs.SMS_BLOCKED_SENDERS.default,
    val blockedPhrases: String = Prefs.SMS_BLOCKED_PHRASES.default,
    val contentFilter: String = Prefs.SMS_CONTENT_FILTER.default,
)

/** Everything the Bank SMS settings screen draws, in both layouts. */
data class MessageSettingsState(val switches: SmsSwitches = SmsSwitches(), val filters: SmsFilters = SmsFilters(), val waiting: Int = 0)

/** The Bank SMS settings: detection switches, how the original message is kept, import range and the filters. */
class MessageSettingsModel(
    private val settings: SettingsStore,
    captures: CaptureStore,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val switches = combine(
        settings.setting(Prefs.CAPTURE_ENABLED), settings.setting(Prefs.SMS_ALERT), settings.setting(Prefs.SMS_AUTO_SAVE),
        settings.setting(Prefs.SMS_SHOW_ORIGINAL), settings.setting(Prefs.SMS_IMPORT_DAYS), ::SmsSwitches,
    )
    private val filters = combine(
        settings.setting(Prefs.SMS_BLOCKED_SENDERS), settings.setting(Prefs.SMS_BLOCKED_PHRASES), settings.setting(Prefs.SMS_CONTENT_FILTER), ::SmsFilters,
    )

    val state: StateFlow<MessageSettingsState> =
        combine(switches, filters, captures.pendingCaptures) { s, f, waiting -> MessageSettingsState(s, f, waiting.size) }
            .stateIn(scope, SharingStarted.Eagerly, MessageSettingsState(waiting = captures.pendingCaptures.current.size))

    fun <T> put(setting: Setting<T>, value: T) {
        scope.launch { withContext(io) { settings.putSetting(setting, value) } }
    }

    /** Saves a filter list typed one per line, dropping blank lines and stray spaces. */
    fun saveFilter(filter: TextSetting, text: String) = put(filter, MessageRules.lines(text).joinToString("\n"))
}
