package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LayoutSwitchDialog
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.I18n
import app.moneytracker.core.rememberSetting
import app.moneytracker.core.tr
import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.designsystem.classic.AccentChoice
import app.moneytracker.designsystem.classic.accentChoice
import app.moneytracker.designsystem.classic.darkTheme
import app.moneytracker.designsystem.classic.systemAccent
import app.moneytracker.designsystem.classic.tones
import app.moneytracker.designsystem.fresh.Confirm
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.FRow
import app.moneytracker.designsystem.fresh.FSwitchRow
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SheetOption
import app.moneytracker.designsystem.fresh.SheetOptionGroup
import app.moneytracker.designsystem.fresh.StgField
import app.moneytracker.designsystem.fresh.SubScreen
import app.moneytracker.designsystem.fresh.pick
import app.moneytracker.ui.BackClosesGroup
import app.moneytracker.ui.ChoiceHost
import app.moneytracker.ui.ChoiceSpec
import app.moneytracker.ui.DisclosureDialog
import app.moneytracker.ui.MorePlace
import app.moneytracker.ui.SettingDialog
import app.moneytracker.ui.SettingGroup
import app.moneytracker.ui.SettingsModel
import app.moneytracker.ui.rememberMorePlaces
import app.moneytracker.ui.rememberSettingGroups
import app.moneytracker.ui.rememberSettingHubs
import app.moneytracker.ui.rememberSettingsModel
import app.moneytracker.ui.rememberSettingsOverview
import app.moneytracker.ui.rememberTextEdit
import app.moneytracker.ui.searchRows
import app.moneytracker.ui.searchSettings
import app.moneytracker.ui.settingActions

private typealias SRow = app.moneytracker.ui.SettingRow

