package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.moneytracker.data.ClockTime
import app.moneytracker.data.ColorSet
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.FlagSetting
import app.moneytracker.data.InputOrder
import app.moneytracker.data.Prefs
import app.moneytracker.data.RepeatTiming
import app.moneytracker.data.Setting
import app.moneytracker.data.StartScreen
import app.moneytracker.data.Swipe
import app.moneytracker.data.ThemeMode
import app.moneytracker.data.TimeInput
import app.moneytracker.data.TimeSort
import kotlinx.datetime.DayOfWeek
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Periods
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Money, Calendar, Look, Entering, Lists, Reminders. */
@Composable
fun rememberSettingGroups(newLayout: Boolean, a: SettingActions): List<SettingGroup> {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val backgroundJobs = LocalPlatform.current.backgroundJobs
    val scope = rememberCoroutineScope()
    val currencies by repo.currencies.collectAsState(emptyList())
    val specs = choiceSpecs(newLayout)

    fun setFlag(s: FlagSetting, on: Boolean) = scope.saveSetting(repo, s, on)
    fun link(title: String, value: String? = null, words: String = "", subtitle: String? = null, onClick: () -> Unit) =
        SettingRow(title, value, subtitle, words, onClick = onClick)
    fun toggle(title: String, checked: Boolean, subtitle: String? = null, words: String = "", onChange: (Boolean) -> Unit) =
        SettingRow(title, null, subtitle, words, checked) { onChange(!checked) }
    // Each choice's current label, read live.
    val labels: Map<Setting<*>, String> = specs.associate { spec -> spec.setting to currentLabel(spec) }
    fun label(s: Setting<*>) = labels.getValue(s)
    fun pick(s: Setting<*>, title: String, words: String = "") = SettingRow(title, label(s), null, words) { a.choice(specs.first { it.setting == s }) }

    val startDay by rememberSetting(Prefs.MONTH_START_DAY)
    val week = label(Prefs.WEEK_START)
    val language = I18n.LANGUAGES.firstOrNull { it.first == rememberSetting(Prefs.LANGUAGE).value }?.takeIf { it.first.isNotEmpty() }?.second ?: tr("Phone language")
    val others = currencies.joinToString(", ") { it.code }
    val reminder by rememberSetting(Prefs.REMINDER)
    val reminderTime by rememberSetting(Prefs.REMINDER_TIME)
    val symbolAfter by rememberSetting(Prefs.SYMBOL_AFTER)
    val carryOver by rememberSetting(Prefs.CARRY_OVER)
    val cardAtPayment by rememberSetting(Prefs.CARD_AT_PAYMENT)
    val habitAccount by rememberSetting(Prefs.HABIT_ACCOUNT)
    val autocomplete by rememberSetting(Prefs.AUTOCOMPLETE)
    val location by rememberSetting(Prefs.LOCATION)
    val showDescription by rememberSetting(Prefs.SHOW_DESCRIPTION)
    val noteButton by rememberSetting(Prefs.NOTE_BUTTON)
    val quickAdd by rememberSetting(Prefs.QUICK_ADD_NOTIFICATION)

    return listOf(
        SettingGroup("money", tr("Money"),
            listOfNotNull(currency.symbol, tr("{0} decimals", currency.decimals), others.takeIf { it.isNotEmpty() }?.let { tr("also {0}", it) }).joinToString(" · "),
            listOf(
                link(tr("Currency symbol"), currency.symbol, "rupee money") { a.edit(Prefs.CURRENCY_SYMBOL) },
                toggle(tr("Symbol after amount"), symbolAfter,
                    if (symbolAfter) "1,000.00 ${currency.symbol}" else "${currency.symbol} 1,000.00", "currency") { setFlag(Prefs.SYMBOL_AFTER, it) },
                link(tr("Decimal places"), currency.decimals.toString(), "paise cents rounding",
                    tr("Set before entering data; existing amounts are not converted.")) { a.edit(Prefs.CURRENCY_DECIMALS) },
                link(tr("Other currencies"), others.ifEmpty { null }, "sub currency dollar exchange rate foreign") { nav.push(Screen.Currencies) },
                toggle(tr("Carry-over"), carryOver, tr("Show the balance carried from earlier months"), "previous month balance") { setFlag(Prefs.CARRY_OVER, it) },
                toggle(tr("Card expenses at payment"), cardAtPayment, tr("Count credit-card purchases when the bill is paid, not when you buy"), "credit bill") {
                    setFlag(Prefs.CARD_AT_PAYMENT, it)
                },
            )),
        SettingGroup("calendar", tr("Calendar"), tr("Month from day {0} · week from {1}", startDay, week), listOf(
            link(tr("Month starts on"), tr("Every {0}", startDay), "monthly start date salary period cycle") { a.edit(Prefs.MONTH_START_DAY) },
            link(tr("If that day is a weekend"), weekendShort(rememberSetting(Prefs.WEEKEND_SHIFT).value), "friday monday shift") {
                a.choice(specs.first { it.setting == Prefs.WEEKEND_SHIFT })
            },
            pick(Prefs.WEEK_START, tr("Week starts on"), "weekly start day"),
        )),
        SettingGroup("look", tr("Look"), listOf(label(Prefs.THEME), tr(accentChoice.label), language).joinToString(" · "), listOf(
            link(tr("Layout"), if (newLayout) tr("New") else tr("Classic"), "layout classic new design restart",
                tr("Classic or New")) { a.switchLayout() },
            pick(Prefs.THEME, tr("Theme"), "style dark light night mode"),
            link(tr("Accent colour"), tr(accentChoice.label), "color colour accent wallpaper") { a.accent() },
            pick(Prefs.COLOR_SET, tr("Colours"), "income expense color colour blue green red"),
            link(tr("Language"), language, "translate translation hindi") { a.language() },
            pick(Prefs.WIDGET_THEME, tr("Widget style"), "home screen widget dark light"),
            pick(Prefs.WIDGET_OPACITY, tr("Widget background"), "home screen widget transparent opacity"),
        )),
        SettingGroup("entering", tr("Entering"),
            listOf(label(Prefs.INPUT_ORDER), if (autocomplete) tr("suggestions on") else tr("suggestions off")).joinToString(" · "),
            listOf(
                pick(Prefs.INPUT_ORDER, tr("Input order"), "keypad form"),
                pick(Prefs.TIME_INPUT, tr("Time"), "clock hour time input"),
                toggle(tr("Remember usual accounts"), habitAccount,
                    tr("Pre-select the account you usually use; a category's preferred account comes first"), "default account preselect habit") {
                    setFlag(Prefs.HABIT_ACCOUNT, it)
                },
                toggle(tr("Suggest past notes"), autocomplete, tr("Picks their category and account too"), "autocomplete") {
                    setFlag(Prefs.AUTOCOMPLETE, it)
                },
                link(tr("Reset suggestions"), null, "autocomplete clear forget notes") { a.resetSuggestions() },
                toggle(tr("Save location"), location, tr("Attach your current location to new entries"), "gps map place") {
                    if (it) a.locationOn() else setFlag(Prefs.LOCATION, false)
                },
                pick(Prefs.REPEAT_TIMING, tr("Repeat entries are added"), "recurring timing"),
            )),
        SettingGroup("lists", tr("Lists"), tr("Opens on {0} · swipe: {1}", label(Prefs.START_SCREEN), label(Prefs.SWIPE)), listOfNotNull(
            pick(Prefs.START_SCREEN, tr("Start screen"), "start screen open view list calendar months notes"),
            pick(Prefs.SWIPE, tr("Swipe left / right"), "gesture tab month"),
            pick(Prefs.TIME_SORT, tr("Order within a day"), "sort"),
            toggle(tr("Show description"), showDescription, tr("Description under the note in lists"), "details memo") { setFlag(Prefs.SHOW_DESCRIPTION, it) },
            if (!newLayout) toggle(tr("Note button"), noteButton, tr("Add a note from the Daily view, next to +"), "memo") {
                setFlag(Prefs.NOTE_BUTTON, it)
            } else null,
        )),
        SettingGroup("reminders", tr("Reminders"),
            listOf(if (reminder) tr("Reminder at {0}", reminderTime.toString()) else tr("Reminder off"),
                if (quickAdd) tr("quick add on") else tr("quick add off")).joinToString(" · "),
            listOfNotNull(
                toggle(tr("Daily reminder"), reminder, tr("A nudge to record the day's spending"), "notification alert") { turnOn ->
                    backgroundJobs.setReminder(turnOn, reminderTime.hour, reminderTime.minute) { active -> setFlag(Prefs.REMINDER, active) }
                },
                if (reminder) link(tr("Reminder time"), reminderTime.toString(), "notification clock hour") { a.edit(Prefs.REMINDER_TIME) } else null,
                toggle(tr("Quick add in notification bar"), quickAdd, tr("Expense / Income / Transfer buttons"), "notification shortcut") { turnOn ->
                    backgroundJobs.setQuickAddNotification(turnOn) { active -> setFlag(Prefs.QUICK_ADD_NOTIFICATION, active) }
                },
            )),
    )
}

