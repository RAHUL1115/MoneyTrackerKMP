package app.moneytracker.data

import app.moneytracker.domain.Periods
import kotlinx.datetime.DayOfWeek

/**
 * One stored setting: its key in the settings table, its default, and how the stored text reads and writes.
 * Reading never fails: a missing or unreadable value gives [default].
 */
sealed class Setting<T>(val key: String, val default: T) {
    abstract fun read(stored: String?): T
    abstract fun write(value: T): String
    override fun toString() = key
}

/** On/off, stored "1" / "0". */
class FlagSetting(key: String, default: Boolean = false) : Setting<Boolean>(key, default) {
    override fun read(stored: String?) = when (stored) { "1" -> true; "0" -> false; else -> default }
    override fun write(value: Boolean) = if (value) "1" else "0"
}

/** A whole number, kept inside [range]. */
class NumberSetting(key: String, default: Int, val range: IntRange) : Setting<Int>(key, default) {
    override fun read(stored: String?) = stored?.trim()?.toIntOrNull()?.coerceIn(range) ?: default
    override fun write(value: Int) = value.coerceIn(range).toString()
}

/** A moment in epoch milliseconds; 0 = never. */
class MomentSetting(key: String) : Setting<Long>(key, 0L) {
    override fun read(stored: String?) = stored?.trim()?.toLongOrNull() ?: default
    override fun write(value: Long) = value.toString()
}

/** Free text, stored as is. */
class TextSetting(key: String, default: String = "") : Setting<String>(key, default) {
    override fun read(stored: String?) = stored ?: default
    override fun write(value: String) = value
}

/** A time of day, stored "HH:mm". */
data class ClockTime(val hour: Int, val minute: Int) {
    override fun toString() = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    companion object {
        /** "21:00" or "9:5"; null unless both parts are a real time. */
        fun parse(text: String): ClockTime? {
            val parts = text.trim().split(":").map { it.trim().toIntOrNull() }
            val (h, m) = parts.takeIf { it.size == 2 } ?: return null
            return if (h != null && m != null && h in 0..23 && m in 0..59) ClockTime(h, m) else null
        }
    }
}

class ClockSetting(key: String, default: ClockTime) : Setting<ClockTime>(key, default) {
    override fun read(stored: String?) = stored?.let(ClockTime::parse) ?: default
    override fun write(value: ClockTime) = value.toString()
}

/** One of [options], stored by [code] (the enum name unless it says otherwise). */
class ChoiceSetting<E : Enum<E>>(
    key: String,
    default: E,
    val options: List<E>,
    private val code: (E) -> String = { it.name },
) : Setting<E>(key, default) {
    override fun read(stored: String?) = options.firstOrNull { code(it) == stored } ?: default
    override fun write(value: E) = code(value)
}

enum class StartScreen { DAILY, CALENDAR, MONTHLY, TOTAL, NOTE }
enum class TimeInput { NONE, INPUT, LIST }
enum class TimeSort { DESC, ASC }
enum class InputOrder { AMOUNT, ACCOUNT }
enum class Swipe { DATE, TAB, NONE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class RepeatTiming { ON_DATE, MONTH_START }
enum class UiLayout { CLASSIC, NEW }

/** Income and expense colours: the usual pair, or the two swapped. */
enum class ColorSet(val code: String) { NORMAL("A"), SWAPPED("B") }

/** What a detected message leaves on its entry. */
enum class SmsOriginal(val code: String) { KEEP("1"), OFF("0"), WHILE_EDITING("2") }

/**
 * Every setting the app stores, each with its default written once. Read with `settingsStore.setting(Prefs.X)` (live) or
 * `settingsStore.settingNow(Prefs.X)`, write with `settingsStore.putSetting(Prefs.X, value)`.
 */
object Prefs {
    // Money
    val CURRENCY_SYMBOL = TextSetting("currencySymbol", "₹")
    val CURRENCY_DECIMALS = NumberSetting("currencyDecimals", 2, 0..3)
    val CURRENCY_CODE = TextSetting("currencyCode", "INR")
    val SYMBOL_AFTER = FlagSetting("symbolAfter")                       // "1,000.00 ₹"
    val CARRY_OVER = FlagSetting("carryOver")                           // show the balance carried from earlier months
    val CARD_AT_PAYMENT = FlagSetting("cardAtPayment")                  // card purchases count when the bill is paid
    val RATES_AUTO = FlagSetting("ratesAuto", default = true)           // daily exchange-rate update
    val RATES_DATE = TextSetting("ratesDate")                           // ECB date (yyyy-mm-dd) of the rates last fetched
    val RATES_CHECKED = MomentSetting("ratesChecked")                   // last successful fetch

    // Calendar
    val MONTH_START_DAY = NumberSetting("monthStartDay", 1, 1..31)
    val WEEKEND_SHIFT = ChoiceSetting("weekendShift", Periods.WeekendShift.NONE, Periods.WeekendShift.entries)
    val WEEK_START = ChoiceSetting("weekStart", DayOfWeek.SUNDAY, DayOfWeek.entries)

    // Look
    val THEME = ChoiceSetting("theme", ThemeMode.SYSTEM, ThemeMode.entries)
    val ACCENT = TextSetting("accent", "TEAL")                          // an AccentChoice name
    val COLOR_SET = ChoiceSetting("colorSet", ColorSet.NORMAL, ColorSet.entries) { it.code }
    val LANGUAGE = TextSetting("language")                              // a tag from I18n.LANGUAGES; empty = phone language
    val WIDGET_THEME = ChoiceSetting("widgetTheme", ThemeMode.SYSTEM, ThemeMode.entries)
    val WIDGET_OPACITY = NumberSetting("widgetOpacity", 100, 0..100)    // %
    val UI_LAYOUT = ChoiceSetting("uiLayout", UiLayout.CLASSIC, UiLayout.entries) // read once at start; a change restarts

