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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PIN_LENGTH = 4

/** Full-screen PIN pad. [onComplete] gets the 4 digits; returns false to shake/clear. */
@Composable
private fun PinPad(title: String, subtitle: String?, error: String?, onComplete: (String) -> Boolean, extra: @Composable () -> Unit = {}) {
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(SurfaceColor).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(72.dp))
        Icon(Icons.Outlined.Lock, null, Modifier.size(40.dp), tint = Accent)
        Text(title, Modifier.padding(top = 16.dp), fontSize = 20.sp)
        if (subtitle != null) Text(subtitle, Modifier.padding(top = 6.dp), color = SubtleText, fontSize = 14.sp)
        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(PIN_LENGTH) { i ->
                Box(Modifier.size(16.dp).background(if (i < pin.length) Accent else DividerColor, CircleShape))
            }
        }
        Text(error.orEmpty(), Modifier.padding(top = 14.dp).height(20.dp), color = DangerColor, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        extra()
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(76.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().clickable(enabled = k.isNotEmpty()) {
                            if (k == "⌫") pin = pin.dropLast(1)
                            else if (pin.length < PIN_LENGTH) {
                                pin += k
                                if (pin.length == PIN_LENGTH && !onComplete(pin)) pin = ""
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") Icon(Icons.AutoMirrored.Filled.Backspace, tr("Delete digit"))
                        else Text(k, fontSize = 26.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun LockScreen(hash: String, onUnlocked: () -> Unit) {
    val repo = LocalRepo.current
    val platform = LocalPlatform.current
    val biometricOn by remember(repo) { repo.flag(Keys.BIOMETRIC) }.collectAsState(false)
    val status by platform.biometricStatus.collectAsState()
    val canUseBiometric = biometricOn && status == BiometricStatus.AVAILABLE
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(0) }
    fun promptBiometric() = platform.authenticate(tr("Unlock MoneyTracker"), onUnlocked) { msg -> error = msg }
    // Offer the biometric prompt straight away; the PIN pad stays underneath as the fallback.
    LaunchedEffect(canUseBiometric) { if (canUseBiometric) promptBiometric() }
    PinPad(tr("Enter passcode"), if (canUseBiometric) tr("or use your fingerprint / face") else null, error, onComplete = { pin ->
        if (platform.hashPin(pin) == hash) { onUnlocked(); true }
        else { attempts++; error = tr("Wrong passcode ({0})", attempts); false }
    }) {
        if (canUseBiometric) {
            IconButton(onClick = { error = null; promptBiometric() }, modifier = Modifier.padding(bottom = 12.dp).size(56.dp)) {
                Icon(Icons.Outlined.Fingerprint, tr("Unlock with biometrics"), Modifier.size(40.dp), tint = Accent)
            }
        }
    }
}

/** What the passcode screen is currently asking for. */
private enum class PinStep { NONE, VERIFY_FOR_CHANGE, VERIFY_FOR_OFF, NEW, CONFIRM }

@Composable
fun SecurityScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val passcode by remember(repo) { repo.setting(Keys.PASSCODE) }.collectAsState(null)
    val biometricOn by remember(repo) { repo.flag(Keys.BIOMETRIC) }.collectAsState(false)
    val status by platform.biometricStatus.collectAsState()
    val hasPasscode = !passcode.isNullOrBlank()
    var step by remember { mutableStateOf(PinStep.NONE) }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var offerBiometric by remember { mutableStateOf(false) }

    fun put(key: String, value: String) = scope.launch { withContext(repo.io) { repo.putSetting(key, value) } }
    fun turnOff() { put(Keys.PASSCODE, ""); put(Keys.BIOMETRIC, "0"); message = tr("Passcode turned off") }
    /** Turning biometrics on requires one successful scan, so we know the sensor works for this user. */
    fun enableBiometric() = platform.authenticate(tr("Confirm it's you"), onSuccess = {
        put(Keys.BIOMETRIC, "1"); message = tr("Biometric unlock is on")
    }) { msg -> message = msg }

    when (step) {
        PinStep.VERIFY_FOR_CHANGE, PinStep.VERIFY_FOR_OFF -> {
            val forOff = step == PinStep.VERIFY_FOR_OFF
            // Changing or removing the lock needs the current PIN (or a biometric scan) — the phone may be in someone else's hands.
            LaunchedEffect(Unit) {
                if (biometricOn && status == BiometricStatus.AVAILABLE) {
                    platform.authenticate(tr("Confirm it's you"), onSuccess = {
                        if (forOff) { turnOff(); step = PinStep.NONE } else { first = null; error = null; step = PinStep.NEW }
                    })
                }
            }
            PinPad(tr("Enter current passcode"), null, error, onComplete = { pin ->
                if (platform.hashPin(pin) != passcode) { error = tr("Wrong passcode"); false }
                else {
                    error = null
                    if (forOff) { turnOff(); step = PinStep.NONE } else { first = null; step = PinStep.NEW }
                    true
                }
            })
            return
        }
        PinStep.NEW, PinStep.CONFIRM -> {
            PinPad(if (step == PinStep.NEW) tr("New passcode") else tr("Confirm passcode"), tr("4 digits"), error, onComplete = { pin ->
                val f = first
                if (step == PinStep.NEW) { first = pin; error = null; step = PinStep.CONFIRM; false }
                else if (f == pin) {
                    val wasNew = !hasPasscode
                    put(Keys.PASSCODE, platform.hashPin(pin))
                    step = PinStep.NONE; first = null
                    message = tr("Passcode saved")
                    if (wasNew && status == BiometricStatus.AVAILABLE && !biometricOn) offerBiometric = true
                    true
                } else { first = null; error = tr("Didn't match, try again"); step = PinStep.NEW; false }
            })
            return
        }
        PinStep.NONE -> Unit
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Passcode"), nav::pop)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxWidth().background(SurfaceColor)) {
            if (!hasPasscode) {
                SettingRow(tr("Set passcode")) { first = null; error = null; message = null; step = PinStep.NEW }
            } else {
                SettingRow(tr("Change passcode")) { error = null; message = null; step = PinStep.VERIFY_FOR_CHANGE }
                SettingRow(tr("Turn off passcode")) { error = null; message = null; step = PinStep.VERIFY_FOR_OFF }
            }
            HorizontalDivider(color = DividerColor)
            Box(Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) {
                when (status) {
                    BiometricStatus.AVAILABLE -> Column {
                        SwitchRow(tr("Unlock with fingerprint / face"), biometricOn && hasPasscode) { on ->
                            message = null
                            when {
                                !hasPasscode -> message = tr("Set a passcode first; it's the fallback if biometrics fail.")
                                on -> enableBiometric()
                                else -> { put(Keys.BIOMETRIC, "0"); message = tr("Biometric unlock is off") }
                            }
                        }
                        Text(tr("Uses whatever your phone has set up — fingerprint, face or iris."), fontSize = 12.sp, color = SubtleText)
                    }
                    BiometricStatus.NOT_ENROLLED -> Column {
                        Text(tr("Unlock with fingerprint / face"))
                        Text(tr("No fingerprint or face is set up on this phone yet."), fontSize = 13.sp, color = SubtleText)
                        Text(
                            tr("Set up in phone settings"), Modifier.padding(top = 8.dp).clickable { platform.openBiometricEnrollment() },
                            color = Accent, fontWeight = FontWeight.Medium,
                        )
                    }
                    BiometricStatus.UNSUPPORTED -> Column {
                        Text(tr("Unlock with fingerprint / face"), color = SubtleText)
                        Text(tr("This phone has no biometric sensor that apps can use. The passcode still protects the app."), fontSize = 13.sp, color = SubtleText)
                    }
                }
            }
        }
        message?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = IncomeColor, fontSize = 14.sp) }
        Text(
            tr("The app locks when opened and after a minute in the background. Biometric unlock always falls back to your passcode. " +
                "If you forget the passcode, clear the app's data (restoring an exported backup brings your records back)."),
            Modifier.padding(24.dp), color = SubtleText, fontSize = 13.sp,
        )
    }

    if (offerBiometric) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { offerBiometric = false },
            icon = { Icon(Icons.Outlined.Fingerprint, null, tint = Accent) },
            title = { Text(tr("Use fingerprint or face too?")) },
            text = { Text(tr("Unlock with your phone's biometrics. Your passcode still works as a backup.")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { offerBiometric = false; enableBiometric() }) { Text(tr("Turn on"), color = Accent) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { offerBiometric = false }) { Text(tr("Not now")) } },
        )
    }
}