/** One of the text settings (currency symbol, decimal places, month start day, reminder time): its title, starting text, check and save. */
class TextEdit(
    val title: String,
    val initial: String,
    /** Number keyboard. */
    val number: Boolean,
    private val check: (String) -> Boolean,
    private val store: (MoneyRepository, String) -> Unit,
) {
    fun valid(text: String) = check(text.trim())
    /** Blocking; call off the main thread. */
    fun save(repo: MoneyRepository, text: String) = store(repo, text.trim())
}

@Composable
fun rememberTextEdit(s: Setting<*>): TextEdit {
    val repo = LocalRepo.current
    return remember(repo, s) {
        when (s) {
            Prefs.MONTH_START_DAY -> TextEdit(tr("Monthly start date (1–31)"), repo.settingNow(Prefs.MONTH_START_DAY).toString(), true,
                { it.toIntOrNull() in 1..31 }) { r, t -> r.putSetting(Prefs.MONTH_START_DAY, t.toInt()) }
            Prefs.CURRENCY_DECIMALS -> TextEdit(tr("Decimal places (0–3)"), repo.settingNow(Prefs.CURRENCY_DECIMALS).toString(), true,
                { it.toIntOrNull() in 0..3 }) { r, t -> r.putSetting(Prefs.CURRENCY_DECIMALS, t.toInt()) }
            Prefs.CURRENCY_SYMBOL -> TextEdit(tr("Currency symbol"), repo.settingNow(Prefs.CURRENCY_SYMBOL), false,
                { it.isNotEmpty() && it.length <= 4 }) { r, t -> r.putSetting(Prefs.CURRENCY_SYMBOL, t) }
            Prefs.REMINDER_TIME -> TextEdit(tr("Reminder time (24h, HH:mm)"), repo.settingNow(Prefs.REMINDER_TIME).toString(), false,
                { ClockTime.parse(it) != null }) { r, t -> ClockTime.parse(t)?.let { r.putSetting(Prefs.REMINDER_TIME, it) } }
            else -> error("$s is not a text setting")
        }
    }
}

