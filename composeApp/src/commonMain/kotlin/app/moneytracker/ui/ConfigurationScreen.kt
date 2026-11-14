package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LayoutSwitchDialog
import app.moneytracker.app.LocalNavigator
import app.moneytracker.core.I18n
import app.moneytracker.core.tr
import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.AccentChoice
import app.moneytracker.designsystem.classic.AccentTint
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.PageBackground
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.accentChoice
import app.moneytracker.designsystem.classic.darkTheme
import app.moneytracker.designsystem.classic.systemAccent
import app.moneytracker.designsystem.classic.tones

/** Classic Settings: the six preference groups, searchable, each opening its own page. */
@Composable
fun ConfigurationScreen() {
    val nav = LocalNavigator.current
    val model = rememberSettingsModel()
    val overview by rememberSettingsOverview()
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val groups = rememberSettingGroups(newLayout = false, settingActions { dialog = it }, model, overview)
    val hubs = rememberSettingHubs(model, overview)
    val group = groups.firstOrNull { it.id == groupId }
    BackClosesGroup(group != null) { groupId = null }

    Column(Modifier.fillMaxSize().background(PageBackground)) {
        BackTopBar(group?.title ?: tr("Settings"), { if (group != null) groupId = null else nav.pop() })
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (group == null) ClassicSettingsSearch(query) { query = it }
            Spacer(Modifier.height(12.dp))
            when {
                group != null -> ClassicRows(group.rows.map { null to it })
                query.isNotBlank() -> ClassicSearchResults(searchSettings(groups, hubs, query), query)
                else -> ClassicGroupList(groups) { groupId = it }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    dialog?.let { d -> ClassicSettingDialog(d, model, open = { dialog = it }, close = { dialog = null }) }
}

@Composable
private fun ClassicSettingsSearch(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 16.dp, vertical = 10.dp)
            .background(PageBackground, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = SubtleText)
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            if (query.isEmpty()) Text(tr("Search, e.g. currency, dark, reminder"), color = SubtleText)
            BasicTextField(query, onChange, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = Ink),
                cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
        }
    }
    HorizontalDivider(color = DividerColor)
}

/** Rows between dividers; a row's hint is where it lives when that is given. */
@Composable
private fun ClassicRows(rows: List<Pair<String?, SettingRow>>) {
    HorizontalDivider(color = DividerColor)
    rows.forEach { (where, r) -> SettingRowClassic(r, where); HorizontalDivider(color = DividerColor) }
}

@Composable
private fun ClassicSearchResults(found: List<Pair<String, SettingRow>>, query: String) {
    if (found.isEmpty()) Text(tr("No settings match “{0}”", query.trim()), Modifier.fillMaxWidth().padding(32.dp),
        color = SubtleText, textAlign = TextAlign.Center)
    else ClassicRows(found)
}

@Composable
private fun ClassicGroupList(groups: List<SettingGroup>, onOpen: (String) -> Unit) {
    HorizontalDivider(color = DividerColor)
    groups.forEach { g ->
        Row(
            Modifier.fillMaxWidth().background(SurfaceColor).clickable { onOpen(g.id) }.heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).background(AccentTint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(groupIcon(g.id), null, Modifier.size(20.dp), tint = Accent)
            }
            Column(Modifier.weight(1f)) {
                Text(g.title, color = Ink)
                Text(g.summary, fontSize = 12.sp, color = SubtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = SubtleText)
        }
        HorizontalDivider(color = DividerColor)
    }
    Text(tr("Each line shows what is set now. Changes save right away."), Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
        fontSize = 12.sp, color = SubtleText)
}

