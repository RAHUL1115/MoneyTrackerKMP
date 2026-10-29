package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.domain.CategoryKind
import app.moneytracker.ui.AccentChoice
import app.moneytracker.ui.DisclosureDialog
import app.moneytracker.ui.I18n
import app.moneytracker.ui.LocalBackup
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.accentChoice
import app.moneytracker.ui.darkTheme
import app.moneytracker.ui.systemAccent
import app.moneytracker.ui.tones
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One setting: a link, a choice or (when [checked] is set) an on/off switch. [words] only help search. */
private class SRow(
    val title: String,
    val value: String? = null,
    val subtitle: String? = null,
    val words: String = "",
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

private class SSection(val id: String, val title: String, val sub: String, val rows: List<SRow>)

/** Multiple-choice settings: stored value → label. The first option is the default. */
private fun settingChoices(): Map<String, List<Pair<String, String>>> = mapOf(
    Keys.SWIPE to listOf("DATE" to tr("Change month"), "TAB" to tr("Change tab"), "NONE" to tr("Off")),
    Keys.COLOR_SET to listOf("A" to tr("Income green, expense red"), "B" to tr("Income red, expense green")),
    Keys.TIME_INPUT to listOf("NONE" to tr("Off"), "INPUT" to tr("Record time"), "LIST" to tr("Record and show in lists")),
    Keys.TIME_SORT to listOf("DESC" to tr("Latest first"), "ASC" to tr("Earliest first")),
    Keys.INPUT_ORDER to listOf("AMOUNT" to tr("Amount first"), "ACCOUNT" to tr("Account first")),
    Keys.THEME to listOf("SYSTEM" to tr("Follow system"), "LIGHT" to tr("Light"), "DARK" to tr("Dark")),
    Keys.REPEAT_TIMING to listOf("ON_DATE" to tr("On the date"), "MONTH_START" to tr("On the first day of the month")),
    Keys.WIDGET_THEME to listOf("SYSTEM" to tr("Follow system"), "LIGHT" to tr("Light"), "DARK" to tr("Dark")),
    Keys.WIDGET_OPACITY to listOf("100", "80", "60", "40", "20").map { it to tr("{0}% opaque", it) },
    Keys.WEEKEND_SHIFT to listOf("NONE" to tr("Keep the date"), "BEFORE" to tr("Move to the Friday before"), "AFTER" to tr("Move to the Monday after")),
    Keys.WEEK_START to listOf("SUNDAY", "MONDAY", "SATURDAY").map { it to tr(it.lowercase().replaceFirstChar { c -> c.uppercase() }) },
    Keys.START_SCREEN to listOf("DAILY" to tr("List (Daily)"), "CALENDAR" to tr("Calendar"), "MONTHLY" to tr("Months (Monthly)"), "TOTAL" to tr("Months (Total)"), "NOTE" to tr("Notes")),
)

private fun choiceTitle(key: String): String = when (key) {
    Keys.SWIPE -> tr("Swipe left / right"); Keys.COLOR_SET -> tr("Income / expense colours"); Keys.TIME_INPUT -> tr("Time input")
    Keys.TIME_SORT -> tr("Order within a day"); Keys.INPUT_ORDER -> tr("Input order"); Keys.THEME -> tr("Theme")
    Keys.REPEAT_TIMING -> tr("Repeat entries are added"); Keys.WIDGET_THEME -> tr("Widget style"); Keys.WIDGET_OPACITY -> tr("Widget background")
    Keys.WEEKEND_SHIFT -> tr("Start date on a weekend"); Keys.WEEK_START -> tr("Week starts on"); else -> tr("Start view")
}

private val STORED_KEYS = listOf(
    Keys.LANGUAGE, Keys.CARRY_OVER, Keys.CARD_AT_PAYMENT, Keys.REMINDER, Keys.QUICK_ADD_NOTIFICATION, Keys.CAPTURE_ENABLED, Keys.LOCATION,
    Keys.USE_SUBCATEGORIES, Keys.PASSCODE, Keys.REMINDER_TIME, Keys.START_SCREEN, Keys.SWIPE, Keys.COLOR_SET, Keys.TIME_INPUT, Keys.TIME_SORT,
    Keys.INPUT_ORDER, Keys.THEME, Keys.WIDGET_THEME, Keys.WIDGET_OPACITY, Keys.REPEAT_TIMING, Keys.AUTOCOMPLETE, Keys.HABIT_ACCOUNT,
    Keys.SHOW_DESCRIPTION, Keys.NOTE_BUTTON, Keys.SYMBOL_AFTER,
)

@Composable
internal fun FreshSettings() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val platform = LocalPlatform.current
    val backup = LocalBackup.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    val v: Map<String, String?> = STORED_KEYS.associateWith { k -> remember(repo, k) { repo.setting(k) }.collectAsState(null).value }
    fun on(key: String) = v[key] == "1"
    val startDay by repo.monthStartDay.collectAsState(1)
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val categories by repo.categories.collectAsState(emptyList())
    val rules by repo.rules.collectAsState(emptyList())
    val tags by repo.tags.collectAsState(emptyList())
    val templates by repo.templates.collectAsState(emptyList())
    val currencies by repo.currencies.collectAsState(emptyList())
    val snapshots by backup.localBackups.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf("look") }
    var choice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var accentSheet by remember { mutableStateOf(false) }
    var languageSheet by remember { mutableStateOf(false) }
    var locationDisclosure by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var layoutSwitch by remember { mutableStateOf(false) }

    // Widgets show colours, currency format and their own style; repaint them after any change.
    fun put(key: String, value: String) = scope.launch { withContext(repo.io) { repo.putSetting(key, value) }; platform.refreshWidgets() }
    fun setFlag(key: String, on: Boolean) = put(key, if (on) "1" else "0")

    val choices = settingChoices()
    fun raw(key: String): String? = when (key) {
        Keys.WEEKEND_SHIFT -> ps.shift.name
        Keys.WEEK_START -> ps.weekStart.name
        else -> v[key]
    }
    fun label(key: String): String {
        val options = choices.getValue(key)
        val r = raw(key)
        return options.firstOrNull { it.first == r }?.second
            ?: if (key == Keys.WEEK_START && r != null) tr(r.lowercase().replaceFirstChar { it.uppercase() }) else options.first().second
    }

    fun link(title: String, value: String? = null, words: String = "", subtitle: String? = null, onClick: () -> Unit) =
        SRow(title, value, subtitle, words, onClick = onClick)
    fun toggle(title: String, checked: Boolean, subtitle: String? = null, words: String = "", onChange: (Boolean) -> Unit) =
        SRow(title, null, subtitle, words, checked) { onChange(!checked) }
    fun pick(key: String, title: String = choiceTitle(key), words: String = "") =
        SRow(title, label(key), null, words + " " + choices.getValue(key).joinToString(" ") { it.second }) { choice = key }

    val passcodeOn = !v[Keys.PASSCODE].isNullOrBlank()
    val capture = on(Keys.CAPTURE_ENABLED)
    val language = I18n.LANGUAGES.firstOrNull { it.first == v[Keys.LANGUAGE].orEmpty() }?.takeIf { it.first.isNotEmpty() }?.second ?: tr("Phone language")
    val mains = { k: CategoryKind -> categories.count { it.kind == k && it.parentId == null } }

    val sections = listOf(
        SSection("look", tr("Appearance"), tr("Layout, theme, colours, language, widgets"), listOf(
            toggle(tr("New layout"), true, tr("Turn off to go back to the classic layout. MoneyTracker restarts to switch."),
                "layout classic old design screens restart") { if (!it) layoutSwitch = true },
            pick(Keys.THEME, words = "style dark light night mode"),
            link(tr("Accent colour"), tr(accentChoice.label), "color colour accent wallpaper") { accentSheet = true },
            pick(Keys.COLOR_SET, words = "color colour green red"),
            link(tr("Language"), language, "translate translation hindi") { languageSheet = true },
            pick(Keys.WIDGET_THEME, words = "home screen widget dark light"),
            pick(Keys.WIDGET_OPACITY, words = "home screen widget transparent opacity"),
        )),
        SSection("lists", tr("Your lists"), tr("Categories, tags, bookmarks, repeats, accounts"), listOf(
            link(tr("Expense categories"), mains(CategoryKind.EXPENSE).toString(), "category sub emoji") { nav.push(Screen.Categories(CategoryKind.EXPENSE)) },
            link(tr("Income categories"), mains(CategoryKind.INCOME).toString(), "category salary") { nav.push(Screen.Categories(CategoryKind.INCOME)) },
            toggle(tr("Sub-categories"), on(Keys.USE_SUBCATEGORIES), tr("Pick a sub-category (Food › Lunch) when adding an entry"), "category") {
                setFlag(Keys.USE_SUBCATEGORIES, it)
            },
            link(tr("Budgets"), null, "budget limit monthly plan") { nav.push(Screen.Budget) },
            link(tr("Tags"), tags.size.toString(), "label hashtag") { nav.push(Screen.Tags) },
            link(tr("Bookmarks"), templates.size.toString(), "quick add favourite star template") { nav.push(Screen.Templates) },
            link(tr("Repeat entries"), rules.size.toString(), "recurring installment rent subscription repeat") { nav.push(Screen.Recurring) },
            link(tr("Accounts"), null, "account card wallet bank hidden deleted") { nav.push(Screen.AccountList) },
            link(tr("Account groups"), null, "accounts group order") { nav.push(Screen.AccountGroups) },
            link(tr("Sub currencies"), currencies.joinToString(", ") { it.code }.ifEmpty { null }, "currency dollar exchange rate foreign") { nav.push(Screen.Currencies) },
        )),
        SSection("adding", tr("Adding entries"), tr("Order, suggestions, time, location"), listOf(
            pick(Keys.INPUT_ORDER, words = "keypad form"),
            toggle(tr("Remember usual accounts"), v[Keys.HABIT_ACCOUNT] != "0",
                tr("Pre-select the account you usually use; a category's preferred account comes first"), "default account preselect habit") {
                setFlag(Keys.HABIT_ACCOUNT, it)
            },
            toggle(tr("Suggest past notes"), v[Keys.AUTOCOMPLETE] != "0", tr("Suggest past notes while typing; picks their category and account"), "autocomplete") {
                setFlag(Keys.AUTOCOMPLETE, it)
            },
            link(tr("Reset suggestions"), null, "autocomplete clear forget notes") { confirmReset = true },
            pick(Keys.TIME_INPUT, words = "clock hour time"),
            toggle(tr("Save location"), on(Keys.LOCATION), tr("Attach your current location to new entries"), "gps map place") {
                if (it) locationDisclosure = true else setFlag(Keys.LOCATION, false)
            },
            pick(Keys.REPEAT_TIMING, words = "recurring timing reflection"),
        )),
        SSection("numbers", tr("Numbers & dates"), tr("Currency, decimals, month and week start"), listOf(
            link(tr("Currency symbol"), currency.symbol, "rupee money") { editing = Keys.CURRENCY_SYMBOL },
            toggle(tr("Symbol after amount"), on(Keys.SYMBOL_AFTER),
                if (on(Keys.SYMBOL_AFTER)) "1,000.00 ${currency.symbol}" else "${currency.symbol} 1,000.00", "currency") { setFlag(Keys.SYMBOL_AFTER, it) },
            link(tr("Decimal places"), currency.decimals.toString(), "paise cents rounding",
                tr("Set decimal places before entering data; existing amounts are not converted.")) { editing = Keys.CURRENCY_DECIMALS },
            link(tr("Month starts on"), tr("Every {0}", startDay), "monthly start date salary period cycle") { editing = Keys.MONTH_START_DAY },
            pick(Keys.WEEKEND_SHIFT, words = "friday monday"),
            pick(Keys.WEEK_START, words = "weekly start day"),
            toggle(tr("Carry-over"), on(Keys.CARRY_OVER), tr("Show the balance carried from earlier months"), "previous month balance") {
                setFlag(Keys.CARRY_OVER, it)
            },
            toggle(tr("Card expenses at payment"), on(Keys.CARD_AT_PAYMENT), tr("Count credit-card purchases when the bill is paid, not when you buy"), "credit bill") {
                setFlag(Keys.CARD_AT_PAYMENT, it)
            },
        )),
        SSection("alerts", tr("Reminders"), tr("Daily nudge and quick-add buttons"), listOfNotNull(
            toggle(tr("Daily reminder"), on(Keys.REMINDER), tr("A nudge to record the day's spending"), "notification alert") { turnOn ->
                val (h, m) = (v[Keys.REMINDER_TIME] ?: "21:00").split(":").map { it.toIntOrNull() ?: 0 }
                platform.setReminder(turnOn, h, m) { active -> setFlag(Keys.REMINDER, active) }
            },
            if (on(Keys.REMINDER)) link(tr("Reminder time"), v[Keys.REMINDER_TIME] ?: "21:00", "notification clock hour") { editing = Keys.REMINDER_TIME } else null,
            toggle(tr("Quick add in notification bar"), on(Keys.QUICK_ADD_NOTIFICATION), tr("Expense / Income / Transfer buttons"), "notification shortcut") { turnOn ->
                platform.setQuickAddNotification(turnOn) { active -> setFlag(Keys.QUICK_ADD_NOTIFICATION, active) }
            },
        )),
        SSection("security", tr("Security & SMS"), tr("Passcode, bank messages"), listOf(
            link(tr("Passcode"), if (passcodeOn) tr("On") else tr("Off"), "pin lock password fingerprint face biometric security") { nav.push(Screen.Security) },
            link(tr("Bank SMS"), if (capture) tr("On") else tr("Off"), "message text detect auto") { nav.push(Screen.MessageSettings) },
            link(tr("Detected SMS"), null, "message inbox review bank") { nav.push(Screen.Inbox) },
        )),
        SSection("lists", tr("Activity"), tr("Start view, swipe, order, description"), listOf(
            pick(Keys.START_SCREEN, words = "start screen open view list calendar months notes"),
            pick(Keys.SWIPE, words = "gesture tab month"),
            pick(Keys.TIME_SORT, words = "sort"),
            toggle(tr("Show description"), on(Keys.SHOW_DESCRIPTION), tr("Description under the note in lists"), "details memo") {
                setFlag(Keys.SHOW_DESCRIPTION, it)
            },
            toggle(tr("Note button"), v[Keys.NOTE_BUTTON] != "0", tr("Classic layout: add a note from the Daily view, next to +"), "memo classic") { setFlag(Keys.NOTE_BUTTON, it) },
        )),
        SSection("tools", tr("Tools & info"), tr("Backup, calculator, help, about"), listOf(
            link(tr("Backup & data"), null, "backup restore export import snapshot reset") { nav.push(Screen.Backup) },
            link(tr("Calculator"), null, "math") { nav.push(Screen.Calculator) },
            link(tr("Help"), null, "guide how faq") { nav.push(Screen.Help) },
            link(tr("About MoneyTracker"), platform.appVersion.ifEmpty { null }, "version privacy source github feedback contributing") { nav.push(Screen.About) },
            link(tr("Open-source licences"), "GPL-3.0", "license licence legal libraries") { nav.push(Screen.License("GPL-3.0")) },
        )),
    )
    // Options that live on other screens: found by search, they open that screen.
    val toBackup = { nav.push(Screen.Backup) }
    val toSms = { nav.push(Screen.MessageSettings) }
    val elsewhere = listOf(
        tr("Backup & data") to link(tr("Back up now"), null, "save snapshot", onClick = toBackup),
        tr("Backup & data") to link(tr("Restore from a file"), null, "import recover mmbak", onClick = toBackup),
        tr("Backup & data") to link(tr("Export CSV or Excel"), null, "spreadsheet xlsx download share", onClick = toBackup),
        tr("Backup & data") to link(tr("Import CSV or Excel"), null, "spreadsheet xlsx upload", onClick = toBackup),
        tr("Backup & data") to link(tr("Export as .mmbak"), null, "backup file migrate", onClick = toBackup),
        tr("Backup & data") to link(tr("Export or import photos"), null, "receipt picture zip", onClick = toBackup),
        tr("Backup & data") to link(tr("Clear entries / reset everything"), null, "delete erase wipe start over", onClick = toBackup),
        tr("Bank SMS") to link(tr("Detect bank SMS"), if (capture) tr("On") else tr("Off"), "message text auto", onClick = toSms),
        tr("Bank SMS") to link(tr("Blocked senders and words"), null, "filter block spam message", onClick = toSms),
        tr("Bank SMS") to link(tr("Import SMS from the inbox"), null, "message read past", onClick = toSms),
    )

    SubScreen(tr("Settings"), nav::pop) {
        SearchBox(query) { query = it }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val all = sections.flatMap { s -> s.rows.map { s.title to it } } + elsewhere.map { (where, r) -> "$where ›" to r }
            val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
            val found = all.filter { (where, r) ->
                val text = listOfNotNull(r.title, r.value, r.subtitle, where, r.words).joinToString(" ").lowercase()
                terms.all { it in text }
            }
            Text(if (found.size == 1) tr("1 setting found") else tr("{0} settings found", found.size),
                Modifier.padding(start = 4.dp), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (found.isEmpty()) FCard {
                Text(tr("No settings match “{0}”", query.trim()), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(tr("Try a simpler word, like theme, currency, backup or SMS."), Modifier.padding(top = 4.dp), color = F.muted, fontSize = 13.sp)
            } else FCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    found.forEachIndexed { i, (where, r) ->
                        if (i > 0) Divider()
                        SettingRowView(r, where)
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val last = snapshots.firstOrNull()?.let { snapshotWhen(it.name, repo.today()) } ?: tr("Not yet")
                QuickTile(Icons.Outlined.Restore, tr("Backup"), last, Modifier.weight(1f)) { nav.push(Screen.Backup) }
                QuickTile(Icons.Outlined.MarkEmailUnread, tr("Bank SMS"), if (capture) tr("On") else tr("Off"), Modifier.weight(1f)) { nav.push(Screen.MessageSettings) }
                QuickTile(Icons.Outlined.Lock, tr("Passcode"), if (passcodeOn) tr("On") else tr("Off"), Modifier.weight(1f)) { nav.push(Screen.Security) }
            }
            sections.forEach { sec ->
                val isOpen = open == sec.id
                FCard(padding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (isOpen) "" else sec.id }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sec.title, color = F.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(sec.sub, color = F.muted, fontSize = 12.sp)
                        }
                        Icon(if (isOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            if (isOpen) tr("Collapse") else tr("Expand"), tint = F.muted)
                    }
                    if (isOpen) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
                        sec.rows.forEach { r -> Divider(); SettingRowView(r, null) }
                    }
                }
            }
            Text(tr("Tap a setting to change it. Changes save right away."), Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp)
        }
    }

    if (layoutSwitch) LayoutSwitchDialog(toNew = false) { layoutSwitch = false }
    if (locationDisclosure) {
        DisclosureDialog(tr("Save location"),
            tr("When you add a new entry, MoneyTracker saves where you are (latitude and longitude) with it. " +
                "Location is read only while you add an entry, never in the background, and stays on your phone."),
            { locationDisclosure = false }) { locationDisclosure = false; setFlag(Keys.LOCATION, true) }
    }
    if (confirmReset) {
        Confirm(tr("Reset note suggestions?"),
            tr("MoneyTracker forgets the notes, categories and accounts it learned from past entries. Your entries stay as they are."),
            tr("Reset"), { confirmReset = false }) {
            scope.launch { withContext(repo.io) { repo.resetAutocomplete() }; toaster.show(tr("Note suggestions reset")) }
        }
    }
    choice?.let { key ->
        val options = choices.getValue(key)
        val current = raw(key) ?: options.first().first
        Sheet(choiceTitle(key), { choice = null }) {
            options.forEach { (value, text) ->
                SheetOption(text, selected = value == current) {
                    choice = null
                    if (value != current) {
                        put(key, value)
                        toaster.show(tr("{0}: {1}", choiceTitle(key), text)) { put(key, current) }
                    }
                }
            }
        }
    }
    if (languageSheet) {
        val currentTag = v[Keys.LANGUAGE].orEmpty()
        Sheet(tr("Language"), { languageSheet = false }) {
            I18n.LANGUAGES.forEach { (tag, name) ->
                SheetOption(if (tag.isEmpty()) tr(name) else name, selected = tag == currentTag) { languageSheet = false; put(Keys.LANGUAGE, tag) }
            }
        }
    }
    if (accentSheet) {
        Sheet(tr("Accent colour"), { accentSheet = false }) {
            // "Follow system" only where the OS provides wallpaper colours (Android 12+).
            AccentChoice.entries.filter { it != AccentChoice.SYSTEM || systemAccent != null }.forEach { a ->
                val c = a.tones().let { Color(if (darkTheme) it.dark else it.light) }
                SheetOption(tr(a.label), selected = a == accentChoice, leading = { Box(Modifier.size(24.dp).clip(CircleShape).background(c)) }) {
                    accentSheet = false
                    val before = accentChoice
                    if (a != before) { put(Keys.ACCENT, a.name); toaster.show(tr("{0}: {1}", tr("Accent colour"), tr(a.label))) { put(Keys.ACCENT, before.name) } }
                }
            }
        }
    }
    editing?.let { key -> EditValueSheet(key, startDay, v[Keys.REMINDER_TIME], { editing = null }) { value ->
        editing = null
        scope.launch {
            withContext(repo.io) { repo.putSetting(key, value) }
            platform.refreshWidgets()
            if (key == Keys.REMINDER_TIME) value.split(":").map { it.toInt() }.let { (h, m) -> platform.setReminder(true, h, m) {} }
        }
    } }
}

