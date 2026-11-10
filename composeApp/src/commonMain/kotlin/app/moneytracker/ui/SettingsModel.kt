package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.moneytracker.data.MoneyRepository.Keys
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

/** What a row needs from the layout: its own dialogs or sheets, and its wording for choices. */
class SettingActions(
    val choice: (key: String) -> Unit,
    /** Text values: currency symbol, decimals, month start day, reminder time. */
    val edit: (key: String) -> Unit,
    val accent: () -> Unit,
    val language: () -> Unit,
    /** Opens the layout choice: Classic or New. */
    val switchLayout: () -> Unit,
    /** Turning on Save location shows the disclosure first. */
    val locationOn: () -> Unit,
    val resetSuggestions: () -> Unit,
    val choiceLabel: (key: String, value: String?) -> String,
)

private val SETTING_KEYS = listOf(
    Keys.LANGUAGE, Keys.CARRY_OVER, Keys.CARD_AT_PAYMENT, Keys.REMINDER, Keys.QUICK_ADD_NOTIFICATION, Keys.CAPTURE_ENABLED, Keys.LOCATION,
    Keys.USE_SUBCATEGORIES, Keys.PASSCODE, Keys.REMINDER_TIME, Keys.START_SCREEN, Keys.SWIPE, Keys.COLOR_SET, Keys.TIME_INPUT, Keys.TIME_SORT,
    Keys.INPUT_ORDER, Keys.THEME, Keys.WIDGET_THEME, Keys.WIDGET_OPACITY, Keys.REPEAT_TIMING, Keys.AUTOCOMPLETE, Keys.HABIT_ACCOUNT,
    Keys.SHOW_DESCRIPTION, Keys.NOTE_BUTTON, Keys.SYMBOL_AFTER,
)

@Composable
private fun settingValues(): Map<String, String?> {
    val repo = LocalRepo.current
    return SETTING_KEYS.associateWith { k -> remember(repo, k) { repo.setting(k) }.collectAsState(null).value }
}

