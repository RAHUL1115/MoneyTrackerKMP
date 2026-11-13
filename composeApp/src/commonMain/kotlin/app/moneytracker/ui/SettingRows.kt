package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import app.moneytracker.data.ColorSet
import app.moneytracker.data.InputOrder
import app.moneytracker.data.Prefs
import app.moneytracker.data.RepeatTiming
import app.moneytracker.data.Setting
import app.moneytracker.data.StartScreen
import app.moneytracker.data.Swipe
import app.moneytracker.data.ThemeMode
import app.moneytracker.data.TimeInput
import app.moneytracker.data.TimeSort
import app.moneytracker.domain.Periods
import kotlinx.datetime.DayOfWeek

/*
 * One settings structure for both layouts: the More places, the three combined screens and the six
 * preference groups are defined here once, and each layout only draws them in its own style.
 */

/** One setting: a link or a choice, or (when [checked] is set) an on/off switch. [words] only help search. */
class SettingRow(
    val title: String,
    val value: String? = null,
    val subtitle: String? = null,
    val words: String = "",
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/** A preference group: its rows open on their own page; [summary] shows what is set now. */
class SettingGroup(val id: String, val title: String, val summary: String, val rows: List<SettingRow>)

/** A tile on More (classic) or at the top of Settings (new layout); [status] is its current state. */
class MorePlace(val id: String, val title: String, val status: String, val open: () -> Unit)

/** A screen that gathers related places: Categories & tags, Repeat & bookmarks, Bank SMS, Backup & security, Help & about. */
class SettingHub(val id: String, val title: String, val intro: String, val rows: List<SettingRow>)

/** What a row needs from the layout: its own dialogs or sheets. */
class SettingActions(
    val choice: (ChoiceSpec<*>) -> Unit,
    /** Text values: currency symbol, decimals, month start day, reminder time. */
    val edit: (Setting<*>) -> Unit,
    val accent: () -> Unit,
    val language: () -> Unit,
    /** Opens the layout choice: Classic or New. */
    val switchLayout: () -> Unit,
    /** Turning on Save location shows the disclosure first. */
    val locationOn: () -> Unit,
    val resetSuggestions: () -> Unit,
)

/** A multiple-choice setting as both layouts show it: a title and each option's label. */
class ChoiceSpec<T>(val setting: Setting<T>, val title: String, val options: List<Pair<T, String>>) {
    fun label(value: T): String = (options.firstOrNull { it.first == value } ?: options.first()).second
}

/** The one list of multiple-choice settings, their titles and wording. Colours are named as each layout draws them. */
fun choiceSpecs(newLayout: Boolean): List<ChoiceSpec<*>> {
    fun day(d: DayOfWeek) = tr(d.name.lowercase().replaceFirstChar { it.uppercase() })
    val (income, expense) = if (newLayout) tr("Income blue, expense red") to tr("Income red, expense blue")
        else tr("Income green, expense red") to tr("Income red, expense green")
    return listOf(
        ChoiceSpec(Prefs.SWIPE, tr("Swipe left / right"), listOf(Swipe.DATE to tr("Change month"), Swipe.TAB to tr("Change tab"), Swipe.NONE to tr("Off"))),
        ChoiceSpec(Prefs.COLOR_SET, tr("Income / expense colours"), listOf(ColorSet.NORMAL to income, ColorSet.SWAPPED to expense)),
        ChoiceSpec(Prefs.TIME_INPUT, tr("Time input"),
            listOf(TimeInput.NONE to tr("Off"), TimeInput.INPUT to tr("Record time"), TimeInput.LIST to tr("Record and show in lists"))),
        ChoiceSpec(Prefs.TIME_SORT, tr("Order within a day"), listOf(TimeSort.DESC to tr("Latest first"), TimeSort.ASC to tr("Earliest first"))),
        ChoiceSpec(Prefs.INPUT_ORDER, tr("Input order"), listOf(InputOrder.AMOUNT to tr("Amount first"), InputOrder.ACCOUNT to tr("Account first"))),
        ChoiceSpec(Prefs.THEME, tr("Theme"), themeOptions()),
        ChoiceSpec(Prefs.REPEAT_TIMING, tr("Repeat entries are added"),
            listOf(RepeatTiming.ON_DATE to tr("On the date"), RepeatTiming.MONTH_START to tr("On the first day of the month"))),
        ChoiceSpec(Prefs.WIDGET_THEME, tr("Widget style"), themeOptions()),
        ChoiceSpec(Prefs.WIDGET_OPACITY, tr("Widget background"), listOf(100, 80, 60, 40, 20).map { it to tr("{0}% opaque", it.toString()) }),
        ChoiceSpec(Prefs.WEEKEND_SHIFT, tr("Start date on a weekend"), listOf(Periods.WeekendShift.NONE to tr("Keep the date"),
            Periods.WeekendShift.BEFORE to tr("Move to the Friday before"), Periods.WeekendShift.AFTER to tr("Move to the Monday after"))),
        ChoiceSpec(Prefs.WEEK_START, tr("Week starts on"), listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY).map { it to day(it) }),
        ChoiceSpec(Prefs.START_SCREEN, tr("Start screen"),
            if (newLayout) listOf(StartScreen.DAILY to tr("List (Daily)"), StartScreen.CALENDAR to tr("Calendar"),
                StartScreen.MONTHLY to tr("Months (Monthly)"), StartScreen.TOTAL to tr("Months (Total)"), StartScreen.NOTE to tr("Notes"))
            else StartScreen.entries.map { it to tr(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }),
    )
}

private fun themeOptions() = listOf(ThemeMode.SYSTEM to tr("Follow system"), ThemeMode.LIGHT to tr("Light"), ThemeMode.DARK to tr("Dark"))

@Suppress("UNCHECKED_CAST")
fun <T> choiceSpec(s: Setting<T>, newLayout: Boolean): ChoiceSpec<T> = choiceSpecs(newLayout).first { it.setting == s } as ChoiceSpec<T>

/**
 * The sheet or dialog for a multiple-choice setting: [onPick] gets the option picked with its label and the value
 * before, so a layout can offer Undo. Each layout draws the options its own way through [content]. Saves go through
 * [model], which runs them in the screen's scope, since the sheet is gone by the time they (or an Undo) run.
 */
@Composable
fun <T> ChoiceHost(
    spec: ChoiceSpec<T>,
    model: SettingsModel,
    content: @Composable (current: T, options: List<Pair<T, String>>, pick: (T, String) -> Unit) -> Unit,
    onPick: (value: T, label: String, before: T) -> Unit = { _, _, _ -> },
) {
    val current by rememberSetting(spec.setting)
    content(current, spec.options) { value, label ->
        if (value != current) {
            model.save(spec.setting, value)
            onPick(value, label, current)
        }
    }
}

/** Rows matching every word of [query], with where they live. */
fun searchSettings(groups: List<SettingGroup>, hubs: List<SettingHub>, query: String): List<Pair<String, SettingRow>> =
    searchRows(groups.flatMap { g -> g.rows.map { g.title to it } } + hubs.flatMap { h -> h.rows.map { h.title to it } }, query)

/** The [rows] (each with where it lives) whose words include every word of [query]. */
fun searchRows(rows: List<Pair<String, SettingRow>>, query: String): List<Pair<String, SettingRow>> {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty()) return emptyList()
    return rows.filter { (where, r) ->
        val text = listOfNotNull(r.title, r.value, r.subtitle, where, r.words).joinToString(" ").lowercase()
        terms.all { it in text }
    }
}

