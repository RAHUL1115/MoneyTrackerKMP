package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.domain.MessageRules
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bank SMS settings: detection switches, how the original message is kept, inbox import and the filters. */
@Composable
internal fun SmsSettingsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    @Composable fun opt(key: String) = remember(repo, key) { repo.setting(key) }
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val capture by remember(repo) { repo.flag(Keys.CAPTURE_ENABLED) }.collectAsState(false)
    val alert by opt(Keys.SMS_ALERT).collectAsState(null)
    val autoSave by opt(Keys.SMS_AUTO_SAVE).collectAsState(null)
    val showOriginal by opt(Keys.SMS_SHOW_ORIGINAL).collectAsState(null)
    val blockedSenders by opt(Keys.SMS_BLOCKED_SENDERS).collectAsState(null)
    val blockedPhrases by opt(Keys.SMS_BLOCKED_PHRASES).collectAsState(null)
    val contentFilter by opt(Keys.SMS_CONTENT_FILTER).collectAsState(null)
    val importDays by opt(Keys.SMS_IMPORT_DAYS).collectAsState(null)
    var disclosure by remember { mutableStateOf<(() -> Unit)?>(null) }   // action waiting on the SMS disclosure
    var editing by remember { mutableStateOf<String?>(null) }
    var customDays by remember { mutableStateOf(false) }

    fun put(key: String, value: String) = scope.launch { withContext(repo.io) { repo.putSetting(key, value) } }
    val days = importDays?.toIntOrNull() ?: 30
    fun import() = platform.importInboxMessages(days) { n ->
        when (n) {
            null -> toaster.show(tr("SMS permission was not granted"))
            0 -> toaster.show(tr("No new bank messages found"))
            else -> toaster.show(tr("Added {0} messages from the last {1} days", n, days))
        }
    }

    SubScreen(tr("Bank SMS"), nav::pop) {
        Row(
            Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable { nav.push(Screen.Inbox) }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Mark(tr("Detected messages"), 38.dp, icon = Glyph.Message)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tr("Detected messages"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (pending.isEmpty()) tr("Nothing waiting") else tr("{0} waiting for review", pending.size), color = F.muted, fontSize = 12.sp)
            }
            Text(tr("Review ›"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        FCard {
            Text(tr("Bank messages are read on this phone only. Nothing is uploaded. Detection is an assistant, so check suggestions before saving."),
                color = F.ink, fontSize = 13.sp, lineHeight = 19.sp)
        }

        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                SwitchLine(tr("Detect bank SMS"), tr("Suggest entries from bank messages. Read on this phone only."), capture) { on ->
                    val apply = { platform.setMessageCapture(on) { active -> put(Keys.CAPTURE_ENABLED, if (active) "1" else "0") } }
                    if (on) disclosure = apply else apply()
                }
                Divider()
                SwitchLine(tr("Alert for detected messages"), tr("A notification when a bank message is detected"), alert != "0", dimmed = !capture) { on ->
                    if (!capture) toaster.show(tr("Turn on “Detect bank SMS” first")) else put(Keys.SMS_ALERT, if (on) "1" else "0")
                }
                Divider()
                SwitchLine(tr("Save automatically"), tr("Add the entry right away when the account is recognised"), autoSave == "1") { on ->
                    put(Keys.SMS_AUTO_SAVE, if (on) "1" else "0")
                }
                Divider()
                val origHint = when (showOriginal) {
                    "0" -> tr("Not kept on entries"); "2" -> tr("Shown only while editing an entry"); else -> tr("Always shown in entry details")
                }
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Original message on entries"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(origHint, Modifier.padding(bottom = 6.dp), color = F.muted, fontSize = 12.sp)
                    val opts = listOf("0" to tr("Hide"), "2" to tr("While editing"), "1" to tr("Show"))
                    val selected = opts.indexOfFirst { it.first == (showOriginal ?: "1") }.coerceAtLeast(0)
                    Segmented(opts.map { it.second }, selected, { put(Keys.SMS_SHOW_ORIGINAL, opts[it].first) }, Modifier.height(48.dp))
                }
            }
        }

        FCard {
            Text(tr("Import older messages"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(tr("Reads bank messages already in your SMS inbox into Detected messages. Pick how many days back."),
                Modifier.padding(top = 3.dp), color = F.muted, fontSize = 12.sp)
            val presets = listOf(7, 30, 90, 180)
            val opts = presets.map { tr("{0} days", it) } + (if (days in presets) tr("Other") else tr("{0} days", days))
            val sel = presets.indexOf(days).let { if (it < 0) presets.size else it }
            Segmented(opts, sel, { i -> if (i < presets.size) put(Keys.SMS_IMPORT_DAYS, "${presets[i]}") else customDays = true }, Modifier.padding(top = 14.dp).height(48.dp))
            PrimaryButton(tr("Import messages"), { if (capture) import() else disclosure = { import() } }, Modifier.padding(top = 12.dp))
        }

        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                Text(tr("Filters").uppercase(), Modifier.padding(top = 12.dp, bottom = 2.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
                listOf(
                    Triple(Keys.SMS_BLOCKED_SENDERS, tr("Blocked numbers"), tr("Messages from these senders are ignored")) to blockedSenders,
                    Triple(Keys.SMS_BLOCKED_PHRASES, tr("Blocked words"), tr("Messages containing these are ignored")) to blockedPhrases,
                    Triple(Keys.SMS_CONTENT_FILTER, tr("Content filter"), tr("Removed from a message before it is read")) to contentFilter,
                ).forEach { (def, value) ->
                    val (key, label, hint) = def
                    FilterLine(key, label, hint, value, open = editing == key, onToggle = { editing = if (editing == key) null else key }) { text ->
                        put(key, MessageRules.lines(text).joinToString("\n")); editing = null; toaster.show(tr("{0} saved", label))
                    }
                    Divider()
                }
                FRow(tr("Account matching rules"), tr("Sender and card digits, set on each account"), value = tr("Per account"), onClick = { nav.push(Screen.AccountList) })
            }
        }
        Text(tr("Content-filter words are removed before a message is read, which helps when a bank adds text that confuses the amount or shop name."),
            color = F.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }

    disclosure?.let { action ->
        Confirm(tr("Detect bank SMS"), smsDisclosureText(), tr("Continue"), onDismiss = { disclosure = null }, danger = false) { action() }
    }
    if (customDays) {
        var text by remember { mutableStateOf(days.toString()) }
        val value = text.toIntOrNull()?.takeIf { it in 1..365 }
        Sheet(tr("Days to import"), { customDays = false }, bottom = {
            PrimaryButton(tr("Save"), { value?.let { put(Keys.SMS_IMPORT_DAYS, "$it") }; customDays = false }, enabled = value != null)
        }) {
            Text(tr("How far back to read the SMS inbox (1 to 365 days)."), color = F.muted, fontSize = 13.sp)
            BasicTextField(
                text, { text = it.filter(Char::isDigit).take(3) },
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, F.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium), cursorBrush = SolidColor(F.accent),
            )
        }
    }
}

