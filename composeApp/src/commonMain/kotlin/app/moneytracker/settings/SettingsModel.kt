package app.moneytracker.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.moneytracker.core.BackgroundJobs
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.tr
import app.moneytracker.data.ClockTime
import app.moneytracker.data.LearningStore
import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.UiLayout
import app.moneytracker.entry.classic.save
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every change the settings screens make, shared by both layouts. Saves run off the main thread in the screen's
 * [scope], so they finish even after the sheet or dialog that started them has closed.
 */
class SettingsModel(
    private val settings: SettingsStore,
    private val learning: LearningStore,
    private val backgroundJobs: BackgroundJobs,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    fun <T> save(s: Setting<T>, value: T) {
        scope.launch(io) { settings.putSetting(s, value) }
    }

    /** Forgets the notes, categories and accounts learned from past entries; [then] runs once that is done. */
    fun resetSuggestions(then: () -> Unit = {}) {
        scope.launch {
            withContext(io) { learning.resetAutocomplete() }
            then()
        }
    }

    /** The text setting [s] with its current value. Blocking read. */
    fun textEdit(s: Setting<*>): TextEdit = textEditFor(s, settings)

    /** Saves [text] for [s]; a new reminder time also reschedules the reminder. */
    fun saveText(s: Setting<*>, edit: TextEdit, text: String) {
        scope.launch {
            withContext(io) { edit.save(settings, text) }
            if (s == Prefs.REMINDER_TIME) ClockTime.parse(text)?.let { backgroundJobs.setReminder(true, it.hour, it.minute) {} }
        }
    }

    /** The daily reminder; the switch follows whether the phone allowed it. */
    fun setReminder(on: Boolean, time: ClockTime) =
        backgroundJobs.setReminder(on, time.hour, time.minute) { active -> save(Prefs.REMINDER, active) }

    /** The Expense / Income / Transfer buttons in the notification bar; the switch follows whether the phone allowed them. */
    fun setQuickAdd(on: Boolean) = backgroundJobs.setQuickAddNotification(on) { active -> save(Prefs.QUICK_ADD_NOTIFICATION, active) }

    /** Saves the layout, then [restart]s the app, which picks its layout only when it starts. */
    fun switchLayout(toNew: Boolean, restart: () -> Unit) {
        scope.launch {
            withContext(io) { settings.putSetting(Prefs.UI_LAYOUT, layoutOf(toNew)) }
            restart()
        }
    }

    /** The welcome pages' choices. Only the new layout needs a [restart]: the app starts in classic. */
    fun finishOnboarding(symbol: String, decimals: Int, newLayout: Boolean, restart: () -> Unit) {
        scope.launch {
            withContext(io) {
                settings.putSetting(Prefs.CURRENCY_SYMBOL, symbol.ifBlank { "₹" })
                settings.putSetting(Prefs.CURRENCY_DECIMALS, decimals)
                settings.putSetting(Prefs.UI_LAYOUT, layoutOf(newLayout))
                settings.putSetting(Prefs.ONBOARDED, true)
            }
            if (newLayout) restart()
        }
    }

    private fun layoutOf(new: Boolean) = if (new) UiLayout.NEW else UiLayout.CLASSIC
}

/** A [SettingsModel] that saves in the calling screen's coroutine scope. */
@Composable
fun rememberSettingsModel(): SettingsModel {
    val repo = LocalRepo.current
    val backgroundJobs = LocalPlatform.current.backgroundJobs
    val scope = rememberCoroutineScope()
    return remember(repo, backgroundJobs) { SettingsModel(repo.settingsStore, repo.learningStore, backgroundJobs, repo.io, scope) }
}

/** One of the text settings (currency symbol, decimal places, month start day, reminder time): its title, starting text, check and save. */
class TextEdit(
    val title: String,
    val initial: String,
    /** Number keyboard. */
    val number: Boolean,
    private val check: (String) -> Boolean,
    private val store: (SettingsStore, String) -> Unit,
) {
    fun valid(text: String) = check(text.trim())
    /** Blocking; call off the main thread. */
    fun save(settings: SettingsStore, text: String) = store(settings, text.trim())
}

/** The text setting [s] as its edit sheet or dialog shows it, read once when that opens. */
@Composable
fun rememberTextEdit(model: SettingsModel, s: Setting<*>): TextEdit = remember(model, s) { model.textEdit(s) }

private fun textEditFor(s: Setting<*>, settings: SettingsStore): TextEdit = when (s) {
    Prefs.MONTH_START_DAY -> TextEdit(tr("Monthly start date (1–31)"), settings.settingNow(Prefs.MONTH_START_DAY).toString(), true,
        { it.toIntOrNull() in 1..31 }) { st, t -> st.putSetting(Prefs.MONTH_START_DAY, t.toInt()) }
    Prefs.CURRENCY_DECIMALS -> TextEdit(tr("Decimal places (0–3)"), settings.settingNow(Prefs.CURRENCY_DECIMALS).toString(), true,
        { it.toIntOrNull() in 0..3 }) { st, t -> st.putSetting(Prefs.CURRENCY_DECIMALS, t.toInt()) }
    Prefs.CURRENCY_SYMBOL -> TextEdit(tr("Currency symbol"), settings.settingNow(Prefs.CURRENCY_SYMBOL), false,
        { it.isNotEmpty() && it.length <= 4 }) { st, t -> st.putSetting(Prefs.CURRENCY_SYMBOL, t) }
    Prefs.REMINDER_TIME -> TextEdit(tr("Reminder time (24h, HH:mm)"), settings.settingNow(Prefs.REMINDER_TIME).toString(), false,
        { ClockTime.parse(it) != null }) { st, t -> ClockTime.parse(t)?.let { st.putSetting(Prefs.REMINDER_TIME, it) } }
    else -> error("$s is not a text setting")
}