/** The one dialog or sheet a settings screen has open, as each layout draws it. */
sealed interface SettingDialog {
    class Choice(val spec: ChoiceSpec<*>) : SettingDialog
    class Edit(val setting: Setting<*>) : SettingDialog
    data object Accent : SettingDialog
    data object Language : SettingDialog
    /** Classic or New, before the restart is confirmed. */
    data object LayoutChoice : SettingDialog
    data object LayoutSwitch : SettingDialog
    /** The disclosure shown before Save location turns on. */
    data object Location : SettingDialog
    data object ResetSuggestions : SettingDialog
}

/** Row actions that each [open] the matching [SettingDialog]. */
fun settingActions(open: (SettingDialog) -> Unit) = SettingActions(
    choice = { open(SettingDialog.Choice(it)) },
    edit = { open(SettingDialog.Edit(it)) },
    accent = { open(SettingDialog.Accent) },
    language = { open(SettingDialog.Language) },
    switchLayout = { open(SettingDialog.LayoutChoice) },
    locationOn = { open(SettingDialog.Location) },
    resetSuggestions = { open(SettingDialog.ResetSuggestions) },
)

/** While a preference group is [open], Back closes it before leaving the screen. */
@Composable
fun BackClosesGroup(open: Boolean, close: () -> Unit) {
    val nav = LocalNavigator.current
    DisposableEffect(open) {
        val remove = if (open) nav.interceptBack { close(); true } else ({})
        onDispose { remove() }
    }
}