/** The current value's label, read live. */
@Composable
private fun <T> currentLabel(spec: ChoiceSpec<T>): String = spec.label(rememberSetting(spec.setting).value)

private fun weekendShort(shift: Periods.WeekendShift) = when (shift) {
    Periods.WeekendShift.NONE -> tr("Keep")
    Periods.WeekendShift.BEFORE -> tr("Friday before")
    Periods.WeekendShift.AFTER -> tr("Monday after")
}

/**
 * The sheet or dialog for a multiple-choice setting: [onPick] gets the option picked with its label and the value
 * before, so a layout can offer Undo. Each layout draws the options its own way through [content]. Saves run in
 * [scope], the screen's, since the sheet is gone by the time they (or an Undo) run.
 */
@Composable
fun <T> ChoiceHost(
    spec: ChoiceSpec<T>,
    scope: kotlinx.coroutines.CoroutineScope,
    content: @Composable (current: T, options: List<Pair<T, String>>, pick: (T, String) -> Unit) -> Unit,
    onPick: (value: T, label: String, before: T) -> Unit = { _, _, _ -> },
) {
    val repo = LocalRepo.current
    val current by rememberSetting(spec.setting)
    content(current, spec.options) { value, label ->
        if (value != current) {
            scope.saveSetting(repo, spec.setting, value)
            onPick(value, label, current)
        }
    }
}