/** Text settings: currency symbol, decimals, month start day, reminder time. */
@Composable
private fun EditValueSheet(key: String, startDay: Int, reminderTime: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val currency = LocalCurrency.current
    var text by remember(key) {
        mutableStateOf(when (key) {
            Keys.MONTH_START_DAY -> startDay.toString()
            Keys.CURRENCY_SYMBOL -> currency.symbol
            Keys.REMINDER_TIME -> reminderTime ?: "21:00"
            else -> currency.decimals.toString()
        })
    }
    val t = text.trim()
    val time = t.split(":").map { it.toIntOrNull() }
    val valid = when (key) {
        Keys.MONTH_START_DAY -> t.toIntOrNull() in 1..31
        Keys.CURRENCY_DECIMALS -> t.toIntOrNull() in 0..3
        Keys.REMINDER_TIME -> time.size == 2 && time[0] in 0..23 && time[1] in 0..59
        else -> t.isNotBlank() && t.length <= 4
    }
    val title = when (key) {
        Keys.MONTH_START_DAY -> tr("Monthly start date (1–31)")
        Keys.CURRENCY_SYMBOL -> tr("Currency symbol")
        Keys.REMINDER_TIME -> tr("Reminder time (24h, HH:mm)")
        else -> tr("Decimal places (0–3)")
    }
    Sheet(title, onDismiss, bottom = {
        PrimaryButton(tr("Save"), enabled = valid, onClick = {
            onSave(if (key == Keys.REMINDER_TIME) "${time[0].toString().padStart(2, '0')}:${time[1].toString().padStart(2, '0')}" else t)
        })
    }) {
        StgField(text, { text = if (key == Keys.REMINDER_TIME) it.take(5) else it }, number = key != Keys.CURRENCY_SYMBOL && key != Keys.REMINDER_TIME, error = !valid)
        if (key == Keys.CURRENCY_DECIMALS) Text(tr("Set decimal places before entering data; existing amounts are not converted."),
            Modifier.padding(top = 8.dp), color = F.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingRowView(r: SRow, where: String?) {
    val checked = r.checked
    FRow(
        r.title,
        subtitle = where ?: r.subtitle,
        value = if (checked == null) r.value else null,
        valueColor = F.accent,
        onClick = r.onClick,
        trailing = if (checked != null) { { StgSwitch(checked) { r.onClick() } } } else null,
    )
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(F.buttonShape).background(F.card).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Search, null, tint = F.muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(tr("Search settings, e.g. dark, pin, export"), color = F.muted, fontSize = 15.sp, maxLines = 1)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = F.ink, fontSize = 15.sp), cursorBrush = SolidColor(F.accent))
        }
        if (value.isNotEmpty()) Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = tr("Clear search")) { onChange("") },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, tr("Clear search"), tint = F.muted, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    FCard(modifier, onClick = onClick, padding = 12.dp) {
        Box(Modifier.size(32.dp).clip(F.iconShape).background(F.accentTint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = F.accent, modifier = Modifier.size(18.dp))
        }
        Text(title, Modifier.padding(top = 8.dp), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value, color = F.muted, fontSize = 12.sp, maxLines = 1)
    }
}
