package app.moneytracker.messages.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.tr
import app.moneytracker.data.Prefs
import app.moneytracker.data.SmsOriginal
import app.moneytracker.data.TextSetting
import app.moneytracker.designsystem.fresh.Confirm
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.FRow
import app.moneytracker.designsystem.fresh.FSwitch
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.SecondaryButton
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SubScreen
import app.moneytracker.domain.MessageRules
import app.moneytracker.messages.MessageSettingsModel
import app.moneytracker.messages.MessageSettingsState
import app.moneytracker.messages.SmsFilters
import app.moneytracker.messages.SmsHealth
import app.moneytracker.messages.SmsSwitches
import app.moneytracker.messages.backgroundSteps
import app.moneytracker.messages.hasAutostartManager
import app.moneytracker.messages.rememberSmsHealth
import app.moneytracker.messages.smsPermissionSteps
import app.moneytracker.settings.classic.expandable

/** What the Bank SMS settings screen asks for: its holder's settings plus the phone's SMS and background controls. */
private class SmsSettingsActions(
    /** Opens the review queue or the accounts (for their matching rules). */
    val open: (Screen) -> Unit,
    /** Turns detection on or off through the phone; the stored switch follows what the phone allowed. */
    val setCapture: (Boolean) -> Unit,
    val setAlert: (Boolean) -> Unit,
    val setAutoSave: (Boolean) -> Unit,
    val setShowOriginal: (SmsOriginal) -> Unit,
    val setImportDays: (Int) -> Unit,
    val importInbox: () -> Unit,
    /** Saves one filter list; the label names it in the confirmation. */
    val saveFilter: (TextSetting, String, String) -> Unit,
    val openBackgroundSettings: (autostart: Boolean) -> Unit,
)

/** Bank SMS settings: detection switches, how the original message is kept, inbox import and the filters. */
@Composable
internal fun SmsSettingsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val backgroundJobs = LocalPlatform.current.backgroundJobs
    val messageCapture = LocalPlatform.current.messageCapture
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) { MessageSettingsModel(repo.settingsStore, repo.captureStore, repo.io, scope) }
    val state by model.state.collectAsState()
    val health = rememberSmsHealth()
    val capture = state.switches.captureEnabled && health.permission
    val days = state.switches.importDays
    val actions = SmsSettingsActions(
        open = nav::push,
        setCapture = { on -> messageCapture.setMessageCapture(on) { active -> model.put(Prefs.CAPTURE_ENABLED, active) } },
        setAlert = { on -> if (!capture) toaster.show(tr("Turn on “Detect bank SMS” first")) else model.put(Prefs.SMS_ALERT, on) },
        setAutoSave = { model.put(Prefs.SMS_AUTO_SAVE, it) },
        setShowOriginal = { model.put(Prefs.SMS_SHOW_ORIGINAL, it) },
        setImportDays = { model.put(Prefs.SMS_IMPORT_DAYS, it) },
        importInbox = {
            messageCapture.importInboxMessages(days) { n ->
                when (n) {
                    null -> toaster.show(tr("SMS permission was not granted"))
                    0 -> toaster.show(tr("No new bank messages found"))
                    else -> toaster.show(tr("Added {0} messages from the last {1} days", n, days))
                }
            }
        },
        saveFilter = { key, text, label -> model.saveFilter(key, text); toaster.show(tr("{0} saved", label)) },
        openBackgroundSettings = { autostart -> backgroundJobs.openBackgroundSettings(autostart) },
    )
    SmsSettingsContent(state, health, capture, onBack = nav::pop, actions)
}

