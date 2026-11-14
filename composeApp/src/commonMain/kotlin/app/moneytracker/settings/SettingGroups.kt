package app.moneytracker.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.I18n
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.rememberSetting
import app.moneytracker.core.tr
import app.moneytracker.data.FlagSetting
import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.designsystem.classic.accentChoice
import app.moneytracker.designsystem.classic.label
import app.moneytracker.domain.Periods
import app.moneytracker.entry.classic.save
import app.moneytracker.settings.classic.ChoiceSpec
import app.moneytracker.settings.classic.SettingActions
import app.moneytracker.settings.classic.SettingGroup
import app.moneytracker.settings.classic.SettingRow
import app.moneytracker.settings.classic.choiceSpecs

/*
 * One settings structure for both layouts: the More places, the combined screens and the six preference groups
 * are defined once, and each layout only draws them in its own style.
 */

/** Money, Calendar, Look, Entering, Lists, Reminders. */
@Composable
fun rememberSettingGroups(newLayout: Boolean, a: SettingActions, model: SettingsModel, overview: SettingsOverview): List<SettingGroup> {
    val specs = choiceSpecs(newLayout)
    // Each choice's current label, read live.
    val labels: Map<Setting<*>, String> = specs.associate { spec -> spec.setting to currentLabel(spec) }
    val rows = RowMaker(specs, labels, a, model)
    return listOf(
        moneyGroup(rows, overview.otherCurrencies.joinToString(", ")),
        calendarGroup(rows),
        lookGroup(rows, newLayout),
        enteringGroup(rows),
        listsGroup(rows, newLayout),
        remindersGroup(rows, model),
    )
}

/** Builds the three kinds of row: a link, an on/off switch and a multiple choice that opens [SettingActions.choice]. */
private class RowMaker(
    private val specs: List<ChoiceSpec<*>>,
    private val labels: Map<Setting<*>, String>,
    val a: SettingActions,
    private val model: SettingsModel,
) {
    fun label(s: Setting<*>) = labels.getValue(s)
    fun choose(s: Setting<*>) = a.choice(specs.first { it.setting == s })
    fun setFlag(s: FlagSetting, on: Boolean) = model.save(s, on)

    fun link(title: String, value: String? = null, words: String = "", subtitle: String? = null, onClick: () -> Unit) =
        SettingRow(title, value, subtitle, words, onClick = onClick)
    fun toggle(title: String, checked: Boolean, subtitle: String? = null, words: String = "", onChange: (Boolean) -> Unit) =
        SettingRow(title, null, subtitle, words, checked) { onChange(!checked) }
    fun pick(s: Setting<*>, title: String, words: String = "") = SettingRow(title, label(s), null, words) { choose(s) }
}

@Composable
private fun moneyGroup(r: RowMaker, others: String): SettingGroup {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val symbolAfter by rememberSetting(Prefs.SYMBOL_AFTER)
    val carryOver by rememberSetting(Prefs.CARRY_OVER)
    val cardAtPayment by rememberSetting(Prefs.CARD_AT_PAYMENT)
    return SettingGroup("money", tr("Money"),
        listOfNotNull(currency.symbol, tr("{0} decimals", currency.decimals), others.takeIf { it.isNotEmpty() }?.let { tr("also {0}", it) }).joinToString(" · "),
        listOf(
            r.link(tr("Currency symbol"), currency.symbol, "rupee money") { r.a.edit(Prefs.CURRENCY_SYMBOL) },
            r.toggle(tr("Symbol after amount"), symbolAfter,
                if (symbolAfter) "1,000.00 ${currency.symbol}" else "${currency.symbol} 1,000.00", "currency") { r.setFlag(Prefs.SYMBOL_AFTER, it) },
            r.link(tr("Decimal places"), currency.decimals.toString(), "paise cents rounding",
                tr("Set before entering data; existing amounts are not converted.")) { r.a.edit(Prefs.CURRENCY_DECIMALS) },
            r.link(tr("Other currencies"), others.ifEmpty { null }, "sub currency dollar exchange rate foreign") { nav.push(Screen.Currencies) },
            r.toggle(tr("Carry-over"), carryOver, tr("Show the balance carried from earlier months"), "previous month balance") {
                r.setFlag(Prefs.CARRY_OVER, it)
            },
            r.toggle(tr("Card expenses at payment"), cardAtPayment, tr("Count credit-card purchases when the bill is paid, not when you buy"), "credit bill") {
                r.setFlag(Prefs.CARD_AT_PAYMENT, it)
            },
        ))
}

@Composable
private fun calendarGroup(r: RowMaker): SettingGroup {
    val startDay by rememberSetting(Prefs.MONTH_START_DAY)
    val weekendShift by rememberSetting(Prefs.WEEKEND_SHIFT)
    return SettingGroup("calendar", tr("Calendar"), tr("Month from day {0} · week from {1}", startDay, r.label(Prefs.WEEK_START)), listOf(
        r.link(tr("Month starts on"), tr("Every {0}", startDay), "monthly start date salary period cycle") { r.a.edit(Prefs.MONTH_START_DAY) },
        r.link(tr("If that day is a weekend"), weekendShort(weekendShift), "friday monday shift") { r.choose(Prefs.WEEKEND_SHIFT) },
        r.pick(Prefs.WEEK_START, tr("Week starts on"), "weekly start day"),
    ))
}