/** New-layout Settings: the places as tiles, then the six preference groups, all searchable. */
@Composable
internal fun FreshSettings() {
    val nav = LocalNavigator.current
    val model = rememberSettingsModel()
    val overview by rememberSettingsOverview()
    var query by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = rememberSettingGroups(newLayout = true, settingActions { dialog = it }, model, overview)
    val hubs = rememberSettingHubs(model, overview)
    val places = rememberMorePlaces(newLayout = true, overview)
    val elsewhere = rememberElsewhereRows()
    val group = groups.firstOrNull { it.id == groupId }
    BackClosesGroup(group != null) { groupId = null }

    SubScreen(group?.title ?: tr("Settings"), { if (group != null) groupId = null else nav.pop() }) {
        if (group != null) {
            RowsCard(group.rows.map { null to it })
            Text(tr("Changes save right away."), Modifier.fillMaxWidth(), color = F.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            return@SubScreen
        }
        SearchBox(query) { query = it }
        val q = query.trim()
        if (q.isNotEmpty()) SearchResults(searchSettings(groups, hubs, q) + searchRows(elsewhere, q), q)
        else SettingsHome(places, groups) { groupId = it }
    }
    dialog?.let { d -> FreshSettingDialog(d, model, open = { dialog = it }, close = { dialog = null }) }
}

/** Options that live on other screens: found by search, they open that screen. */
@Composable
private fun rememberElsewhereRows(): List<Pair<String, SRow>> {
    val nav = LocalNavigator.current
    val capture by rememberSetting(Prefs.CAPTURE_ENABLED)
    fun link(title: String, value: String? = null, words: String = "", onClick: () -> Unit) = SRow(title, value, null, words, onClick = onClick)
    val toBackup = { nav.push(Screen.Backup) }
    val toSms = { nav.push(Screen.MessageSettings) }
    return listOf(
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
    ).map { (where, r) -> "$where ›" to r }
}

/** Setting rows in one card; a row's hint is where it lives when that is given. */
@Composable
private fun RowsCard(rows: List<Pair<String?, SRow>>) {
    FListCard {
        rows.forEachIndexed { i, (where, r) ->
            if (i > 0) Divider(inset = ROW_INSET)
            SettingRowView(r, where, edgeTop = if (i == 0) CARD_EDGE else 0.dp, edgeBottom = if (i == rows.lastIndex) CARD_EDGE else 0.dp)
        }
    }
}

/** Settings rows start 14dp in from their card's edge, lined up with the tiles' content. */
private val ROW_INSET = 14.dp

/** The card's top and bottom spacing, kept inside the first and last rows so their highlight reaches the rounded edge. */
private val CARD_EDGE = 4.dp



@Composable
private fun SearchResults(found: List<Pair<String, SRow>>, q: String) {
    Text(if (found.size == 1) tr("1 setting found") else tr("{0} settings found", found.size),
        Modifier.padding(start = 4.dp), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    if (found.isEmpty()) FCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("No settings match “{0}”", q), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Text(tr("Try a simpler word, like theme, currency, backup or SMS."), Modifier.padding(top = 4.dp), color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    } else RowsCard(found)
}

@Composable
private fun SettingsHome(places: List<MorePlace>, groups: List<SettingGroup>, onOpenGroup: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        places.chunked(2).forEach { pair ->
            // Tiles in a row share the taller one's height, so their edges line up.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { p -> QuickTile(placeGlyph(p.id), p.title, p.status, Modifier.weight(1f).fillMaxHeight(), p.open) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
    // The label sits right on top of its card, lined up with the rows inside it.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Preferences").uppercase(), Modifier.padding(start = 14.dp), color = F.muted, fontSize = 12.sp,
            fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
        FListCard {
            groups.forEachIndexed { i, g ->
                if (i > 0) Divider(inset = ROW_INSET)
                FRow(g.title, subtitle = g.summary, onClick = { onOpenGroup(g.id) }, leading = {
                    Box(Modifier.size(36.dp).clip(F.controlShape).background(F.page), contentAlignment = Alignment.Center) {
                        Icon(groupGlyph(g.id), null, tint = F.accent, modifier = Modifier.size(18.dp))
                    }
                }, inset = ROW_INSET, extraTop = if (i == 0) CARD_EDGE else 0.dp, extraBottom = if (i == groups.lastIndex) CARD_EDGE else 0.dp)
            }
        }
    }
    Text(tr("Each line shows what is set now. Changes save right away."), Modifier.fillMaxWidth().padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp,
        textAlign = TextAlign.Center)
}

@Composable
private fun FreshSettingDialog(d: SettingDialog, model: SettingsModel, open: (SettingDialog) -> Unit, close: () -> Unit) {
    val toaster = LocalToaster.current
    when (d) {
        SettingDialog.LayoutChoice -> Sheet(tr("Layout"), close) {
            SheetOptionGroup {
                SheetOption(tr("Classic"), selected = false, subtitle = tr("The familiar tabs: Trans., Stats, Accounts, More")) { open(SettingDialog.LayoutSwitch) }
                SheetOption(tr("New"), selected = true, subtitle = tr("Home with today's spending and Smart add, then Activity, Insights and Accounts"), onClick = close)
            }
            Text(tr("MoneyTracker restarts to switch. Your entries, accounts and settings stay the same."), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = F.muted, fontSize = 12.sp)
        }
        SettingDialog.LayoutSwitch -> LayoutSwitchDialog(toNew = false, onDismiss = close)
        SettingDialog.Location -> DisclosureDialog(tr("Save location"),
            tr("When you add a new entry, MoneyTracker saves where you are (latitude and longitude) with it. " +
                "Location is read only while you add an entry, never in the background, and stays on your phone."),
            close) { close(); model.save(Prefs.LOCATION, true) }
        SettingDialog.ResetSuggestions -> Confirm(tr("Reset note suggestions?"),
            tr("MoneyTracker forgets the notes, categories and accounts it learned from past entries. Your entries stay as they are."),
            tr("Reset"), close) {
            model.resetSuggestions { toaster.show(tr("Note suggestions reset")) }
        }
        SettingDialog.Language -> LanguageSheet(model, close)
        SettingDialog.Accent -> AccentSheet(model, close)
        is SettingDialog.Choice -> ChoiceSheet(d.spec, model, close)
        is SettingDialog.Edit -> EditValueSheet(d.setting, model, close)
    }
}

@Composable
private fun LanguageSheet(model: SettingsModel, onClose: () -> Unit) {
    val currentTag by rememberSetting(Prefs.LANGUAGE)
    Sheet(tr("Language"), onClose) {
        SheetOptionGroup {
            I18n.LANGUAGES.forEach { (tag, name) ->
                SheetOption(if (tag.isEmpty()) tr(name) else name, selected = tag == currentTag) {
                    onClose(); model.save(Prefs.LANGUAGE, tag)
                }
            }
        }
    }
}

/** Accent colours; a change can be undone from its toast. */
@Composable
private fun AccentSheet(model: SettingsModel, onClose: () -> Unit) {
    val toaster = LocalToaster.current
    Sheet(tr("Accent colour"), onClose) {
        SheetOptionGroup {
            // "Follow system" only where the OS provides wallpaper colours (Android 12+).
            AccentChoice.entries.filter { it != AccentChoice.SYSTEM || systemAccent != null }.forEach { a ->
                val c = a.tones().let { Color(if (darkTheme) it.dark else it.light) }
                SheetOption(tr(a.label), selected = a == accentChoice, leading = { Box(Modifier.size(24.dp).clip(CircleShape).background(c)) }) {
                    onClose()
                    val before = accentChoice
                    if (a != before) {
                        model.save(Prefs.ACCENT, a.name)
                        toaster.show(tr("{0}: {1}", tr("Accent colour"), tr(a.label))) { model.save(Prefs.ACCENT, before.name) }
                    }
                }
            }
        }
    }
}

/** A sheet or toast-backed choice for one multiple-choice setting; Undo puts the old value back. */
@Composable
private fun <T> ChoiceSheet(spec: ChoiceSpec<T>, model: SettingsModel, onClose: () -> Unit) {
    val toaster = LocalToaster.current
    ChoiceHost(spec, model, content = { current, options, pick ->
        Sheet(spec.title, onClose) {
            SheetOptionGroup { options.forEach { (value, text) -> SheetOption(text, selected = value == current) { onClose(); pick(value, text) } } }
        }
    }) { _, text, before -> toaster.show(tr("{0}: {1}", spec.title, text)) { model.save(spec.setting, before) } }
}

/** Text settings: currency symbol, decimals, month start day, reminder time. */
@Composable
private fun EditValueSheet(key: Setting<*>, model: SettingsModel, onClose: () -> Unit) {
    val edit = rememberTextEdit(model, key)
    var text by remember(key) { mutableStateOf(edit.initial) }
    val valid = edit.valid(text)
    Sheet(edit.title, onClose, bottom = {
        PrimaryButton(tr("Save"), enabled = valid, onClick = { onClose(); model.saveText(key, edit, text) })
    }) {
        StgField(text, { text = if (key == Prefs.REMINDER_TIME) it.take(5) else it }, number = edit.number, error = !valid)
        if (key == Prefs.CURRENCY_DECIMALS) Text(tr("Set decimal places before entering data; existing amounts are not converted."),
            Modifier.padding(top = 8.dp), color = F.muted, fontSize = 12.sp)
    }
}

/** One setting: a switch row when it is on/off, otherwise a row showing its value. */
@Composable
private fun SettingRowView(r: SRow, where: String?, edgeTop: Dp, edgeBottom: Dp) {
    val checked = r.checked
    if (checked != null) {
        FSwitchRow(r.title, checked, { r.onClick() }, subtitle = where ?: r.subtitle, inset = ROW_INSET, extraTop = edgeTop, extraBottom = edgeBottom)
    } else {
        FRow(r.title, subtitle = where ?: r.subtitle, value = r.value, valueColor = F.accent, onClick = r.onClick,
            inset = ROW_INSET, extraTop = edgeTop, extraBottom = edgeBottom)
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    // A thin accent ring while typing shows where the text goes.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val clearLabel = tr("Clear search")
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
            Modifier.size(32.dp).clip(F.innerShape).background(F.page).clickable(role = Role.Button) { onChange("") }
                .semantics { contentDescription = clearLabel },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Close, null, tint = F.muted, modifier = Modifier.size(18.dp)) }
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
    val overview by rememberSettingsOverview()
    val hub = rememberSettingHubs(rememberSettingsModel(), overview).firstOrNull { it.id == id } ?: return
    SubScreen(hub.title, nav::pop) {
        Text(hub.intro, Modifier.padding(horizontal = 14.dp), color = F.muted, fontSize = 13.sp)
        RowsCard(hub.rows.map { null to it })
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
