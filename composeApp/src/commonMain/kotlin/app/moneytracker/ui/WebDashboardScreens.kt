package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.Prefs
import app.moneytracker.data.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

// The same-Wi-Fi web dashboard: shared state for both layouts, and the classic screen.

/** Minutes of no visits before the dashboard stops on its own; 0 = never. */
internal val WEB_AUTO_STOPS = listOf(30, 60, 0)
internal const val WEB_PASSWORD_MIN = 4

internal fun autoStopLabel(minutes: Int, short: Boolean = false): String = when (minutes) {
    0 -> tr("Never")
    60 -> if (short) tr("1 hour") else tr("After 1 hour")
    else -> if (short) tr("{0} min", minutes) else tr("After {0} min", minutes)
}

/** "Active now", "Active 4 min ago". */
internal fun activeLabel(lastSeen: Long, now: Long): String {
    val min = ((now - lastSeen) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> tr("Active now")
        min < 60 -> tr("Active {0} min ago", min)
        else -> tr("Active {0} h ago", min / 60)
    }
}

/** The dashboard's actions that save something or report back: starting, the password and the auto-stop. */
internal class WebDashboardModel(
    private val dashboard: WebDashboardControl,
    private val settings: SettingsStore,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    /** Starts serving; [onFailed] gets why it couldn't. */
    fun start(onFailed: (String) -> Unit) =
        dashboard.startWebDashboard { ok -> if (!ok) onFailed(dashboard.state.value.error ?: tr("Couldn't start the web dashboard")) }

    /** Saves or (with null) removes the password, then tells [onDone] what changed. */
    fun setPassword(password: String?, onDone: (String) -> Unit) {
        scope.launch {
            withContext(io) { dashboard.setWebPassword(password) }
            onDone(if (password == null) tr("Password turned off") else tr("Password saved. Computers sign in again."))
        }
    }

    fun setAutoStop(minutes: Int) {
        scope.launch(io) { settings.putSetting(Prefs.WEB_AUTO_STOP, minutes) }
    }
}

/** Everything the two dashboard screens show, plus [actions] for what they do. */
internal class WebDashboardUi(
    val state: WebDashboardState,
    val passwordSet: Boolean,
    val autoStop: Int,
    val now: Long,
    /** Set briefly after Copy. */
    val copied: Boolean,
    val actions: WebDashboardActions,
)

/** The taps both dashboard screens handle. */
internal class WebDashboardActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val copy: () -> Unit,
    val disconnect: (String) -> Unit,
    val setPassword: (String?) -> Unit,
    val setAutoStop: (Int) -> Unit,
    val openWifi: () -> Unit,
)

@Composable
internal fun rememberWebDashboardUi(onMessage: (String) -> Unit): WebDashboardUi {
    val repo = LocalRepo.current
    val dashboard = LocalPlatform.current.webDashboard
    val scope = rememberCoroutineScope()
    val model = remember(repo, dashboard) { WebDashboardModel(dashboard, repo.settingsStore, repo.io, scope) }
    val clipboard = LocalClipboardManager.current
    val state by dashboard.state.collectAsState()
    val password by rememberSetting(Prefs.WEB_PASSWORD)
    val autoStop by rememberSetting(Prefs.WEB_AUTO_STOP)
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    var copied by remember { mutableStateOf(false) }
    // No lifecycle hook in common code: a cheap re-check notices Wi-Fi changes and keeps "Active 2 min ago" current.
    LaunchedEffect(dashboard) {
        while (true) {
            dashboard.refreshWebDashboard()
            now = Clock.System.now().toEpochMilliseconds()
            delay(2000)
        }
    }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    return WebDashboardUi(
        state = state,
        passwordSet = password.isNotEmpty(),
        autoStop = autoStop.takeIf { it in WEB_AUTO_STOPS } ?: Prefs.WEB_AUTO_STOP.default,
        now = now,
        copied = copied,
        actions = WebDashboardActions(
            start = { model.start(onMessage) },
            stop = { dashboard.stopWebDashboard() },
            copy = { state.address?.let { clipboard.setText(AnnotatedString("http://$it")); copied = true } },
            disconnect = { dashboard.disconnectWebComputer(it) },
            setPassword = { pw -> model.setPassword(pw, onMessage) },
            setAutoStop = model::setAutoStop,
            openWifi = { dashboard.openWifiSettings() },
        ),
    )
}

/** Short line for the More / Settings tile. */
@Composable
internal fun webDashboardStatus(): String {
    val state by LocalPlatform.current.webDashboard.state.collectAsState()
    return if (state.running) tr("On · {0}", state.address.orEmpty()) else tr("Use from a computer")
}

// ───────────────────────── classic screen