/** Money, Calendar, Look, Entering, Lists, Reminders. */
@Composable
fun rememberSettingGroups(newLayout: Boolean, a: SettingActions): List<SettingGroup> {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val v = settingValues()
    val startDay by repo.monthStartDay.collectAsState(1)
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val currencies by repo.currencies.collectAsState(emptyList())

    fun on(key: String) = v[key] == "1"
    // Widgets show colours, currency format and their own style; repaint them after any change.
    fun put(key: String, value: String) = scope.launch(repo.io) { repo.putSetting(key, value) }
    fun setFlag(key: String, on: Boolean) = put(key, if (on) "1" else "0")
    fun label(key: String) = a.choiceLabel(key, v[key])
    fun link(title: String, value: String? = null, words: String = "", subtitle: String? = null, onClick: () -> Unit) =
        SettingRow(title, value, subtitle, words, onClick = onClick)
    fun toggle(title: String, checked: Boolean, subtitle: String? = null, words: String = "", onChange: (Boolean) -> Unit) =
        SettingRow(title, null, subtitle, words, checked) { onChange(!checked) }
    fun pick(key: String, title: String, words: String = "") = SettingRow(title, label(key), null, words) { a.choice(key) }

    val weekend = when (ps.shift) {
        Periods.WeekendShift.NONE -> tr("Keep")
        Periods.WeekendShift.BEFORE -> tr("Friday before")
        Periods.WeekendShift.AFTER -> tr("Monday after")
    }
    val week = tr(ps.weekStart.name.lowercase().replaceFirstChar { it.uppercase() })
    val language = I18n.LANGUAGES.firstOrNull { it.first == v[Keys.LANGUAGE].orEmpty() }?.takeIf { it.first.isNotEmpty() }?.second ?: tr("Phone language")
    val others = currencies.joinToString(", ") { it.code }
    val reminder = on(Keys.REMINDER)
    val reminderTime = v[Keys.REMINDER_TIME] ?: "21:00"

    return listOf(
        SettingGroup("money", tr("Money"),
            listOfNotNull(currency.symbol, tr("{0} decimals", currency.decimals), others.takeIf { it.isNotEmpty() }?.let { tr("also {0}", it) }).joinToString(" · "),
            listOf(
                link(tr("Currency symbol"), currency.symbol, "rupee money") { a.edit(Keys.CURRENCY_SYMBOL) },
                toggle(tr("Symbol after amount"), on(Keys.SYMBOL_AFTER),
                    if (on(Keys.SYMBOL_AFTER)) "1,000.00 ${currency.symbol}" else "${currency.symbol} 1,000.00", "currency") { setFlag(Keys.SYMBOL_AFTER, it) },
                link(tr("Decimal places"), currency.decimals.toString(), "paise cents rounding",
                    tr("Set before entering data; existing amounts are not converted.")) { a.edit(Keys.CURRENCY_DECIMALS) },
                link(tr("Other currencies"), others.ifEmpty { null }, "sub currency dollar exchange rate foreign") { nav.push(Screen.Currencies) },
                toggle(tr("Carry-over"), on(Keys.CARRY_OVER), tr("Show the balance carried from earlier months"), "previous month balance") { setFlag(Keys.CARRY_OVER, it) },
                toggle(tr("Card expenses at payment"), on(Keys.CARD_AT_PAYMENT), tr("Count credit-card purchases when the bill is paid, not when you buy"), "credit bill") {
                    setFlag(Keys.CARD_AT_PAYMENT, it)
                },
            )),
        SettingGroup("calendar", tr("Calendar"), tr("Month from day {0} · week from {1}", startDay, week), listOf(
            link(tr("Month starts on"), tr("Every {0}", startDay), "monthly start date salary period cycle") { a.edit(Keys.MONTH_START_DAY) },
            link(tr("If that day is a weekend"), weekend, "friday monday shift") { a.choice(Keys.WEEKEND_SHIFT) },
            link(tr("Week starts on"), week, "weekly start day") { a.choice(Keys.WEEK_START) },
        )),
        SettingGroup("look", tr("Look"), listOf(label(Keys.THEME), tr(accentChoice.label), language).joinToString(" · "), listOf(
            link(tr("Layout"), if (newLayout) tr("New") else tr("Classic"), "layout classic new design restart",
                tr("Classic or New")) { a.switchLayout() },
            pick(Keys.THEME, tr("Theme"), "style dark light night mode"),
            link(tr("Accent colour"), tr(accentChoice.label), "color colour accent wallpaper") { a.accent() },
            pick(Keys.COLOR_SET, tr("Colours"), "income expense color colour blue green red"),
            link(tr("Language"), language, "translate translation hindi") { a.language() },
            pick(Keys.WIDGET_THEME, tr("Widget style"), "home screen widget dark light"),
            pick(Keys.WIDGET_OPACITY, tr("Widget background"), "home screen widget transparent opacity"),
        )),
        SettingGroup("entering", tr("Entering"),
            listOf(label(Keys.INPUT_ORDER), if (v[Keys.AUTOCOMPLETE] != "0") tr("suggestions on") else tr("suggestions off")).joinToString(" · "),
            listOf(
                pick(Keys.INPUT_ORDER, tr("Input order"), "keypad form"),
                pick(Keys.TIME_INPUT, tr("Time"), "clock hour time input"),
                toggle(tr("Remember usual accounts"), v[Keys.HABIT_ACCOUNT] != "0",
                    tr("Pre-select the account you usually use; a category's preferred account comes first"), "default account preselect habit") {
                    setFlag(Keys.HABIT_ACCOUNT, it)
                },
                toggle(tr("Suggest past notes"), v[Keys.AUTOCOMPLETE] != "0", tr("Picks their category and account too"), "autocomplete") {
                    setFlag(Keys.AUTOCOMPLETE, it)
                },
                link(tr("Reset suggestions"), null, "autocomplete clear forget notes") { a.resetSuggestions() },
                toggle(tr("Save location"), on(Keys.LOCATION), tr("Attach your current location to new entries"), "gps map place") {
                    if (it) a.locationOn() else setFlag(Keys.LOCATION, false)
                },
                pick(Keys.REPEAT_TIMING, tr("Repeat entries are added"), "recurring timing"),
            )),
        SettingGroup("lists", tr("Lists"), tr("Opens on {0} · swipe: {1}", label(Keys.START_SCREEN), label(Keys.SWIPE)), listOfNotNull(
            pick(Keys.START_SCREEN, tr("Start screen"), "start screen open view list calendar months notes"),
            pick(Keys.SWIPE, tr("Swipe left / right"), "gesture tab month"),
            pick(Keys.TIME_SORT, tr("Order within a day"), "sort"),
            toggle(tr("Show description"), on(Keys.SHOW_DESCRIPTION), tr("Description under the note in lists"), "details memo") { setFlag(Keys.SHOW_DESCRIPTION, it) },
            if (!newLayout) toggle(tr("Note button"), v[Keys.NOTE_BUTTON] != "0", tr("Add a note from the Daily view, next to +"), "memo") {
                setFlag(Keys.NOTE_BUTTON, it)
            } else null,
        )),
        SettingGroup("reminders", tr("Reminders"),
            listOf(if (reminder) tr("Reminder at {0}", reminderTime) else tr("Reminder off"),
                if (on(Keys.QUICK_ADD_NOTIFICATION)) tr("quick add on") else tr("quick add off")).joinToString(" · "),
            listOfNotNull(
                toggle(tr("Daily reminder"), reminder, tr("A nudge to record the day's spending"), "notification alert") { turnOn ->
                    val (h, m) = reminderTime.split(":").map { it.toIntOrNull() ?: 0 }
                    platform.setReminder(turnOn, h, m) { active -> setFlag(Keys.REMINDER, active) }
                },
                if (reminder) link(tr("Reminder time"), reminderTime, "notification clock hour") { a.edit(Keys.REMINDER_TIME) } else null,
                toggle(tr("Quick add in notification bar"), on(Keys.QUICK_ADD_NOTIFICATION), tr("Expense / Income / Transfer buttons"), "notification shortcut") { turnOn ->
                    platform.setQuickAddNotification(turnOn) { active -> setFlag(Keys.QUICK_ADD_NOTIFICATION, active) }
                },
            )),
    )
}

