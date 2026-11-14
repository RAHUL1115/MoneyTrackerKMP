package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.classic.ChoiceDialog
import app.moneytracker.activity.classic.DisclosureDialog
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.data.Prefs
import app.moneytracker.data.SmsOriginal
import app.moneytracker.data.TextSetting
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.domain.MessageRules

/** Explains why detection can be late and opens the right settings page. Shared by both layouts. */
@Composable
internal fun KeepRunningDialog(health: SmsHealth, onDismiss: () -> Unit) {
    val backgroundJobs = LocalPlatform.current.backgroundJobs
    val autostart = hasAutostartManager(health.background.maker)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Keep detection running")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Some phones stop apps from starting in the background. Bank SMS are then only read when you open MoneyTracker, or at the next background check (about every 15 minutes while the phone is in use)."))
                Text(backgroundSteps(health.background.maker), fontWeight = FontWeight.Medium)
                if (health.background.restricted) Text(tr("Android is currently limiting MoneyTracker in the background."), color = SubtleText, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { backgroundJobs.openBackgroundSettings(autostart = false); onDismiss() }) { Text(tr("App settings")) }
        },
        dismissButton = {
            Row {
                if (autostart) TextButton(onClick = { backgroundJobs.openBackgroundSettings(autostart = true); onDismiss() }) { Text(tr("Autostart")) }
                TextButton(onClick = onDismiss) { Text(tr("Close")) }
            }
        },
    )
}

/** What classic Bank SMS settings asks for: its holder's saves plus the phone's SMS controls. */
private class SmsSettingActions(
    /** Turns detection on or off through the phone; the stored switch follows what the phone allowed. */
    val setCapture: (Boolean) -> Unit,
    val setAlert: (Boolean) -> Unit,
    val setAutoSave: (Boolean) -> Unit,
    val setShowOriginal: (SmsOriginal) -> Unit,
    val setImportDays: (Int) -> Unit,
    /** Reads the inbox; the callback gets how many messages were added, or null without SMS permission. */
    val importInbox: ((Int?) -> Unit) -> Unit,
    val saveFilter: (TextSetting, String) -> Unit,
)

/** The dialog classic Bank SMS settings has open. */
private sealed interface SmsDialog {
    /** An action waiting on the SMS disclosure. */
    class Disclosure(val action: () -> Unit) : SmsDialog
    class Message(val text: String) : SmsDialog
    class EditList(val key: TextSetting, val title: String) : SmsDialog
    data object KeepRunning : SmsDialog
    data object Original : SmsDialog
    data object Days : SmsDialog
}

/** SMS settings: detection switches, block lists, the content filter and inbox import. */
@Composable
fun MessageSettingsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val messageCapture = LocalPlatform.current.messageCapture
    val scope = rememberCoroutineScope()
    val model = remember(repo) { MessageSettingsModel(repo.settingsStore, repo.captureStore, repo.io, scope) }
    val state by model.state.collectAsState()
    val health = rememberSmsHealth()
    val actions = SmsSettingActions(
        setCapture = { on -> messageCapture.setMessageCapture(on) { active -> model.put(Prefs.CAPTURE_ENABLED, active) } },
        setAlert = { model.put(Prefs.SMS_ALERT, it) },
        setAutoSave = { model.put(Prefs.SMS_AUTO_SAVE, it) },
        setShowOriginal = { model.put(Prefs.SMS_SHOW_ORIGINAL, it) },
        setImportDays = { model.put(Prefs.SMS_IMPORT_DAYS, it) },
        importInbox = { onDone -> messageCapture.importInboxMessages(state.switches.importDays, onDone) },
        saveFilter = model::saveFilter,
    )
    MessageSettingsContent(state, health, onBack = nav::pop, actions)
}

@Composable
private fun MessageSettingsContent(state: MessageSettingsState, health: SmsHealth, onBack: () -> Unit, actions: SmsSettingActions) {
    var dialog by remember { mutableStateOf<SmsDialog?>(null) }
    val capture = state.switches.captureEnabled && health.permission
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Bank SMS"), onBack)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SmsSection(state.switches, capture, health, actions) { dialog = it }
            SmsFilterRows(state.filters) { dialog = it }
            Spacer(Modifier.height(24.dp))
        }
    }
    dialog?.let { d -> SmsSettingDialog(d, state, health, actions) { dialog = null } }
}