@Composable
fun SettingRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        if (value != null) Text(value, color = Accent)
    }
}

// ───────────────────────── onboarding

@Composable
fun OnboardingScreen() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(0) }
    var symbol by remember { mutableStateOf("₹") }
    var decimals by remember { mutableStateOf(2) }
    val pages = listOf(
        tr("Track every rupee") to tr("Add income, expenses and transfers in a few taps with the built-in keypad."),
        tr("See where it goes") to tr("Daily, calendar and monthly views, stats by category, budgets and card statements."),
        tr("Yours, offline") to tr("Everything stays on this phone. Daily backups, export to CSV or Excel, no ads."),
    )
    Column(Modifier.fillMaxSize().background(SurfaceColor).statusBarsPadding().padding(28.dp)) {
        Spacer(Modifier.height(48.dp))
        if (page < pages.size) {
            val (title, body) = pages[page]
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(body, Modifier.padding(top = 16.dp), fontSize = 17.sp, color = InkSoft)
        } else {
            Text(tr("Your currency"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(tr("You can add more currencies later in Configuration."), Modifier.padding(top = 12.dp), color = SubtleText)
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("₹" to 2, "$" to 2, "€" to 2, "£" to 2, "¥" to 0).forEach { (s, d) ->
                    Box(
                        Modifier.size(52.dp).border(1.5.dp, if (symbol == s) Accent else BorderColor, RoundedCornerShape(8.dp))
                            .clickable { symbol = s; decimals = d },
                        contentAlignment = Alignment.Center,
                    ) { Text(s, fontSize = 22.sp, color = if (symbol == s) Accent else InkSoft) }
                }
            }
            OutlinedTextField(symbol, { symbol = it.take(4) }, Modifier.padding(top = 20.dp), label = { Text(tr("Symbol")) }, singleLine = true)
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Decimal places"), Modifier.weight(1f))
                (0..3).forEach { d ->
                    Text(
                        d.toString(),
                        Modifier.padding(start = 8.dp).border(1.dp, if (decimals == d) Accent else BorderColor, RoundedCornerShape(6.dp))
                            .clickable { decimals = d }.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = if (decimals == d) Accent else InkSoft,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size + 1) { i -> Box(Modifier.padding(4.dp).size(8.dp).background(if (i == page) Accent else DividerColor, CircleShape)) }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp).background(Accent, RoundedCornerShape(6.dp)).clickable {
                if (page < pages.size) page++
                else scope.launch {
                    withContext(repo.io) {
                        repo.putSetting(Keys.CURRENCY_SYMBOL, symbol.ifBlank { "₹" })
                        repo.putSetting(Keys.CURRENCY_DECIMALS, decimals.toString())
                        repo.putSetting(Keys.ONBOARDED, "1")
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) { Text(if (page < pages.size) tr("Next") else tr("Start"), color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center) }
        if (page < pages.size) {
            Text(tr("Skip"), Modifier.fillMaxWidth().clickable { page = pages.size }.padding(14.dp), textAlign = TextAlign.Center, color = SubtleText)
        }
    }
}