@Composable
private fun lookGroup(r: RowMaker, newLayout: Boolean): SettingGroup {
    val languageTag by rememberSetting(Prefs.LANGUAGE)
    val language = I18n.LANGUAGES.firstOrNull { it.first == languageTag }?.takeIf { it.first.isNotEmpty() }?.second ?: tr("Phone language")
    return SettingGroup("look", tr("Look"), listOf(r.label(Prefs.THEME), tr(accentChoice.label), language).joinToString(" · "), listOf(
        r.link(tr("Layout"), if (newLayout) tr("New") else tr("Classic"), "layout classic new design restart", tr("Classic or New")) { r.a.switchLayout() },
        r.pick(Prefs.THEME, tr("Theme"), "style dark light night mode"),
        r.link(tr("Accent colour"), tr(accentChoice.label), "color colour accent wallpaper") { r.a.accent() },
        r.pick(Prefs.COLOR_SET, tr("Colours"), "income expense color colour blue green red"),
        r.link(tr("Language"), language, "translate translation hindi") { r.a.language() },
        r.pick(Prefs.WIDGET_THEME, tr("Widget style"), "home screen widget dark light"),
        r.pick(Prefs.WIDGET_OPACITY, tr("Widget background"), "home screen widget transparent opacity"),
    ))
}

@Composable
private fun enteringGroup(r: RowMaker): SettingGroup {
    val habitAccount by rememberSetting(Prefs.HABIT_ACCOUNT)
    val autocomplete by rememberSetting(Prefs.AUTOCOMPLETE)
    val location by rememberSetting(Prefs.LOCATION)
    return SettingGroup("entering", tr("Entering"),
        listOf(r.label(Prefs.INPUT_ORDER), if (autocomplete) tr("suggestions on") else tr("suggestions off")).joinToString(" · "),
        listOf(
            r.pick(Prefs.INPUT_ORDER, tr("Input order"), "keypad form"),
            r.pick(Prefs.TIME_INPUT, tr("Time"), "clock hour time input"),
            r.toggle(tr("Remember usual accounts"), habitAccount,
                tr("Pre-select the account you usually use; a category's preferred account comes first"), "default account preselect habit") {
                r.setFlag(Prefs.HABIT_ACCOUNT, it)
            },
            r.toggle(tr("Suggest past notes"), autocomplete, tr("Picks their category and account too"), "autocomplete") {
                r.setFlag(Prefs.AUTOCOMPLETE, it)
            },
            r.link(tr("Reset suggestions"), null, "autocomplete clear forget notes") { r.a.resetSuggestions() },
            r.toggle(tr("Save location"), location, tr("Attach your current location to new entries"), "gps map place") {
                if (it) r.a.locationOn() else r.setFlag(Prefs.LOCATION, false)
            },
            r.pick(Prefs.REPEAT_TIMING, tr("Repeat entries are added"), "recurring timing"),
        ))
}

@Composable
private fun listsGroup(r: RowMaker, newLayout: Boolean): SettingGroup {
    val showDescription by rememberSetting(Prefs.SHOW_DESCRIPTION)
    val noteButton by rememberSetting(Prefs.NOTE_BUTTON)
    return SettingGroup("lists", tr("Lists"), tr("Opens on {0} · swipe: {1}", r.label(Prefs.START_SCREEN), r.label(Prefs.SWIPE)), listOfNotNull(
        r.pick(Prefs.START_SCREEN, tr("Start screen"), "start screen open view list calendar months notes"),
        r.pick(Prefs.SWIPE, tr("Swipe left / right"), "gesture tab month"),
        r.pick(Prefs.TIME_SORT, tr("Order within a day"), "sort"),
        r.toggle(tr("Show description"), showDescription, tr("Description under the note in lists"), "details memo") { r.setFlag(Prefs.SHOW_DESCRIPTION, it) },
        if (!newLayout) r.toggle(tr("Note button"), noteButton, tr("Add a note from the Daily view, next to +"), "memo") {
            r.setFlag(Prefs.NOTE_BUTTON, it)
        } else null,
    ))
}

@Composable
private fun remindersGroup(r: RowMaker, model: SettingsModel): SettingGroup {
    val reminder by rememberSetting(Prefs.REMINDER)
    val reminderTime by rememberSetting(Prefs.REMINDER_TIME)
    val quickAdd by rememberSetting(Prefs.QUICK_ADD_NOTIFICATION)
    return SettingGroup("reminders", tr("Reminders"),
        listOf(if (reminder) tr("Reminder at {0}", reminderTime.toString()) else tr("Reminder off"),
            if (quickAdd) tr("quick add on") else tr("quick add off")).joinToString(" · "),
        listOfNotNull(
            r.toggle(tr("Daily reminder"), reminder, tr("A nudge to record the day's spending"), "notification alert") { turnOn ->
                model.setReminder(turnOn, reminderTime)
            },
            if (reminder) r.link(tr("Reminder time"), reminderTime.toString(), "notification clock hour") { r.a.edit(Prefs.REMINDER_TIME) } else null,
            r.toggle(tr("Quick add in notification bar"), quickAdd, tr("Expense / Income / Transfer buttons"), "notification shortcut") { turnOn ->
                model.setQuickAdd(turnOn)
            },
        ))
}

/** The current value's label, read live. */
@Composable
private fun <T> currentLabel(spec: ChoiceSpec<T>): String = spec.label(rememberSetting(spec.setting).value)

private fun weekendShort(shift: Periods.WeekendShift) = when (shift) {
    Periods.WeekendShift.NONE -> tr("Keep")
    Periods.WeekendShift.BEFORE -> tr("Friday before")
    Periods.WeekendShift.AFTER -> tr("Monday after")
}
