package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private typealias SRow = app.moneytracker.ui.SettingRow

/** Multiple-choice settings: stored value → label. The first option is the default. */
private fun settingChoices(): Map<String, List<Pair<String, String>>> = mapOf(
    Keys.SWIPE to listOf("DATE" to tr("Change month"), "TAB" to tr("Change tab"), "NONE" to tr("Off")),
    Keys.COLOR_SET to listOf("A" to tr("Income blue, expense red"), "B" to tr("Income red, expense blue")),
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

    var query by rememberSaveable { mutableStateOf("") }
    var choice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var accentSheet by remember { mutableStateOf(false) }
    var languageSheet by remember { mutableStateOf(false) }
    var locationDisclosure by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var layoutSwitch by remember { mutableStateOf(false) }
    var layoutSheet by remember { mutableStateOf(false) }

    // Widgets show colours, currency format and their own style; repaint them after any change.
    fun put(key: String, value: String) = scope.launch { withContext(repo.io) { repo.putSetting(key, value) }; platform.refreshWidgets() }
    fun setFlag(key: String, on: Boolean) = put(key, if (on) "1" else "0")

    val choices = settingChoices()
    fun raw(key: String): String? = when (key) {
        Keys.WEEKEND_SHIFT -> ps.shift.name
        Keys.WEEK_START -> ps.weekStart.name
        else -> v[key]
    }
    fun link(title: String, value: String? = null, words: String = "", subtitle: String? = null, onClick: () -> Unit) =
        SRow(title, value, subtitle, words, onClick = onClick)
    val capture = on(Keys.CAPTURE_ENABLED)

    val actions = app.moneytracker.ui.SettingActions(
        choice = { choice = it },
        edit = { editing = it },
        accent = { accentSheet = true },
        language = { languageSheet = true },
        switchLayout = { layoutSheet = true },
        locationOn = { locationDisclosure = true },
        resetSuggestions = { confirmReset = true },
        choiceLabel = { key, value -> choices[key]?.let { opts -> (opts.firstOrNull { it.first == value } ?: opts.first()).second } ?: "" },
    )
    val groups = app.moneytracker.ui.rememberSettingGroups(newLayout = true, a = actions)
    val hubs = app.moneytracker.ui.rememberSettingHubs()
    val places = app.moneytracker.ui.rememberMorePlaces(newLayout = true)
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    val group = groups.firstOrNull { it.id == groupId }
    // Back closes an open group first.
    androidx.compose.runtime.DisposableEffect(group != null) {
        val remove = if (group != null) nav.interceptBack { groupId = null; true } else ({})
        onDispose { remove() }
    }
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

    SubScreen(group?.title ?: tr("Settings"), { if (group != null) groupId = null else nav.pop() }) {
        if (group != null) {
            FCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    group.rows.forEachIndexed { i, r -> if (i > 0) Divider(); SettingRowView(r, null) }
                }
            }
            Text(tr("Changes save right away."), Modifier.fillMaxWidth(), color = F.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            return@SubScreen
        }
        SearchBox(query) { query = it }
        val q = query.trim()
        if (q.isNotEmpty()) {
            val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val found = app.moneytracker.ui.searchSettings(groups, hubs, q) + elsewhere.map { (where, r) -> "$where ›" to r }.filter { (where, r) ->
                val text = listOfNotNull(r.title, r.value, r.subtitle, where, r.words).joinToString(" ").lowercase()
                terms.all { it in text }
            }
            Text(if (found.size == 1) tr("1 setting found") else tr("{0} settings found", found.size),
                Modifier.padding(start = 4.dp), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (found.isEmpty()) FCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("No settings match “{0}”", q), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Text(tr("Try a simpler word, like theme, currency, backup or SMS."), Modifier.padding(top = 4.dp), color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            } else FCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    found.forEachIndexed { i, (where, r) ->
                        if (i > 0) Divider()
                        SettingRowView(r, where)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                places.chunked(2).forEach { pair ->
                    // Tiles in a row share the taller one's height, so their edges line up.
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { p -> QuickTile(placeGlyph(p.id), p.title, p.status, Modifier.weight(1f).fillMaxHeight(), p.open) }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            // The label sits right on top of its card, lined up with the rows inside it.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Preferences").uppercase(), Modifier.padding(start = 14.dp), color = F.muted, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
            FCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    groups.forEachIndexed { i, g ->
                        if (i > 0) Divider()
                        FRow(g.title, subtitle = g.summary, onClick = { groupId = g.id }, leading = {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(F.page), contentAlignment = Alignment.Center) {
                                Icon(groupGlyph(g.id), null, tint = F.accent, modifier = Modifier.size(18.dp))
                            }
                        })
                    }
                }
            }
            }
            Text(tr("Each line shows what is set now. Changes save right away."), Modifier.fillMaxWidth().padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp,
                textAlign = TextAlign.Center)
        }
    }

    if (layoutSheet) Sheet(tr("Layout"), { layoutSheet = false }) {
        SheetOption(tr("Classic"), selected = false, subtitle = tr("The familiar tabs: Trans., Stats, Accounts, More")) { layoutSheet = false; layoutSwitch = true }
        SheetOption(tr("New"), selected = true, subtitle = tr("Home with today's spending and Smart add, then Activity, Insights and Accounts")) { layoutSheet = false }
        Text(tr("MoneyTracker restarts to switch. Your entries, accounts and settings stay the same."), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = F.muted, fontSize = 12.sp)
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
    // A thin accent ring while typing shows where the text goes.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(F.iconShape).background(F.card)
            .border(2.dp, if (focused) F.accent else Color.Transparent, F.iconShape).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Glyph.Search, null, tint = F.muted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(tr("Search settings, e.g. dark, pin, export"), color = F.muted, fontSize = 14.sp, maxLines = 1)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = F.ink, fontSize = 14.sp), cursorBrush = SolidColor(F.accent), interactionSource = interaction)
        }
        if (value.isNotEmpty()) Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(F.page).clickable(onClickLabel = tr("Clear search")) { onChange("") },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Close, tr("Clear search"), tint = F.muted, modifier = Modifier.size(18.dp)) }
    }
}

private fun placeGlyph(id: String): ImageVector = when (id) {
    "labels" -> Icons.AutoMirrored.Outlined.Label
    "budget" -> Glyph.Trend
    "reuse" -> Glyph.Repeat
    "sms" -> Glyph.Message
    "safety" -> Glyph.Lock
    "web" -> Icons.Outlined.Computer
    else -> Icons.AutoMirrored.Outlined.HelpOutline
}

private fun groupGlyph(id: String): ImageVector = when (id) {
    "money" -> Glyph.Money
    "calendar" -> Glyph.Calendar
    "look" -> Icons.Outlined.Palette
    "entering" -> Glyph.Edit
    "lists" -> Glyph.Activity
    else -> Glyph.Clock
}

/** Categories & tags, Repeat & bookmarks, Backup & security, Help & about. */
@Composable
internal fun FreshHub(id: String) {
    val nav = LocalNavigator.current
    val hub = app.moneytracker.ui.rememberSettingHubs().firstOrNull { it.id == id } ?: return
    SubScreen(hub.title, nav::pop) {
        Text(hub.intro, Modifier.padding(horizontal = 14.dp), color = F.muted, fontSize = 13.sp)
        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                hub.rows.forEachIndexed { i, r -> if (i > 0) Divider(); SettingRowView(r, null) }
            }
        }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    // A bare accent icon, no tinted box: small tiles read lighter than badges. Content starts 14 in, like card rows.
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(F.card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = F.accent, modifier = Modifier.size(20.dp))
        Text(title, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = F.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}