/** The screens that gather related places. */
@Composable
fun rememberSettingHubs(): List<SettingHub> {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val appInfo = LocalPlatform.current.appInfo
    val scope = rememberCoroutineScope()
    val categories by repo.categories.collectAsState(emptyList())
    val tags by repo.tags.collectAsState(emptyList())
    val rules by repo.rules.collectAsState(emptyList())
    val templates by repo.templates.collectAsState(emptyList())
    val snapshots by LocalBackup.current.localBackups.collectAsState()
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val mains = { k: CategoryKind -> categories.count { it.kind == k && it.parentId == null } }
    val subs by rememberSetting(Prefs.USE_SUBCATEGORIES)
    val capture by rememberSetting(Prefs.CAPTURE_ENABLED)
    val passcode = rememberSetting(Prefs.PASSCODE).value.isNotBlank()
    val last = snapshots.firstOrNull()?.let { app.moneytracker.ui.fresh.snapshotWhen(it.name, repo.today()) }
    return listOf(
        SettingHub("labels", tr("Categories & tags"), tr("Everything you label entries with."), listOf(
            SettingRow(tr("Expense categories"), mains(CategoryKind.EXPENSE).toString(), tr("Add, rename, reorder"), "category sub emoji") {
                nav.push(Screen.Categories(CategoryKind.EXPENSE))
            },
            SettingRow(tr("Income categories"), mains(CategoryKind.INCOME).toString(), tr("Salary, refunds and more"), "category salary") {
                nav.push(Screen.Categories(CategoryKind.INCOME))
            },
            SettingRow(tr("Sub-categories"), null, tr("Pick a sub-category (Food › Lunch) when adding an entry"), "category", subs) {
                scope.saveSetting(repo, Prefs.USE_SUBCATEGORIES, !subs)
            },
            SettingRow(tr("Tags"), tags.size.toString(), tr("Extra labels like “Goa trip”; search them with #"), "label hashtag") { nav.push(Screen.Tags) },
        )),
        SettingHub("reuse", tr("Repeat & bookmarks"), tr("Entries you save once and use again."), listOf(
            SettingRow(tr("Repeat entries"), rules.size.toString(), tr("Rent, SIPs, subscriptions: added on their date"), "recurring installment rent subscription") {
                nav.push(Screen.Recurring)
            },
            SettingRow(tr("Bookmarks"), templates.size.toString(), tr("Saved entries you add in one tap"), "quick add favourite star template") { nav.push(Screen.Templates) },
        )),
        SettingHub("sms", tr("Bank SMS"), tr("Payments read from your bank's text messages, on this phone only."), listOf(
            SettingRow(tr("Detected transactions"), if (pending.isEmpty()) null else pending.size.toString(),
                if (pending.isEmpty()) tr("Nothing to review") else tr("{0} to review", pending.size), "message inbox review bank detected paste") {
                nav.push(Screen.Inbox)
            },
            SettingRow(tr("Bank SMS settings"), if (capture) tr("On") else tr("Off"),
                tr("Detection, blocked senders and words, import from the inbox"), "message text detect auto block filter import") {
                nav.push(Screen.MessageSettings)
            },
        )),
        SettingHub("safety", tr("Backup & security"), tr("Keep your data safe and private."), listOf(
            SettingRow(tr("Backup & restore"), null, last?.let { tr("Last backup: {0}", it) } ?: tr("Not backed up yet"),
                "backup restore export import csv excel mmbak snapshot reset") { nav.push(Screen.Backup) },
            SettingRow(tr("Passcode"), if (passcode) tr("On") else tr("Off"), tr("Lock with a PIN, fingerprint or face"),
                "pin lock password fingerprint face biometric security") { nav.push(Screen.Security) },
        )),
        SettingHub("help", tr("Help & about"), tr("How things work, and who made this."), listOf(
            SettingRow(tr("Help"), null, null, "guide how faq") { nav.push(Screen.Help) },
            SettingRow(tr("About MoneyTracker"), appInfo.appVersion.ifEmpty { null }, null, "version privacy source github feedback") { nav.push(Screen.About) },
            SettingRow(tr("Open-source licences"), "GPL-3.0", null, "license licence legal libraries") { nav.push(Screen.License("GPL-3.0")) },
        )),
    )
}