@Composable
private fun SmsSettingsContent(state: MessageSettingsState, health: SmsHealth, capture: Boolean, onBack: () -> Unit, actions: SmsSettingsActions) {
    var disclosure by remember { mutableStateOf<(() -> Unit)?>(null) }   // action waiting on the SMS disclosure
    var customDays by remember { mutableStateOf(false) }
    var keepRunning by remember { mutableStateOf(false) }

    SubScreen(tr("Bank SMS"), onBack) {
        ReviewLinkCard(state.waiting) { actions.open(Screen.Inbox) }
        FCard {
            Text(tr("Bank messages are read on this phone only. Nothing is uploaded. Detection is an assistant, so check suggestions before saving."),
                color = F.ink, fontSize = 13.sp, lineHeight = 19.sp)
        }
        SwitchesCard(
            state.switches, health, capture, actions,
            onCapture = { on -> val apply = { actions.setCapture(on) }; if (on) disclosure = apply else apply() },
            onKeepRunning = { keepRunning = true },
        )
        ImportCard(state.switches.importDays, actions.setImportDays, onCustom = { customDays = true },
            onImport = { if (capture) actions.importInbox() else disclosure = actions.importInbox })
        FiltersCard(state.filters, actions)
        Text(tr("Content-filter words are removed before a message is read, which helps when a bank adds text that confuses the amount or shop name."),
            color = F.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }

    disclosure?.let { action ->
        Confirm(tr("Detect bank SMS"), smsDisclosureText(), tr("Continue"), onDismiss = { disclosure = null }, danger = false) { action() }
    }
    if (keepRunning) KeepRunningSheet(health, onDismiss = { keepRunning = false }) { autostart -> actions.openBackgroundSettings(autostart); keepRunning = false }
    if (customDays) CustomDaysSheet(state.switches.importDays, onDismiss = { customDays = false }) { actions.setImportDays(it); customDays = false }
}

@Composable
private fun ReviewLinkCard(waiting: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable(onClick = onOpen).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(F.controlShape).background(F.accentTint), contentAlignment = Alignment.Center) {
            Icon(Glyph.Message, null, tint = F.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(tr("Detected messages"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(if (waiting == 0) tr("Nothing waiting") else tr("{0} waiting for review", waiting), color = F.muted, fontSize = 12.sp)
        }
        Text(tr("Review ›"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SwitchesCard(
    switches: SmsSwitches, health: SmsHealth, capture: Boolean, actions: SmsSettingsActions, onCapture: (Boolean) -> Unit, onKeepRunning: () -> Unit,
) {
    FListCard {
        SwitchLine(tr("Detect bank SMS"), tr("Suggest entries from bank messages. Read on this phone only."), capture, extraTop = CARD_EDGE, onChange = onCapture)
        if (!health.permission) {
            Text(smsPermissionSteps(), color = F.muted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 12.dp))
        }
        if (capture) {
            Divider(inset = ROW_INSET)
            HealthLine(tr("Keep detection running"),
                if (health.background.restricted) tr("This phone is limiting MoneyTracker in the background") else tr("Let MoneyTracker start when an SMS arrives"),
                tr("Check ›"), warn = health.background.restricted, onClick = onKeepRunning)
        }
        Divider(inset = ROW_INSET)
        SwitchLine(tr("Alert for detected messages"), tr("A notification when a bank message is detected"), switches.alert, dimmed = !capture, onChange = actions.setAlert)
        Divider(inset = ROW_INSET)
        SwitchLine(tr("Save automatically"), tr("Add the entry right away when the account is recognised. A guessed usual account, or no account, waits for your review"),
            switches.autoSave, onChange = actions.setAutoSave)
        Divider(inset = ROW_INSET)
        OriginalMessageLine(switches.showOriginal, actions.setShowOriginal)
    }
}

/** How a message's text is kept on its entry: a compact choice on the right of its row, like a switch would sit. */
@Composable
private fun OriginalMessageLine(showOriginal: SmsOriginal, onPick: (SmsOriginal) -> Unit) {
    val origHint = when (showOriginal) {
        SmsOriginal.OFF -> tr("Not kept on entries"); SmsOriginal.WHILE_EDITING -> tr("Shown only while editing an entry"); SmsOriginal.KEEP -> tr("Always shown in entry details")
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = CARD_EDGE + 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(tr("Original message on entries"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(origHint, color = F.muted, fontSize = 12.sp)
        }
        val opts = listOf(SmsOriginal.OFF to tr("Hide"), SmsOriginal.WHILE_EDITING to tr("While editing"), SmsOriginal.KEEP to tr("Show"))
        val selected = opts.indexOfFirst { it.first == showOriginal }.coerceAtLeast(0)
        SmsSegmented(opts.map { it.second }, selected, { onPick(opts[it].first) },
            segmentHeight = 36.dp, outer = 10.dp, inner = 8.dp, inset = 3.dp, track = F.page, fill = false)
    }
}

@Composable
private fun ImportCard(days: Int, onDays: (Int) -> Unit, onCustom: () -> Unit, onImport: () -> Unit) {
    FCard {
        Text(tr("Import older messages"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(tr("Reads bank messages already in your SMS inbox into Detected messages. Pick how many days back."),
            Modifier.padding(top = 3.dp), color = F.muted, fontSize = 12.sp)
        val presets = listOf(7, 30, 90, 180)
        val opts = presets.map { tr("{0} days", it) } + (if (days in presets) tr("Other") else tr("{0} days", days))
        val sel = presets.indexOf(days).let { if (it < 0) presets.size else it }
        SmsSegmented(opts, sel, { i -> if (i < presets.size) onDays(presets[i]) else onCustom() },
            Modifier.padding(top = 14.dp), segmentHeight = 40.dp, outer = 12.dp, inner = 9.dp, inset = 4.dp, track = F.track, fill = true)
        PrimaryButton(tr("Import messages"), onImport, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun FiltersCard(filters: SmsFilters, actions: SmsSettingsActions) {
    var editing by remember { mutableStateOf<TextSetting?>(null) }
    FListCard {
        Text(tr("Filters").uppercase(), Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = CARD_EDGE + 12.dp, bottom = 2.dp), color = F.muted, fontSize = 12.sp,
            fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
        listOf(
            Triple(Prefs.SMS_BLOCKED_SENDERS, tr("Blocked numbers"), tr("Messages from these senders are ignored")) to filters.blockedSenders,
            Triple(Prefs.SMS_BLOCKED_PHRASES, tr("Blocked words"), tr("Messages containing these are ignored")) to filters.blockedPhrases,
            Triple(Prefs.SMS_CONTENT_FILTER, tr("Content filter"), tr("Removed from a message before it is read")) to filters.contentFilter,
        ).forEach { (def, value) ->
            val (key, label, hint) = def
            FilterLine(key, label, hint, value, open = editing == key, onToggle = { editing = if (editing == key) null else key }) { text ->
                editing = null
                actions.saveFilter(key, text, label)
            }
            Divider(inset = ROW_INSET)
        }
        FRow(tr("Account matching rules"), tr("Sender and card digits, set on each account"), value = tr("Per account") + " ›", valueColor = F.accent,
            chevron = false, onClick = { actions.open(Screen.AccountList) }, inset = ROW_INSET, extraBottom = CARD_EDGE)
    }
}

@Composable
private fun KeepRunningSheet(health: SmsHealth, onDismiss: () -> Unit, onOpen: (autostart: Boolean) -> Unit) {
    val autostart = hasAutostartManager(health.background.maker)
    Sheet(tr("Keep detection running"), onDismiss, bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (autostart) PrimaryButton(tr("Open Autostart"), { onOpen(true) }, Modifier.fillMaxWidth())
            SecondaryButton(tr("Open app settings"), { onOpen(false) }, Modifier.fillMaxWidth())
        }
    }) {
        Text(tr("Some phones stop apps from starting in the background. Bank SMS are then only read when you open MoneyTracker, or at the next background check (about every 15 minutes while the phone is in use)."),
            color = F.muted, fontSize = 13.sp, lineHeight = 19.sp)
        Text(backgroundSteps(health.background.maker), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    }
}

@Composable
private fun CustomDaysSheet(days: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(days.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 1..365 }
    Sheet(tr("Days to import"), onDismiss, bottom = {
        PrimaryButton(tr("Save"), { if (value != null) onSave(value) else onDismiss() }, enabled = value != null)
    }) {
        Text(tr("How far back to read the SMS inbox (1 to 365 days)."), color = F.muted, fontSize = 13.sp)
        BasicTextField(
            text, { text = it.filter(Char::isDigit).take(3) },
            Modifier.fillMaxWidth().clip(F.controlShape).border(1.dp, F.border, F.controlShape).padding(horizontal = 12.dp, vertical = 12.dp),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(F.accent),
        )
    }
}

/** This screen's rows start 18dp in from their card's edge. */
private val ROW_INSET = 18.dp

/** The cards' top and bottom spacing, kept inside the first and last rows so their highlight reaches the rounded edge. */
private val CARD_EDGE = 4.dp

/**
 * A switch row: the whole row is the switch. A [dimmed] row still takes taps (its action explains why it can't
 * change yet). [extraTop] is the card's top spacing when this is the card's first row.
 */
@Composable
private fun SwitchLine(label: String, hint: String?, checked: Boolean, dimmed: Boolean = false, extraTop: Dp = 0.dp, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (dimmed) 0.45f else 1f).toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(top = extraTop).heightIn(min = 64.dp).padding(horizontal = ROW_INSET, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, color = F.muted, fontSize = 12.sp)
        }
        FSwitch(checked, null)
    }
}

/** A status line inside the switches card: label, hint and a small action, amber when something needs fixing. */
@Composable
private fun HealthLine(label: String, hint: String, action: String, warn: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = ROW_INSET, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = if (warn) F.warn else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(hint, color = F.muted, fontSize = 12.sp)
        }
        Text(action, color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FilterLine(key: TextSetting, label: String, hint: String, value: String?, open: Boolean, onToggle: () -> Unit, onSave: (String) -> Unit) {
    val items = MessageRules.lines(value)
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).expandable(open, onToggle).heightIn(min = 60.dp).padding(horizontal = ROW_INSET, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(hint, color = F.muted, fontSize = 12.sp)
            }
            val summary = when {
                open -> tr("Close")
                items.isEmpty() -> tr("None ›")
                items.size > 2 -> tr("{0} items ›", items.size)
                else -> items.joinToString(", ") + " ›"
            }
            Text(summary, Modifier.widthIn(max = 130.dp), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) FilterEditor(key, value, onSave)
    }
}

/** The open filter: one entry per line, saved with its own button. */
@Composable
private fun FilterEditor(key: TextSetting, value: String?, onSave: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(value.orEmpty()) }
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmsTextArea(text, { text = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("One per line."), Modifier.weight(1f), color = F.muted, fontSize = 12.sp)
            Box(Modifier.height(44.dp).clip(F.innerShape).background(F.accent).clickable(role = Role.Button) { onSave(text) }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) {
                Text(tr("Save"), color = F.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The kit's segmented control at this screen's two sizes: full width with equal segments ([fill]),
 * or compact with segments sized to their labels.
 */
@Composable
private fun SmsSegmented(
    options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    segmentHeight: Dp, outer: Dp, inner: Dp, inset: Dp, track: Color, fill: Boolean,
) {
    Row(
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).clip(RoundedCornerShape(outer)).background(track).padding(inset).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // One of several, read as tabs like the kit's Segmented.
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.then(if (fill) Modifier.weight(1f) else Modifier).height(segmentHeight).clip(RoundedCornerShape(inner))
                    .background(if (on) F.card else Color.Transparent).selectable(on, role = Role.Tab) { onSelect(i) }
                    .then(if (fill) Modifier else Modifier.padding(horizontal = 10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) F.ink else F.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) }
        }
    }
}
