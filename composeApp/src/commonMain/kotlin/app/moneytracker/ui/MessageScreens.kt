package app.moneytracker.ui

import app.moneytracker.data.SmsOriginal
import app.moneytracker.data.TextSetting
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.Prefs
import app.moneytracker.domain.MessageRules

/** Whether detection can run (SMS permission) and how the phone treats the app in the background; re-checked while shown. */
internal data class SmsHealth(val permission: Boolean, val background: BackgroundState)

@Composable
internal fun rememberSmsHealth(): SmsHealth {
    val backgroundJobs = LocalPlatform.current.backgroundJobs
    val messageCapture = LocalPlatform.current.messageCapture
    var health by remember { mutableStateOf(SmsHealth(messageCapture.messagePermissionGranted(), backgroundJobs.backgroundState())) }
    // No lifecycle hook in common code: a cheap re-check picks up changes made in the phone's settings.
    LaunchedEffect(messageCapture, backgroundJobs) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            health = SmsHealth(messageCapture.messagePermissionGranted(), backgroundJobs.backgroundState())
        }
    }
    return health
}

private val autostartMakers = listOf("vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "huawei", "honor", "letv")

/** Android 13+ blocks SMS permissions for apps installed outside a store until "restricted settings" are allowed. */
internal fun smsPermissionSteps(): String =
    tr("If Android says it's a restricted setting (common when installed from GitHub): open Settings › Apps › MoneyTracker, tap ⋮ › Allow restricted settings, then allow SMS under Permissions.")

/** Makers with their own autostart manager that the app knows how to open. */
internal fun hasAutostartManager(maker: String) = autostartMakers.any { it in maker }

/** Where this phone keeps the switches that let MoneyTracker start when an SMS arrives. */
internal fun backgroundSteps(maker: String): String = when {
    listOf("vivo", "iqoo").any { it in maker } -> tr("Settings › Battery › Background power consumption management › MoneyTracker › Allow. Then turn on MoneyTracker in Autostart (Settings › Apps › Autostart, or i Manager › App manager).")
    listOf("xiaomi", "redmi", "poco").any { it in maker } -> tr("Turn on Autostart for MoneyTracker, then set Battery saver to No restrictions (Settings › Apps › Manage apps › MoneyTracker).")
    listOf("oppo", "realme", "oneplus").any { it in maker } -> tr("Settings › Apps › MoneyTracker › Battery: allow background activity and auto launch.")
    "samsung" in maker -> tr("Settings › Battery › Background usage limits: make sure MoneyTracker isn't under Sleeping or Deep sleeping apps. In app info, set Battery to Unrestricted.")
    listOf("huawei", "honor").any { it in maker } -> tr("Settings › Battery › App launch › MoneyTracker: choose Manage manually and turn everything on.")
    else -> tr("Settings › Apps › MoneyTracker › Battery › Unrestricted.")
}

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

/** The SMS settings classic draws, read live. */
private class SmsSettingValues(
    val alert: Boolean,
    val autoSave: Boolean,
    val showOriginal: SmsOriginal,
    val blockedSenders: String,
    val blockedPhrases: String,
    val contentFilter: String,
    val days: Int,
)

@Composable
private fun rememberSmsSettingValues() = SmsSettingValues(
    alert = rememberSetting(Prefs.SMS_ALERT).value,
    autoSave = rememberSetting(Prefs.SMS_AUTO_SAVE).value,
    showOriginal = rememberSetting(Prefs.SMS_SHOW_ORIGINAL).value,
    blockedSenders = rememberSetting(Prefs.SMS_BLOCKED_SENDERS).value,
    blockedPhrases = rememberSetting(Prefs.SMS_BLOCKED_PHRASES).value,
    contentFilter = rememberSetting(Prefs.SMS_CONTENT_FILTER).value,
    days = rememberSetting(Prefs.SMS_IMPORT_DAYS).value,
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
    val nav = LocalNavigator.current
    val model = rememberSettingsModel()
    val v = rememberSmsSettingValues()
    val captureSetting by rememberSetting(Prefs.CAPTURE_ENABLED)
    var dialog by remember { mutableStateOf<SmsDialog?>(null) }
    val health = rememberSmsHealth()
    val capture = captureSetting && health.permission

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Bank SMS"), nav::pop)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SmsSection(v, capture, health, model) { dialog = it }
            SmsFilters(v) { dialog = it }
            Spacer(Modifier.height(24.dp))
        }
    }
    dialog?.let { d -> SmsSettingDialog(d, v, health, model) { dialog = null } }
}