/**
 * Places with their status. The last place is Settings on classic and Help & about on the new layout.
 */
@Composable
fun rememberMorePlaces(newLayout: Boolean): List<MorePlace> {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val capture by rememberSetting(Prefs.CAPTURE_ENABLED)
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val categories by repo.categories.collectAsState(emptyList())
    val tags by repo.tags.collectAsState(emptyList())
    val rules by repo.rules.collectAsState(emptyList())
    val templates by repo.templates.collectAsState(emptyList())
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val snapshots by LocalBackup.current.localBackups.collectAsState()
    val month = ps.labelFor(repo.today())
    val budgets by remember(repo, month) { repo.budgetEntries(month) }.collectAsState(emptyList())
    val budgeted = budgets.groupBy { it.categoryId }.count { (_, e) -> (e.maxByOrNull { it.period }?.amount ?: 0L) > 0L }
    val mains = { k: CategoryKind -> categories.count { it.kind == k && it.parentId == null } }
    val last = snapshots.firstOrNull()?.let { app.moneytracker.ui.fresh.snapshotWhen(it.name, repo.today()) }
    val passcode = rememberSetting(Prefs.PASSCODE).value.isNotBlank()
    return listOf(
        MorePlace("labels", tr("Categories & tags"),
            tr("{0} expense · {1} income · {2} tags", mains(CategoryKind.EXPENSE), mains(CategoryKind.INCOME), tags.size)) { nav.push(Screen.MoreHub("labels")) },
        MorePlace("budget", tr("Budget"), if (budgeted == 0) tr("Not set") else tr("Set for {0} categories", budgeted)) { nav.push(Screen.Budget) },
        MorePlace("reuse", tr("Repeat & bookmarks"), tr("{0} repeats · {1} bookmarks", rules.size, templates.size)) { nav.push(Screen.MoreHub("reuse")) },
        MorePlace("sms", tr("Bank SMS"),
            if (!capture) tr("Off") else if (pending.isEmpty()) tr("On") else tr("On · {0} to review", pending.size)) {
            nav.push(Screen.MoreHub("sms"))
        },
        MorePlace("safety", tr("Backup & security"),
            listOf(last?.let { tr("Backed up {0}", it) } ?: tr("No backup yet"), if (passcode) tr("passcode on") else tr("passcode off")).joinToString(" · ")) {
            nav.push(Screen.MoreHub("safety"))
        },
        MorePlace("web", tr("Web dashboard"), webDashboardStatus()) { nav.push(Screen.WebDashboard) },
        if (newLayout) MorePlace("help", tr("Help & about"), tr("Guide, version, licences")) { nav.push(Screen.MoreHub("help")) }
        else MorePlace("settings", tr("Settings"), tr("Money, calendar, look, entering")) { nav.push(Screen.Configuration) },
    )
}

/** Rows matching every word of [query], with where they live. */
fun searchSettings(groups: List<SettingGroup>, hubs: List<SettingHub>, query: String): List<Pair<String, SettingRow>> {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty()) return emptyList()
    val all = groups.flatMap { g -> g.rows.map { g.title to it } } + hubs.flatMap { h -> h.rows.map { h.title to it } }
    return all.filter { (where, r) ->
        val text = listOfNotNull(r.title, r.value, r.subtitle, where, r.words).joinToString(" ").lowercase()
        terms.all { it in text }
    }
}
