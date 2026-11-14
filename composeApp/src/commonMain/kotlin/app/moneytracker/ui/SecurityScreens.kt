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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.BiometricStatus
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.collectAsState
import app.moneytracker.core.rememberSetting
import app.moneytracker.core.tr
import app.moneytracker.core.unlock
import app.moneytracker.data.Prefs

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
                    // The blank key under 7 only fills the grid: it takes no taps and TalkBack skips it.
                    val tap = if (k.isEmpty()) Modifier else Modifier.clickable(role = Role.Button) {
                        if (k == "⌫") pin = pin.dropLast(1)
                        else if (pin.length < PIN_LENGTH) {
                            pin += k
                            if (pin.length == PIN_LENGTH && !onComplete(pin)) pin = ""
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxSize().then(tap),
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
    val pinSecurity = LocalPlatform.current.pinSecurity
    val model = remember(repo, pinSecurity) { LockModel(pinSecurity, repo.settingsStore) }
    val biometricOn by rememberSetting(Prefs.BIOMETRIC)
    val status by pinSecurity.biometricStatus.collectAsState()
    val canUseBiometric = biometricOn && status == BiometricStatus.AVAILABLE
    val error by model.error.collectAsState()
    fun promptBiometric() = pinSecurity.authenticate(tr("Unlock MoneyTracker"), onUnlocked, model::showError)
    // Offer the biometric prompt straight away; the PIN pad stays underneath as the fallback.
    LaunchedEffect(canUseBiometric) { if (canUseBiometric) promptBiometric() }
    PinPad(tr("Enter passcode"), if (canUseBiometric) tr("or use your fingerprint / face") else null, error, onComplete = { pin ->
        model.unlock(pin, hash).also { if (it) onUnlocked() }
    }) {
        if (canUseBiometric) {
            IconButton(onClick = { model.showError(null); promptBiometric() }, modifier = Modifier.padding(bottom = 12.dp).size(56.dp)) {
                Icon(Icons.Outlined.Fingerprint, tr("Unlock with biometrics"), Modifier.size(40.dp), tint = Accent)
            }
        }
    }
}

/** Passcode settings: wiring for [SecurityContent], or the PIN pad while a PIN is being asked for. */
@Composable
fun SecurityScreen() {
    val repo = LocalRepo.current
    val pinSecurity = LocalPlatform.current.pinSecurity
    val scope = rememberCoroutineScope()
    val model = remember(repo, pinSecurity) { PasscodeModel(repo.settingsStore, pinSecurity, repo.io, scope) }
    val status by pinSecurity.biometricStatus.collectAsState()
    val setup = LockSetup(rememberSetting(Prefs.PASSCODE).value, rememberSetting(Prefs.BIOMETRIC).value, status)
    val state by model.state.collectAsState()

    when (state.step) {
        PinStep.VERIFY_FOR_CHANGE, PinStep.VERIFY_FOR_OFF -> {
            LaunchedEffect(Unit) { model.verifyWithBiometric(setup) }
            PinPad(tr("Enter current passcode"), null, state.error, onComplete = { model.verify(it, setup) })
        }
        PinStep.NEW, PinStep.CONFIRM ->
            PinPad(if (state.step == PinStep.NEW) tr("New passcode") else tr("Confirm passcode"), tr("4 digits"), state.error,
                onComplete = { model.enterNew(it, setup) })
        PinStep.NONE -> SecurityContent(state, setup, model, onOpenEnrollment = pinSecurity::openBiometricEnrollment)
    }
}

@Composable
private fun SecurityContent(state: PasscodeState, setup: LockSetup, model: PasscodeModel, onOpenEnrollment: () -> Unit) {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Passcode"), nav::pop)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxWidth().background(SurfaceColor)) {
            if (!setup.hasPasscode) {
                SettingRow(tr("Set passcode"), onClick = model::startSet)
            } else {
                SettingRow(tr("Change passcode"), onClick = model::startChange)
                SettingRow(tr("Turn off passcode"), onClick = model::startTurnOff)
            }
            HorizontalDivider(color = DividerColor)
            Box(Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) {
                BiometricSetting(setup, onSwitch = { model.setBiometric(it, setup) }, onOpenEnrollment)
            }
        }
        state.message?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = IncomeColor, fontSize = 14.sp) }
        Text(
            tr("The app locks when opened and after a minute in the background. Biometric unlock always falls back to your passcode. " +
                "If you forget the passcode, clear the app's data (restoring an exported backup brings your records back)."),
            Modifier.padding(24.dp), color = SubtleText, fontSize = 13.sp,
        )
    }
    if (state.offerBiometric) {
        AlertDialog(
            onDismissRequest = model::dismissBiometricOffer,
            icon = { Icon(Icons.Outlined.Fingerprint, null, tint = Accent) },
            title = { Text(tr("Use fingerprint or face too?")) },
            text = { Text(tr("Unlock with your phone's biometrics. Your passcode still works as a backup.")) },
            confirmButton = { TextButton(onClick = { model.dismissBiometricOffer(); model.enableBiometric() }) { Text(tr("Turn on"), color = Accent) } },
            dismissButton = { TextButton(onClick = model::dismissBiometricOffer) { Text(tr("Not now")) } },
        )
    }
}

/** The biometric switch, or why it isn't there. */
@Composable
private fun BiometricSetting(setup: LockSetup, onSwitch: (Boolean) -> Unit, onOpenEnrollment: () -> Unit) {
    when (setup.biometric) {
        BiometricStatus.AVAILABLE -> Column {
            SwitchRow(tr("Unlock with fingerprint / face"), setup.biometricOn && setup.hasPasscode, onSwitch)
            Text(tr("Uses whatever your phone has set up — fingerprint, face or iris."), fontSize = 12.sp, color = SubtleText)
        }
        BiometricStatus.NOT_ENROLLED -> Column {
            Text(tr("Unlock with fingerprint / face"))
            Text(tr("No fingerprint or face is set up on this phone yet."), fontSize = 13.sp, color = SubtleText)
            Text(
                tr("Set up in phone settings"), Modifier.padding(top = 8.dp).clickable(role = Role.Button, onClick = onOpenEnrollment),
                color = Accent, fontWeight = FontWeight.Medium,
            )
        }
        BiometricStatus.UNSUPPORTED -> Column {
            Text(tr("Unlock with fingerprint / face"), color = SubtleText)
            Text(tr("This phone has no biometric sensor that apps can use. The passcode still protects the app."), fontSize = 13.sp, color = SubtleText)
        }
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