    // Entering
    val INPUT_ORDER = ChoiceSetting("inputOrder", InputOrder.AMOUNT, InputOrder.entries)
    val TIME_INPUT = ChoiceSetting("timeInput", TimeInput.NONE, TimeInput.entries)
    val HABIT_ACCOUNT = FlagSetting("habitAccount", default = true)     // new entries pre-select the usual account
    val AUTOCOMPLETE = FlagSetting("autocomplete", default = true)
    val AUTOCOMPLETE_SINCE = MomentSetting("autocompleteSince")         // notes used before this aren't suggested ("Reset")
    val LOCATION = FlagSetting("locationTagging")
    val PHOTO_NOTICE_SHOWN = FlagSetting("photoNoticeShown")            // the one-time note about where photos are kept
    val USE_SUBCATEGORIES = FlagSetting("useSubcategories")             // off: main categories only
    val ACCOUNTS_GROUPED = FlagSetting("accountsGrouped")               // account picker: groups left, accounts right
    val REPEAT_TIMING = ChoiceSetting("repeatTiming", RepeatTiming.ON_DATE, RepeatTiming.entries)

    // Lists
    val START_SCREEN = ChoiceSetting("startScreen", StartScreen.DAILY, StartScreen.entries)
    val SWIPE = ChoiceSetting("swipe", Swipe.DATE, Swipe.entries)
    val TIME_SORT = ChoiceSetting("timeSort", TimeSort.DESC, TimeSort.entries)
    val SHOW_DESCRIPTION = FlagSetting("showDescription")               // description under the note in lists
    val NOTE_BUTTON = FlagSetting("noteButton", default = true)         // note button next to + on Daily (classic)
    val HIDE_AMOUNTS = FlagSetting("hideAmounts")                       // amounts show as •••• until the eye is tapped

    // Reminders
    val REMINDER = FlagSetting("reminder")
    val REMINDER_TIME = ClockSetting("reminderTime", ClockTime(21, 0))
    val QUICK_ADD_NOTIFICATION = FlagSetting("quickAddNotification")

    // Security
    val PASSCODE = TextSetting("passcodeHash")                          // empty = no passcode
    /** The passcode in use before a restore, accepted once at the lock screen in case the backup's can't be checked here. */
    val PASSCODE_PREVIOUS = TextSetting("passcodeHashBeforeRestore")
    val BIOMETRIC = FlagSetting("biometric")

    // Bank SMS
    val CAPTURE_ENABLED = FlagSetting("captureEnabled")
    val SMS_ALERT = FlagSetting("smsAlert", default = true)             // notify when a message is detected
    val SMS_AUTO_SAVE = FlagSetting("smsAutoSave")                      // save detected messages as entries right away
    val SMS_SHOW_ORIGINAL = ChoiceSetting("smsShowOriginal", SmsOriginal.KEEP, SmsOriginal.entries) { it.code }
    val SMS_BLOCKED_SENDERS = TextSetting("smsBlockedSenders")          // one per line
    val SMS_BLOCKED_PHRASES = TextSetting("smsBlockedPhrases")          // one per line
    val SMS_CONTENT_FILTER = TextSetting("smsContentFilter")            // one per line: removed before reading a message
    val SMS_IMPORT_DAYS = NumberSetting("smsImportDays", 30, 1..365)    // "Import SMS": how far back to read the inbox
    val SMS_LAST_SCAN = MomentSetting("smsLastScan")                    // last catch-up read of the inbox

    // Web dashboard
    val WEB_PASSWORD = TextSetting("webPassword")                       // salted hash; empty = no password
    val WEB_AUTO_STOP = NumberSetting("webAutoStop", 30, 0..24 * 60)    // minutes without a visit; 0 = never

    // App state
    val ONBOARDED = FlagSetting("onboarded")
    val SEEDED = FlagSetting("seeded")

    /** Keys of settings whose features are gone; removed when the database opens (old backups can bring them back). */
    val retired: List<String> = listOf("entryStyle", "keyboardHeightDp", "ratesOnline")

    /** Written by the app for itself, never shown: a change to one alone doesn't redraw the widgets. */
    val bookkeeping: Set<String> = setOf(SMS_LAST_SCAN.key, RATES_CHECKED.key)

    /** Preferences a Money Manager backup can carry ([MoneyManagerExport] writes them; the importer reads them back). */
    val carriedByMoneyManager: List<Setting<*>> = listOf(
        MONTH_START_DAY, WEEKEND_SHIFT, WEEK_START, CARRY_OVER, CARD_AT_PAYMENT, USE_SUBCATEGORIES,
        START_SCREEN, SWIPE, COLOR_SET, TIME_INPUT, TIME_SORT, SHOW_DESCRIPTION, AUTOCOMPLETE, NOTE_BUTTON,
        INPUT_ORDER, REPEAT_TIMING, SMS_BLOCKED_SENDERS, SMS_BLOCKED_PHRASES, SMS_CONTENT_FILTER, SMS_AUTO_SAVE,
        SMS_ALERT, SMS_SHOW_ORIGINAL, SMS_IMPORT_DAYS, SYMBOL_AFTER, QUICK_ADD_NOTIFICATION,
    )
}