/** The screens that gather related places. */
@Composable
fun rememberSettingHubs(): List<SettingHub> {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val v = settingValues()
    val categories by repo.categories.collectAsState(emptyList())
    val tags by repo.tags.collectAsState(emptyList())
    val rules by repo.rules.collectAsState(emptyList())
    val templates by repo.templates.collectAsState(emptyList())
    val snapshots by LocalBackup.current.localBackups.collectAsState()
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val mains = { k: CategoryKind -> categories.count { it.kind == k && it.parentId == null } }
    val subs = v[Keys.USE_SUBCATEGORIES] == "1"
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
                scope.launch { withContext(repo.io) { repo.putSetting(Keys.USE_SUBCATEGORIES, if (subs) "0" else "1") } }
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
            SettingRow(tr("Bank SMS settings"), if (v[Keys.CAPTURE_ENABLED] == "1") tr("On") else tr("Off"),
                tr("Detection, blocked senders and words, import from the inbox"), "message text detect auto block filter import") {
                nav.push(Screen.MessageSettings)
            },
        )),
        SettingHub("safety", tr("Backup & security"), tr("Keep your data safe and private."), listOf(
            SettingRow(tr("Backup & restore"), null, last?.let { tr("Last backup: {0}", it) } ?: tr("Not backed up yet"),
                "backup restore export import csv excel mmbak snapshot reset") { nav.push(Screen.Backup) },
            SettingRow(tr("Passcode"), if (v[Keys.PASSCODE].isNullOrBlank()) tr("Off") else tr("On"), tr("Lock with a PIN, fingerprint or face"),
                "pin lock password fingerprint face biometric security") { nav.push(Screen.Security) },
        )),
        SettingHub("help", tr("Help & about"), tr("How things work, and who made this."), listOf(
            SettingRow(tr("Help"), null, null, "guide how faq") { nav.push(Screen.Help) },
            SettingRow(tr("About MoneyTracker"), platform.appVersion.ifEmpty { null }, null, "version privacy source github feedback") { nav.push(Screen.About) },
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
    val v = settingValues()
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
    val passcode = !v[Keys.PASSCODE].isNullOrBlank()
    return listOf(
        MorePlace("labels", tr("Categories & tags"),
            tr("{0} expense · {1} income · {2} tags", mains(CategoryKind.EXPENSE), mains(CategoryKind.INCOME), tags.size)) { nav.push(Screen.MoreHub("labels")) },
        MorePlace("budget", tr("Budget"), if (budgeted == 0) tr("Not set") else tr("Set for {0} categories", budgeted)) { nav.push(Screen.Budget) },
        MorePlace("reuse", tr("Repeat & bookmarks"), tr("{0} repeats · {1} bookmarks", rules.size, templates.size)) { nav.push(Screen.MoreHub("reuse")) },
        MorePlace("sms", tr("Bank SMS"),
            if (v[Keys.CAPTURE_ENABLED] != "1") tr("Off") else if (pending.isEmpty()) tr("On") else tr("On · {0} to review", pending.size)) {
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
