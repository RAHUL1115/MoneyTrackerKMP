package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.domain.MessageRules
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SMS settings: detection switches, block lists, the content filter and inbox import. */
@Composable
fun MessageSettingsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    @Composable fun opt(key: String) = remember(repo, key) { repo.setting(key) }
    val capture by remember(repo) { repo.flag(Keys.CAPTURE_ENABLED) }.collectAsState(false)
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
                "Messages are processed only on your phone and are never uploaded or shared: the app has no internet access. " +
                "Other messages are ignored. You can turn this off at any time here in Bank SMS."),
            { disclosure = null }) { disclosure = null; action() }
    }
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