@Composable
fun WebDashboardScreen() {
    val nav = LocalNavigator.current
    var message by remember { mutableStateOf<String?>(null) }
    val ui = rememberWebDashboardUi { message = it }
    var passwordDialog by remember { mutableStateOf(false) }
    val s = ui.state

    Column(Modifier.fillMaxSize().background(PageBackground)) {
        BackTopBar(tr("Web dashboard"), nav::pop)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when {
                !s.running && !s.wifi -> NoWifiCard(ui.actions.openWifi)
                !s.running -> StartCard(s, ui.actions.start)
                else -> RunningCard(ui)
            }
            ConfigSection(tr("Options"))
            OptionRow(tr("Password"), tr("Asked once per visit on the computer"), if (ui.passwordSet) tr("On") else tr("Off")) { passwordDialog = true }
            OptionRow(tr("Stop automatically"), tr("When no computer has visited for a while"), autoStopLabel(ui.autoStop)) {
                ui.actions.setAutoStop(WEB_AUTO_STOPS[(WEB_AUTO_STOPS.indexOf(ui.autoStop) + 1) % WEB_AUTO_STOPS.size])
            }
            Text(tr("Works only on your local network. Without a password, anyone on the same Wi-Fi who knows the address can open it, so set one on shared or public Wi-Fi."),
                Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 32.dp), fontSize = 13.sp, color = SubtleText, lineHeight = 19.sp)
        }
    }

    if (passwordDialog) PasswordDialog(ui.passwordSet, onClose = { passwordDialog = false }, onSave = ui.actions.setPassword)
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(tr("OK")) } })
    }
}

@Composable
private fun NoWifiCard(openWifi: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 26.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).background(PageBackground, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.WifiOff, null, tint = SubtleText, modifier = Modifier.size(26.dp))
        }
        Text(tr("Connect to Wi-Fi to use the web dashboard"), fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(tr("The computer and this phone need to be on the same Wi-Fi network. Mobile data doesn't work for this."),
            fontSize = 14.sp, color = SubtleText, textAlign = TextAlign.Center, lineHeight = 20.sp)
        OutlineButton(tr("Open Wi-Fi settings"), openWifi)
    }
}

@Composable
private fun StartCard(s: WebDashboardState, start: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(AccentTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Computer, null, tint = Accent, modifier = Modifier.size(24.dp))
        }
        Text(tr("Use MoneyTracker from a computer"), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(tr("Open your entries in a browser on a computer on the same Wi-Fi. See totals and charts, search, and add or edit entries with a full keyboard."),
            fontSize = 14.sp, lineHeight = 21.sp)
        Text(tr("The page comes from this phone. Nothing is sent to the internet."), fontSize = 13.sp, color = SubtleText)
        (s.stopReason ?: s.error)?.let { Text(it, fontSize = 13.sp, color = ExpenseColor) }
        Box(
            Modifier.fillMaxWidth().height(48.dp).background(Accent, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClick = start),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Start"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun RunningCard(ui: WebDashboardUi) {
    val s = ui.state
    Column(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(Accent, CircleShape))
            Text(tr("On · Wi-Fi"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
        }
        Text(tr("On the computer, type this in the browser:"), fontSize = 14.sp)
        Row(
            Modifier.fillMaxWidth().background(PageBackground, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.address.orEmpty(), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            TextButton(onClick = ui.actions.copy) { Text(if (ui.copied) tr("Copied") else tr("Copy"), color = Accent, fontWeight = FontWeight.Medium) }
        }
        Text(tr("Full address: http://{0} · keeps running when you leave the app, until you stop it.", s.address.orEmpty()),
            fontSize = 12.sp, color = SubtleText, lineHeight = 17.sp)
        OutlineButton(tr("Stop"), ui.actions.stop, danger = true, fill = true)
    }
    ConnectedComputers(s.computers, ui.now, ui.actions.disconnect)
}

@Composable
private fun ConnectedComputers(computers: List<WebVisitor>, now: Long, disconnect: (String) -> Unit) {
    ConfigSection(tr("Connected computers"))
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(vertical = 4.dp)) {
        if (computers.isEmpty()) Text(tr("None yet. Open the address on a computer."),
            Modifier.padding(horizontal = 26.dp, vertical = 14.dp), fontSize = 14.sp, color = SubtleText)
        computers.forEach { c ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 26.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(c.name, fontSize = 15.sp)
                    Text(activeLabel(c.lastSeen, now), fontSize = 12.sp, color = SubtleText)
                }
                TextButton(onClick = { disconnect(c.id) }) { Text(tr("Disconnect"), color = Accent) }
            }
        }
    }
}

/** Sets the dashboard password ([onSave] with null turns it off, offered while one is set). */
@Composable
private fun PasswordDialog(passwordSet: Boolean, onClose: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ok = text.length >= WEB_PASSWORD_MIN
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Dashboard password")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("The computer asks for it before showing anything. It's separate from your app passcode."), fontSize = 14.sp)
                OutlinedTextField(text, { text = it.take(64) }, label = { Text(tr("Password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text(tr("At least 4 characters. After 5 wrong tries the computer waits a minute."), fontSize = 12.sp, color = SubtleText)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text); onClose() }, enabled = ok) { Text(tr("Save")) } },
        dismissButton = {
            Row {
                if (passwordSet) TextButton(onClick = { onSave(null); onClose() }) { Text(tr("Turn off"), color = ExpenseColor) }
                TextButton(onClick = onClose) { Text(tr("Cancel")) }
            }
        },
    )
}

@Composable
private fun OptionRow(title: String, hint: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).heightIn(min = 56.dp).padding(start = 26.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp)
            Text(hint, fontSize = 12.sp, color = SubtleText)
        }
        Text(value, fontSize = 15.sp, color = Accent)
    }
}

@Composable
private fun OutlineButton(text: String, onClick: () -> Unit, danger: Boolean = false, fill: Boolean = false) {
    val shape = RoundedCornerShape(23.dp)
    Box(
        (if (fill) Modifier.fillMaxWidth() else Modifier).height(46.dp).border(1.dp, if (danger) DividerColor else Accent, shape)
            .background(SurfaceColor, shape).clip(shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (danger) ExpenseColor else Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
    Spacer(Modifier.height(0.dp))
}