@Composable
private fun ClassicSettingDialog(d: SettingDialog, model: SettingsModel, open: (SettingDialog) -> Unit, close: () -> Unit) {
    when (d) {
        SettingDialog.ResetSuggestions -> AlertDialog(
            onDismissRequest = close,
            title = { Text(tr("Reset note suggestions?")) },
            text = { Text(tr("MoneyTracker forgets the notes, categories and accounts it learned from past entries. Your entries stay as they are.")) },
            confirmButton = { TextButton(onClick = { close(); model.resetSuggestions() }) { Text(tr("Reset"), color = Accent) } },
            dismissButton = { TextButton(onClick = close) { Text(tr("Cancel")) } },
        )
        SettingDialog.LayoutChoice -> ClassicLayoutDialog(close) { open(SettingDialog.LayoutSwitch) }
        SettingDialog.LayoutSwitch -> LayoutSwitchDialog(toNew = true, onDismiss = close)
        SettingDialog.Location -> DisclosureDialog(tr("Save location"),
            tr("When you add a new entry, MoneyTracker saves where you are (latitude and longitude) with it. " +
                "Location is read only while you add an entry, never in the background, and stays on your phone."),
            close) { close(); model.save(Prefs.LOCATION, true) }
        SettingDialog.Language ->
            ChoiceDialog(tr("Language"), I18n.LANGUAGES.map { (tag, name) -> tag.ifEmpty { "system" } to (if (tag.isEmpty()) tr(name) else name) }, close) { tag ->
                model.save(Prefs.LANGUAGE, if (tag == "system") "" else tag); close()
            }
        SettingDialog.Accent -> ClassicAccentDialog(model, close)
        is SettingDialog.Choice -> ClassicChoiceDialog(d.spec, model, close)
        is SettingDialog.Edit -> ClassicEditDialog(d.setting, model, close)
    }
}

/** Classic or New; picking the other one asks to restart through [onSwitch]. */
@Composable
private fun ClassicLayoutDialog(onDismiss: () -> Unit, onSwitch: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Layout")) },
        text = {
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple(tr("Classic"), tr("The familiar tabs: Trans., Stats, Accounts, More"), true),
                    Triple(tr("New"), tr("Home with today's spending and Smart add, then Activity, Insights and Accounts"), false),
                ).forEach { (label, hint, current) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectable(current, role = Role.RadioButton) { if (current) onDismiss() else onSwitch() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = if (current) Accent else Ink)
                            Text(hint, fontSize = 12.sp, color = SubtleText)
                        }
                        if (current) Icon(Icons.Filled.Check, null, tint = Accent)
                    }
                }
                Text(tr("MoneyTracker restarts to switch. Your entries, accounts and settings stay the same."), Modifier.padding(top = 8.dp), fontSize = 12.sp, color = SubtleText)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun ClassicAccentDialog(model: SettingsModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Accent colour")) },
        text = {
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
                // "Follow system" only where the OS provides wallpaper colours (Android 12+).
                AccentChoice.entries.filter { it != AccentChoice.SYSTEM || systemAccent != null }.forEach { a ->
                    val c = a.tones().let { Color(if (darkTheme) it.dark else it.light) }
                    val label = tr(a.label)
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(c)
                            .selectable(a == accentChoice, role = Role.RadioButton) { model.save(Prefs.ACCENT, a.name); onClose() }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { if (a == accentChoice) Icon(Icons.Filled.Check, null, tint = Color.White) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Close")) } },
    )
}

@Composable
private fun <T> ClassicChoiceDialog(spec: ChoiceSpec<T>, model: SettingsModel, onClose: () -> Unit) {
    ChoiceHost(spec, model, content = { current, options, pick ->
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(spec.title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (value, label) ->
                        Text(label, Modifier.fillMaxWidth().selectable(value == current, role = Role.RadioButton) { pick(value, label); onClose() }
                            .padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
        )
    })
}

/** Currency symbol, decimals, month start day or reminder time. */
@Composable
private fun ClassicEditDialog(key: Setting<*>, model: SettingsModel, onClose: () -> Unit) {
    val edit = rememberTextEdit(model, key)
    var text by remember(key) { mutableStateOf(edit.initial) }
    val valid = edit.valid(text)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(edit.title) },
        text = {
            OutlinedTextField(text, { text = if (key == Prefs.REMINDER_TIME) it.take(5) else it }, singleLine = true, isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = if (edit.number) KeyboardType.Number else KeyboardType.Text))
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { model.saveText(key, edit, text); onClose() }) { Text(tr("Save"), color = Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
}
