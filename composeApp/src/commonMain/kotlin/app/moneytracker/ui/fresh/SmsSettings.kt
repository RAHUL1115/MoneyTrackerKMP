package app.moneytracker.ui.fresh

import app.moneytracker.ui.saveSetting
import app.moneytracker.ui.rememberSetting
import app.moneytracker.data.SmsOriginal
import app.moneytracker.data.Setting
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.Prefs
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
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val captureSetting by rememberSetting(Prefs.CAPTURE_ENABLED)
    val alert by rememberSetting(Prefs.SMS_ALERT)
    val autoSave by rememberSetting(Prefs.SMS_AUTO_SAVE)
    val showOriginal by rememberSetting(Prefs.SMS_SHOW_ORIGINAL)
    val blockedSenders by rememberSetting(Prefs.SMS_BLOCKED_SENDERS)
    val blockedPhrases by rememberSetting(Prefs.SMS_BLOCKED_PHRASES)
    val contentFilter by rememberSetting(Prefs.SMS_CONTENT_FILTER)
    val days by rememberSetting(Prefs.SMS_IMPORT_DAYS)
    var disclosure by remember { mutableStateOf<(() -> Unit)?>(null) }   // action waiting on the SMS disclosure
    var editing by remember { mutableStateOf<app.moneytracker.data.TextSetting?>(null) }
    var customDays by remember { mutableStateOf(false) }
    var keepRunning by remember { mutableStateOf(false) }
    val health = app.moneytracker.ui.rememberSmsHealth()
    val capture = captureSetting && health.permission

    fun <T> put(s: Setting<T>, value: T) = scope.saveSetting(repo, s, value)
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
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(F.accentTint), contentAlignment = Alignment.Center) {
                Icon(Glyph.Message, null, tint = F.accent, modifier = Modifier.size(18.dp))
            }
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
                    val apply = { platform.setMessageCapture(on) { active -> put(Prefs.CAPTURE_ENABLED, active) } }
                    if (on) disclosure = apply else apply()
                }
                if (!health.permission) {
                    Text(app.moneytracker.ui.smsPermissionSteps(), color = F.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp))
                }
                if (capture) {
                    Divider()
                    HealthLine(tr("Keep detection running"),
                        if (health.background.restricted) tr("This phone is limiting MoneyTracker in the background") else tr("Let MoneyTracker start when an SMS arrives"),
                        tr("Check ›"), warn = health.background.restricted) { keepRunning = true }
                }
                Divider()
                SwitchLine(tr("Alert for detected messages"), tr("A notification when a bank message is detected"), alert, dimmed = !capture) { on ->
                    if (!capture) toaster.show(tr("Turn on “Detect bank SMS” first")) else put(Prefs.SMS_ALERT, on)
                }
                Divider()
                SwitchLine(tr("Save automatically"), tr("Add the entry right away when the account is recognised. A guessed usual account, or no account, waits for your review"), autoSave) { on ->
                    put(Prefs.SMS_AUTO_SAVE, on)
                }
                Divider()
                val origHint = when (showOriginal) {
                    SmsOriginal.OFF -> tr("Not kept on entries"); SmsOriginal.WHILE_EDITING -> tr("Shown only while editing an entry"); SmsOriginal.KEEP -> tr("Always shown in entry details")
                }
                // A compact choice on the right of its row, like a switch would sit.
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(tr("Original message on entries"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(origHint, color = F.muted, fontSize = 12.sp)
                    }
                    val opts = listOf(SmsOriginal.OFF to tr("Hide"), SmsOriginal.WHILE_EDITING to tr("While editing"), SmsOriginal.KEEP to tr("Show"))
                    val selected = opts.indexOfFirst { it.first == showOriginal }.coerceAtLeast(0)
                    SmsSegmented(opts.map { it.second }, selected, { put(Prefs.SMS_SHOW_ORIGINAL, opts[it].first) },
                        segmentHeight = 36.dp, outer = 10.dp, inner = 8.dp, inset = 3.dp, track = F.page, fill = false)
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
            SmsSegmented(opts, sel, { i -> if (i < presets.size) put(Prefs.SMS_IMPORT_DAYS, presets[i]) else customDays = true },
                Modifier.padding(top = 14.dp), segmentHeight = 40.dp, outer = 12.dp, inner = 9.dp, inset = 4.dp, track = F.track, fill = true)
            PrimaryButton(tr("Import messages"), { if (capture) import() else disclosure = { import() } }, Modifier.padding(top = 12.dp))
        }

        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                Text(tr("Filters").uppercase(), Modifier.padding(top = 12.dp, bottom = 2.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
                listOf(
                    Triple(Prefs.SMS_BLOCKED_SENDERS, tr("Blocked numbers"), tr("Messages from these senders are ignored")) to blockedSenders,
                    Triple(Prefs.SMS_BLOCKED_PHRASES, tr("Blocked words"), tr("Messages containing these are ignored")) to blockedPhrases,
                    Triple(Prefs.SMS_CONTENT_FILTER, tr("Content filter"), tr("Removed from a message before it is read")) to contentFilter,
                ).forEach { (def, value) ->
                    val (key, label, hint) = def
                    FilterLine(key, label, hint, value, open = editing == key, onToggle = { editing = if (editing == key) null else key }) { text ->
                        put(key, MessageRules.lines(text).joinToString("\n")); editing = null; toaster.show(tr("{0} saved", label))
                    }
                    Divider()
                }
                FRow(tr("Account matching rules"), tr("Sender and card digits, set on each account"), value = tr("Per account") + " ›", valueColor = F.accent, chevron = false, onClick = { nav.push(Screen.AccountList) })
            }
        }
        Text(tr("Content-filter words are removed before a message is read, which helps when a bank adds text that confuses the amount or shop name."),
            color = F.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }

    disclosure?.let { action ->
        Confirm(tr("Detect bank SMS"), smsDisclosureText(), tr("Continue"), onDismiss = { disclosure = null }, danger = false) { action() }
    }
    if (keepRunning) {
        val autostart = app.moneytracker.ui.hasAutostartManager(health.background.maker)
        Sheet(tr("Keep detection running"), { keepRunning = false }, bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (autostart) PrimaryButton(tr("Open Autostart"), { platform.openBackgroundSettings(autostart = true); keepRunning = false }, Modifier.fillMaxWidth())
                SecondaryButton(tr("Open app settings"), { platform.openBackgroundSettings(autostart = false); keepRunning = false }, Modifier.fillMaxWidth())
            }
        }) {
            Text(tr("Some phones stop apps from starting in the background. Bank SMS are then only read when you open MoneyTracker, or at the next background check (about every 15 minutes while the phone is in use)."),
                color = F.muted, fontSize = 13.sp, lineHeight = 19.sp)
            Text(app.moneytracker.ui.backgroundSteps(health.background.maker), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
        }
    }
    if (customDays) {
        var text by remember { mutableStateOf(days.toString()) }
        val value = text.toIntOrNull()?.takeIf { it in 1..365 }
        Sheet(tr("Days to import"), { customDays = false }, bottom = {
            PrimaryButton(tr("Save"), { value?.let { put(Prefs.SMS_IMPORT_DAYS, it) }; customDays = false }, enabled = value != null)
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

/** A status line inside the switches card: label, hint and a small action, amber when something needs fixing. */
@Composable
private fun HealthLine(label: String, hint: String, action: String, warn: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = if (warn) F.warn else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(hint, color = F.muted, fontSize = 12.sp)
        }
        Text(action, color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Switch drawn in the kit's colours (the row handles the tap). */
@Composable
internal fun SmsSwitch(checked: Boolean) {
    FSwitch(checked, null)
}

@Composable
private fun FilterLine(key: app.moneytracker.data.TextSetting, label: String, hint: String, value: String?, open: Boolean, onToggle: () -> Unit, onSave: (String) -> Unit) {
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
                    Box(Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(F.accent).clickable { onSave(text) }.padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center) {
                        Text(tr("Save"), color = F.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
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
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).clip(RoundedCornerShape(outer)).background(track).padding(inset),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.then(if (fill) Modifier.weight(1f) else Modifier).height(segmentHeight).clip(RoundedCornerShape(inner))
                    .background(if (on) F.card else Color.Transparent).clickable { onSelect(i) }
                    .then(if (fill) Modifier else Modifier.padding(horizontal = 10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) F.ink else F.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) }
        }
    }
}