@Composable
private fun SmsSection(v: SmsSwitches, capture: Boolean, health: SmsHealth, actions: SmsSettingActions, open: (SmsDialog) -> Unit) {
    val originalLabel = when (v.showOriginal) { SmsOriginal.OFF -> tr("Hide"); SmsOriginal.WHILE_EDITING -> tr("Show only while editing"); SmsOriginal.KEEP -> tr("Show") }
    ConfigSection(tr("SMS"))
    ConfigToggle(tr("Detect bank SMS"), tr("Suggest entries from bank messages. Read on this phone only."), capture) { on ->
        val apply = { actions.setCapture(on) }
        if (on) open(SmsDialog.Disclosure(apply)) else apply()
    }
    if (!health.permission) Text(smsPermissionSteps(), Modifier.padding(horizontal = 28.dp, vertical = 8.dp), fontSize = 12.sp, color = SubtleText)
    if (capture) SettingRow(tr("Keep detection running"), if (health.background.restricted) tr("Restricted") else tr("Check")) { open(SmsDialog.KeepRunning) }
    ConfigToggle(tr("Alert for detected messages"), null, v.alert, actions.setAlert)
    ConfigToggle(tr("Save automatically"), tr("Add the entry right away when the account is recognised. A guessed usual account, or no account, waits for your review"), v.autoSave, actions.setAutoSave)
    SettingRow(tr("Original message on entries"), originalLabel) { open(SmsDialog.Original) }
    SettingRow(tr("Import SMS from the last {0} days", v.importDays), tr("Import")) {
        val import = {
            actions.importInbox { n ->
                open(SmsDialog.Message(when (n) { null -> tr("SMS permission was not granted"); 0 -> tr("No new bank messages found"); else -> tr("Added {0} messages to Detected SMS", n) }))
            }
        }
        if (capture) import() else open(SmsDialog.Disclosure(import))
    }
    SettingRow(tr("Days to import"), "${v.importDays}") { open(SmsDialog.Days) }
}

@Composable
private fun SmsFilterRows(v: SmsFilters, open: (SmsDialog) -> Unit) {
    fun count(text: String?) = MessageRules.lines(text).size.let { if (it == 0) tr("None") else "$it" }
    ConfigSection(tr("Filters"))
    SettingRow(tr("Blocked numbers"), count(v.blockedSenders)) { open(SmsDialog.EditList(Prefs.SMS_BLOCKED_SENDERS, tr("Blocked numbers"))) }
    SettingRow(tr("Blocked words"), count(v.blockedPhrases)) { open(SmsDialog.EditList(Prefs.SMS_BLOCKED_PHRASES, tr("Blocked words"))) }
    SettingRow(tr("Content filter"), count(v.contentFilter)) { open(SmsDialog.EditList(Prefs.SMS_CONTENT_FILTER, tr("Content filter"))) }
    Text(tr("Messages from blocked numbers or containing blocked words are ignored. Content-filter words are removed " +
        "before a message is read, which helps when a bank adds text that confuses the amount or shop name."),
        Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = SubtleText)
}

@Composable
private fun SmsSettingDialog(d: SmsDialog, state: MessageSettingsState, health: SmsHealth, actions: SmsSettingActions, close: () -> Unit) {
    when (d) {
        is SmsDialog.Disclosure -> DisclosureDialog(tr("Detect bank SMS"),
            tr("MoneyTracker reads SMS from banks and card issuers on this phone to suggest income and expense entries. " +
                "Messages are processed only on your phone and are never uploaded or shared. " +
                "Other messages are ignored. You can turn this off at any time here in Bank SMS."),
            close) { close(); d.action() }
        SmsDialog.KeepRunning -> KeepRunningDialog(health, close)
        is SmsDialog.Message -> AlertDialog(onDismissRequest = close, text = { Text(d.text) },
            confirmButton = { TextButton(onClick = close) { Text(tr("OK")) } })
        SmsDialog.Original -> ChoiceDialog(tr("Original message on entries"),
            listOf(SmsOriginal.KEEP to tr("Show"), SmsOriginal.OFF to tr("Hide"), SmsOriginal.WHILE_EDITING to tr("Show only while editing")).map { (o, label) -> o.name to label },
            close) { actions.setShowOriginal(SmsOriginal.valueOf(it)); close() }
        SmsDialog.Days -> ImportDaysDialog(state.switches.importDays, close, actions.setImportDays)
        is SmsDialog.EditList -> {
            val f = state.filters
            val current = when (d.key) { Prefs.SMS_BLOCKED_SENDERS -> f.blockedSenders; Prefs.SMS_BLOCKED_PHRASES -> f.blockedPhrases; else -> f.contentFilter }
            LinesDialog(d.title, current, close) { actions.saveFilter(d.key, it) }
        }
    }
}

/** How many days back the inbox import reads (1–365); [onSave] gets a valid number only. */
@Composable
private fun ImportDaysDialog(days: Int, onClose: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(days.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Days to import")) },
        text = { OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { text.toIntOrNull()?.takeIf { it in 1..365 }?.let(onSave); onClose() }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
}

/** A block list or the content filter, one entry per line. */
@Composable
private fun LinesDialog(title: String, current: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(title) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("One per line."), fontSize = 13.sp, color = SubtleText)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text); onClose() }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
}
