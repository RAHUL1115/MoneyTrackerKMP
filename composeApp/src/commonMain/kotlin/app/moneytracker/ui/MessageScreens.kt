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
import app.moneytracker.ui.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.domain.MessageRules
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether detection can run (SMS permission) and how the phone treats the app in the background; re-checked while shown. */
internal data class SmsHealth(val permission: Boolean, val background: BackgroundState)

@Composable
internal fun rememberSmsHealth(): SmsHealth {
    val platform = LocalPlatform.current
    var health by remember { mutableStateOf(SmsHealth(platform.messagePermissionGranted(), platform.backgroundState())) }
    // No lifecycle hook in common code: a cheap re-check picks up changes made in the phone's settings.
    LaunchedEffect(platform) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            health = SmsHealth(platform.messagePermissionGranted(), platform.backgroundState())
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
    val platform = LocalPlatform.current
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
            TextButton(onClick = { platform.openBackgroundSettings(autostart = false); onDismiss() }) { Text(tr("App settings")) }
        },
        dismissButton = {
            Row {
                if (autostart) TextButton(onClick = { platform.openBackgroundSettings(autostart = true); onDismiss() }) { Text(tr("Autostart")) }
                TextButton(onClick = onDismiss) { Text(tr("Close")) }
            }
        },
    )
}

/** SMS settings: detection switches, block lists, the content filter and inbox import. */
@Composable
fun MessageSettingsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    @Composable fun opt(key: String) = remember(repo, key) { repo.setting(key) }
    val captureSetting by remember(repo) { repo.flag(Keys.CAPTURE_ENABLED) }.collectAsState(false)
    val alert by opt(Keys.SMS_ALERT).collectAsState(null)
    val autoSave by opt(Keys.SMS_AUTO_SAVE).collectAsState(null)
    val showOriginal by opt(Keys.SMS_SHOW_ORIGINAL).collectAsState(null)
    val blockedSenders by opt(Keys.SMS_BLOCKED_SENDERS).collectAsState(null)
    val blockedPhrases by opt(Keys.SMS_BLOCKED_PHRASES).collectAsState(null)
    val contentFilter by opt(Keys.SMS_CONTENT_FILTER).collectAsState(null)
    val importDays by opt(Keys.SMS_IMPORT_DAYS).collectAsState(null)
    var listEditing by remember { mutableStateOf<Pair<String, String>?>(null) }   // key to title
    var choosingOriginal by remember { mutableStateOf(false) }
    var daysDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var disclosure by remember { mutableStateOf<(() -> Unit)?>(null) }   // action waiting on the SMS disclosure
    var keepRunning by remember { mutableStateOf(false) }
    val health = rememberSmsHealth()
    val capture = captureSetting && health.permission

    fun put(key: String, value: String) = scope.launch { withContext(repo.io) { repo.putSetting(key, value) } }
    val days = importDays?.toIntOrNull() ?: 30
    val originalLabel = when (showOriginal) { "0" -> tr("Hide"); "2" -> tr("Show only while editing"); else -> tr("Show") }
    fun count(v: String?) = MessageRules.lines(v).size.let { if (it == 0) tr("None") else "$it" }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Bank SMS"), nav::pop)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ConfigSection(tr("SMS"))
            ConfigToggle(tr("Detect bank SMS"), tr("Suggest entries from bank messages. Read on this phone only."), capture) { on ->
                val apply = { platform.setMessageCapture(on) { active -> put(Keys.CAPTURE_ENABLED, if (active) "1" else "0") } }
                if (on) disclosure = apply else apply()
            }
            if (!health.permission) Text(smsPermissionSteps(), Modifier.padding(horizontal = 28.dp, vertical = 8.dp), fontSize = 12.sp, color = SubtleText)
            if (capture) ConfigRow(tr("Keep detection running"), if (health.background.restricted) tr("Restricted") else tr("Check")) { keepRunning = true }
            ConfigToggle(tr("Alert for detected messages"), null, alert != "0") { put(Keys.SMS_ALERT, if (it) "1" else "0") }
            ConfigToggle(tr("Save automatically"), tr("Add the entry right away when the account is recognised"), autoSave == "1") {
                put(Keys.SMS_AUTO_SAVE, if (it) "1" else "0")
            }
            ConfigRow(tr("Original message on entries"), originalLabel) { choosingOriginal = true }
            ConfigRow(tr("Import SMS from the last {0} days", days), tr("Import")) {
                val import = {
                    platform.importInboxMessages(days) { n ->
                        message = when (n) { null -> tr("SMS permission was not granted"); 0 -> tr("No new bank messages found"); else -> tr("Added {0} messages to Detected SMS", n) }
                    }
                }
                if (capture) import() else disclosure = import
            }
            ConfigRow(tr("Days to import"), "$days") { daysDialog = true }

            ConfigSection(tr("Filters"))
            ConfigRow(tr("Blocked numbers"), count(blockedSenders)) { listEditing = Keys.SMS_BLOCKED_SENDERS to tr("Blocked numbers") }
            ConfigRow(tr("Blocked words"), count(blockedPhrases)) { listEditing = Keys.SMS_BLOCKED_PHRASES to tr("Blocked words") }
            ConfigRow(tr("Content filter"), count(contentFilter)) { listEditing = Keys.SMS_CONTENT_FILTER to tr("Content filter") }
            Text(tr("Messages from blocked numbers or containing blocked words are ignored. Content-filter words are removed " +
                "before a message is read, which helps when a bank adds text that confuses the amount or shop name."),
                Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = SubtleText)

            Spacer(Modifier.height(24.dp))
        }
    }

    disclosure?.let { action ->
        DisclosureDialog(tr("Detect bank SMS"),
            tr("MoneyTracker reads SMS from banks and card issuers on this phone to suggest income and expense entries. " +
                "Messages are processed only on your phone and are never uploaded or shared. " +
                "Other messages are ignored. You can turn this off at any time here in Bank SMS."),
            { disclosure = null }) { disclosure = null; action() }
    }
    if (keepRunning) KeepRunningDialog(health) { keepRunning = false }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(tr("OK")) } })
    }
    if (choosingOriginal) {
        ChoiceDialog(tr("Original message on entries"), listOf("1" to tr("Show"), "0" to tr("Hide"), "2" to tr("Show only while editing")),
            { choosingOriginal = false }) { put(Keys.SMS_SHOW_ORIGINAL, it); choosingOriginal = false }
    }
    if (daysDialog) {
        var text by remember { mutableStateOf(days.toString()) }
        AlertDialog(
            onDismissRequest = { daysDialog = false },
            title = { Text(tr("Days to import")) },
            text = { OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { text.toIntOrNull()?.takeIf { it in 1..365 }?.let { put(Keys.SMS_IMPORT_DAYS, "$it") }; daysDialog = false }) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { daysDialog = false }) { Text(tr("Cancel")) } },
        )
    }
    listEditing?.let { (key, title) ->
        val current = when (key) { Keys.SMS_BLOCKED_SENDERS -> blockedSenders; Keys.SMS_BLOCKED_PHRASES -> blockedPhrases; else -> contentFilter }
        var text by remember(key) { mutableStateOf(current.orEmpty()) }
        AlertDialog(
            onDismissRequest = { listEditing = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("One per line."), fontSize = 13.sp, color = SubtleText)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp))
                }
            },
            confirmButton = { TextButton(onClick = { put(key, MessageRules.lines(text).joinToString("\n")); listEditing = null }) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { listEditing = null }) { Text(tr("Cancel")) } },
        )
    }
}