@Composable
private fun SmsSection(v: SmsSettingValues, capture: Boolean, health: SmsHealth, model: SettingsModel, open: (SmsDialog) -> Unit) {
    val messageCapture = LocalPlatform.current.messageCapture
    val originalLabel = when (v.showOriginal) { SmsOriginal.OFF -> tr("Hide"); SmsOriginal.WHILE_EDITING -> tr("Show only while editing"); SmsOriginal.KEEP -> tr("Show") }
    ConfigSection(tr("SMS"))
    ConfigToggle(tr("Detect bank SMS"), tr("Suggest entries from bank messages. Read on this phone only."), capture) { on ->
        val apply = { messageCapture.setMessageCapture(on) { active -> model.save(Prefs.CAPTURE_ENABLED, active) } }
        if (on) open(SmsDialog.Disclosure(apply)) else apply()
    }
    if (!health.permission) Text(smsPermissionSteps(), Modifier.padding(horizontal = 28.dp, vertical = 8.dp), fontSize = 12.sp, color = SubtleText)
    if (capture) ConfigRow(tr("Keep detection running"), if (health.background.restricted) tr("Restricted") else tr("Check")) { open(SmsDialog.KeepRunning) }
    ConfigToggle(tr("Alert for detected messages"), null, v.alert) { model.save(Prefs.SMS_ALERT, it) }
    ConfigToggle(tr("Save automatically"), tr("Add the entry right away when the account is recognised. A guessed usual account, or no account, waits for your review"), v.autoSave) {
        model.save(Prefs.SMS_AUTO_SAVE, it)
    }
    ConfigRow(tr("Original message on entries"), originalLabel) { open(SmsDialog.Original) }
    ConfigRow(tr("Import SMS from the last {0} days", v.days), tr("Import")) {
        val import = {
            messageCapture.importInboxMessages(v.days) { n ->
                open(SmsDialog.Message(when (n) { null -> tr("SMS permission was not granted"); 0 -> tr("No new bank messages found"); else -> tr("Added {0} messages to Detected SMS", n) }))
            }
        }
        if (capture) import() else open(SmsDialog.Disclosure(import))
    }
    ConfigRow(tr("Days to import"), "${v.days}") { open(SmsDialog.Days) }
}

@Composable
private fun SmsFilters(v: SmsSettingValues, open: (SmsDialog) -> Unit) {
    fun count(text: String?) = MessageRules.lines(text).size.let { if (it == 0) tr("None") else "$it" }
    ConfigSection(tr("Filters"))
    ConfigRow(tr("Blocked numbers"), count(v.blockedSenders)) { open(SmsDialog.EditList(Prefs.SMS_BLOCKED_SENDERS, tr("Blocked numbers"))) }
    ConfigRow(tr("Blocked words"), count(v.blockedPhrases)) { open(SmsDialog.EditList(Prefs.SMS_BLOCKED_PHRASES, tr("Blocked words"))) }
    ConfigRow(tr("Content filter"), count(v.contentFilter)) { open(SmsDialog.EditList(Prefs.SMS_CONTENT_FILTER, tr("Content filter"))) }
    Text(tr("Messages from blocked numbers or containing blocked words are ignored. Content-filter words are removed " +
        "before a message is read, which helps when a bank adds text that confuses the amount or shop name."),
        Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = SubtleText)
}

@Composable
private fun SmsSettingDialog(d: SmsDialog, v: SmsSettingValues, health: SmsHealth, model: SettingsModel, close: () -> Unit) {
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
            close) { model.save(Prefs.SMS_SHOW_ORIGINAL, SmsOriginal.valueOf(it)); close() }
        SmsDialog.Days -> ImportDaysDialog(v.days, close) { model.save(Prefs.SMS_IMPORT_DAYS, it) }
        is SmsDialog.EditList -> {
            val current = when (d.key) { Prefs.SMS_BLOCKED_SENDERS -> v.blockedSenders; Prefs.SMS_BLOCKED_PHRASES -> v.blockedPhrases; else -> v.contentFilter }
            LinesDialog(d.title, current, close) { model.save(d.key, MessageRules.lines(it).joinToString("\n")) }
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
