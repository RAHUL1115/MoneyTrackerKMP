package app.moneytracker.ui

import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.data.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the passcode screen is currently asking for. */
enum class PinStep { NONE, VERIFY_FOR_CHANGE, VERIFY_FOR_OFF, NEW, CONFIRM }

/** The stored lock as the screen sees it now: the passcode hash (blank = none), the biometric switch and the sensor. */
data class LockSetup(val passcode: String, val biometricOn: Boolean, val biometric: BiometricStatus) {
    val hasPasscode get() = passcode.isNotBlank()
    val canUseBiometric get() = biometricOn && biometric == BiometricStatus.AVAILABLE
}

/** [message] confirms what just changed; [offerBiometric] asks once, after a first passcode, to add biometrics. */
data class PasscodeState(
    val step: PinStep = PinStep.NONE,
    val error: String? = null,
    val message: String? = null,
    val offerBiometric: Boolean = false,
)

/**
 * Setting, changing and turning off the passcode, and the biometric switch. Changing or removing the lock first
 * needs the current PIN (or a biometric scan), since the phone may be in someone else's hands.
 */
class PasscodeModel(
    private val settings: SettingsStore,
    private val pins: PinSecurity,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(PasscodeState())
    val state: StateFlow<PasscodeState> = ui.asStateFlow()
    /** The new PIN typed once, waiting to be confirmed. */
    private var first: String? = null

    fun startSet() { first = null; ui.update { it.copy(step = PinStep.NEW, error = null, message = null) } }
    fun startChange() = ui.update { it.copy(step = PinStep.VERIFY_FOR_CHANGE, error = null, message = null) }
    fun startTurnOff() = ui.update { it.copy(step = PinStep.VERIFY_FOR_OFF, error = null, message = null) }

    /** Checks the current PIN; false clears the pad. */
    fun verify(pin: String, setup: LockSetup): Boolean {
        if (!pins.checkPin(pin, setup.passcode)) { ui.update { it.copy(error = tr("Wrong passcode")) }; return false }
        ui.update { it.copy(error = null) }
        verified()
        return true
    }

    /** The verify step's biometric prompt, offered straight away when biometrics are on. */
    fun verifyWithBiometric(setup: LockSetup) {
        if (setup.canUseBiometric) pins.authenticate(tr("Confirm it's you"), onSuccess = ::verified)
    }

    /** The new PIN, typed twice. Returns true once both match and it is saved; false clears the pad. */
    fun enterNew(pin: String, setup: LockSetup): Boolean {
        val typed = first
        return when {
            ui.value.step == PinStep.NEW -> { first = pin; ui.update { it.copy(step = PinStep.CONFIRM, error = null) }; false }
            typed == pin -> { savePasscode(pin, setup); true }
            else -> { first = null; ui.update { it.copy(step = PinStep.NEW, error = tr("Didn't match, try again")) }; false }
        }
    }

    /** The biometric switch. It needs a passcode as the fallback, and turning it on needs one successful scan. */
    fun setBiometric(on: Boolean, setup: LockSetup) {
        ui.update { it.copy(message = null) }
        when {
            !setup.hasPasscode -> say(tr("Set a passcode first; it's the fallback if biometrics fail."))
            on -> enableBiometric()
            else -> { save(Prefs.BIOMETRIC, false); say(tr("Biometric unlock is off")) }
        }
    }

    /** Turning biometrics on requires one successful scan, so we know the sensor works for this user. */
    fun enableBiometric() = pins.authenticate(tr("Confirm it's you"), onSuccess = {
        save(Prefs.BIOMETRIC, true); say(tr("Biometric unlock is on"))
    }) { msg -> say(msg) }

    fun dismissBiometricOffer() = ui.update { it.copy(offerBiometric = false) }

    private fun verified() {
        if (ui.value.step == PinStep.VERIFY_FOR_OFF) {
            turnOff()
            ui.update { it.copy(step = PinStep.NONE) }
        } else {
            first = null
            ui.update { it.copy(step = PinStep.NEW, error = null) }
        }
    }

    private fun savePasscode(pin: String, setup: LockSetup) {
        save(Prefs.PASSCODE, pins.hashPin(pin))
        first = null
        val offer = !setup.hasPasscode && setup.biometric == BiometricStatus.AVAILABLE && !setup.biometricOn
        ui.update { it.copy(step = PinStep.NONE, message = tr("Passcode saved"), offerBiometric = it.offerBiometric || offer) }
    }

    private fun turnOff() {
        save(Prefs.PASSCODE, "")
        save(Prefs.BIOMETRIC, false)
        say(tr("Passcode turned off"))
    }

    private fun say(message: String) = ui.update { it.copy(message = message) }

    private fun <T> save(s: Setting<T>, value: T) {
        scope.launch(io) { settings.putSetting(s, value) }
    }
}

/** The lock screen: checks the PIN and counts wrong tries. */
class LockModel(private val pins: PinSecurity, private val settings: SettingsStore) {
    private val ui = MutableStateFlow<String?>(null)
    /** The line under the dots: a wrong try or a biometric failure. */
    val error: StateFlow<String?> = ui.asStateFlow()
    private var attempts = 0

    /** True when [pin] opens the app. Blocking: a match may rewrite the stored hash in the portable form. */
    fun unlock(pin: String, stored: String): Boolean {
        if (pins.unlock(pin, stored, settings)) return true
        attempts++
        ui.value = tr("Wrong passcode ({0})", attempts)
        return false
    }

    fun showError(message: String?) { ui.value = message }
}