@Composable
private fun SwitchLine(label: String, hint: String?, checked: Boolean, dimmed: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (dimmed) 0.45f else 1f).clickable { onChange(!checked) }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, color = F.muted, fontSize = 12.sp)
        }
        SmsSwitch(checked)
    }
}

/** Switch drawn in the kit's colours (the row handles the tap). */
@Composable
internal fun SmsSwitch(checked: Boolean) {
    Box(Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(999.dp)).background(if (checked) F.accent else F.border)) {
        Box(Modifier.offset(x = if (checked) 21.dp else 3.dp, y = 3.dp).size(22.dp).clip(CircleShape).background(if (checked) F.onAccent else F.card))
    }
}

@Composable
private fun FilterLine(key: String, label: String, hint: String, value: String?, open: Boolean, onToggle: () -> Unit, onSave: (String) -> Unit) {
    val items = MessageRules.lines(value)
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onToggle).padding(vertical = 12.dp),
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
        if (open) {
            var text by remember(key) { mutableStateOf(value.orEmpty()) }
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmsTextArea(text, { text = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("One per line."), Modifier.weight(1f), color = F.muted, fontSize = 12.sp)
                    Text(tr("Save"), Modifier.clip(RoundedCornerShape(10.dp)).background(F.accent).clickable { onSave(text) }.padding(horizontal = 14.dp, vertical = 9.dp),
                        color = F.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
