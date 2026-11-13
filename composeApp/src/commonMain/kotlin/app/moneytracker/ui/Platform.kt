package app.moneytracker.ui

import app.moneytracker.data.Prefs
import app.moneytracker.data.SettingsStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Passcode hashes that carry their own salt start with this, so a restored backup keeps a working passcode. */
const val PORTABLE_PIN_PREFIX = "s2:"

fun isPortablePinHash(stored: String?) = stored?.startsWith(PORTABLE_PIN_PREFIX) == true

/**
 * Checks [pin] at the lock screen. The passcode from before a restore is accepted too, so a backup whose passcode
 * can't be checked on this phone never locks you out; whichever matches becomes the passcode, in the portable form.
 */
fun PinSecurity.unlock(pin: String, stored: String, settings: SettingsStore): Boolean {
    val previous = settings.settingNow(Prefs.PASSCODE_PREVIOUS)
    when {
        checkPin(pin, stored) -> if (!isPortablePinHash(stored)) settings.putSetting(Prefs.PASSCODE, hashPin(pin))
        previous.isNotBlank() && checkPin(pin, previous) -> settings.putSetting(Prefs.PASSCODE, hashPin(pin))
        else -> return false.also { app.moneytracker.Log.i("Lock") { "wrong passcode" } }
    }
    if (previous.isNotEmpty()) settings.putSetting(Prefs.PASSCODE_PREVIOUS, "")
    app.moneytracker.Log.i("Lock") { "unlocked with passcode" }
    return true
}

/**
 * [maker]: lower-case manufacturer, e.g. "vivo". [restricted]: Android's battery optimisation or background
 * restriction applies to the app. Maker battery managers (vivo, Xiaomi, Oppo…) can block it even when this is false.
 */
data class BackgroundState(val maker: String, val restricted: Boolean)

/** A browser signed in to the web dashboard. [lastSeen]: epoch ms of its last request. */
data class WebVisitor(val id: String, val name: String, val lastSeen: Long)

/**
 * The same-Wi-Fi web dashboard. [address] is "192.168.1.5:8080" while [running]; [wifi] is whether the phone has a
 * local network address it can serve on; [stopReason] says why it last stopped on its own (left Wi-Fi, auto-stop).
 */
data class WebDashboardState(
    val running: Boolean = false,
    val wifi: Boolean = false,
    val address: String? = null,
    val computers: List<WebVisitor> = emptyList(),
    val error: String? = null,
    val stopReason: String? = null,
)

/** Events from an in-app listening session. All run on the main thread. */
interface SpeechListener {
    /** Rough loudness 0..1, for the sound bars. */
    fun onLevel(level: Float) {}
    /** The words heard so far (replaces the previous partial text). */
    fun onPartial(text: String)
    /** Listening ended with this text (may be empty if nothing was understood). */
    fun onFinal(text: String)
    fun onError(error: SpeechError)
}

enum class SpeechError { NO_PERMISSION, NO_MATCH, NETWORK, BUSY, OTHER }

interface SpeechSession {
    /** Stop listening and deliver what was heard through onFinal. */
    fun stop()
    /** Stop without a result. */
    fun cancel()
}

/**
 * The passcode and the phone's biometrics. A passcode hash carries its own salt, so a restored backup unlocks
 * on any phone.
 */
interface PinSecurity {
    /** A new hash for [pin], with its own salt inside, so it checks out on any install. */
    fun hashPin(pin: String): String
    /** True when [pin] matches [stored], in either the portable form or an older install-only one. */
    fun checkPin(pin: String, stored: String): Boolean
    /** An older install-only hash rewritten in the portable form (same PIN, no need to know it), or null if this install can't. */
    fun portablePinHash(stored: String): String?
    /** Re-checked whenever the app returns to the foreground (e.g. after enrolling a fingerprint in Settings). */
    val biometricStatus: StateFlow<BiometricStatus>
    /** Opens the system screen for adding a fingerprint / face. */
    fun openBiometricEnrollment()
    /**
     * Shows the system biometric prompt. [onError] gets a user-facing message for real failures
     * (lockout, sensor unavailable); it is not called when the user simply taps "Use passcode".
     */
    fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit = {})
}

/** Receipt photos, stored privately and referenced by file name. */
interface Photos {
    fun pickPhoto(onPicked: (fileName: String) -> Unit)
    fun takePhoto(onTaken: (fileName: String) -> Unit)
    fun deletePhotoFile(fileName: String)
    @Composable fun PhotoImage(fileName: String, modifier: Modifier)
    /** Whole photo, scaled to fit (full-screen viewer). */
    @Composable fun PhotoFull(fileName: String, modifier: Modifier)
}

/** Speech to text, through the system popup or the app's own listening sheet. */
interface Voice {
    val voiceAvailable: Boolean
    fun listen(onText: (String) -> Unit)
    /** True when MoneyTracker's own listening sheet can turn speech into text on this phone. */
    val speechAvailable: Boolean
    /**
     * Starts listening in the app (no system popup); asks for the microphone first if needed. Words arrive through
     * [listener] while the person speaks. Returns null when nothing can listen here.
     */
    fun startSpeech(listener: SpeechListener): SpeechSession?
}

/** Where an entry was made, and showing that place on a map. */
interface Location {
    fun currentLocation(onResult: (lat: Double, lng: Double) -> Unit, onUnavailable: () -> Unit)
    fun openMap(lat: Double, lng: Double)
}

/** Work the app does while closed. Each setter returns via [onResult] whether it is now active (permissions may be refused). */
interface BackgroundJobs {
    fun setReminder(enabled: Boolean, hour: Int, minute: Int, onResult: (Boolean) -> Unit)
    fun setQuickAddNotification(enabled: Boolean, onResult: (Boolean) -> Unit)
    /** Phone maker and whether Android limits the app in the background, which delays SMS detection. */
    fun backgroundState(): BackgroundState
    /** Opens the maker's autostart manager ([autostart]) or the app's own settings page; false if neither opened. */
    fun openBackgroundSettings(autostart: Boolean): Boolean
}

/** Bank SMS capture: turning it on, reading the inbox once, and checking the permission is still there. */
interface MessageCapture {
    fun setMessageCapture(enabled: Boolean, onResult: (Boolean) -> Unit)
    /** Reads bank messages already in the SMS inbox (last [days] days); [onResult] gets how many were added, null if refused. */
    fun importInboxMessages(days: Int, onResult: (Int?) -> Unit)
    /** False when "Detect bank SMS" can't run because the SMS permission was taken away. */
    fun messagePermissionGranted(): Boolean
}

/** The phone serves a page to computers on the same Wi-Fi (local network only). */
interface WebDashboardControl {
    val state: StateFlow<WebDashboardState>
    /** Re-checks Wi-Fi (call while the dashboard screen is open). */
    fun refreshWebDashboard()
    /** Starts serving in the background, with a notification; [onResult] is false when it couldn't start. */
    fun startWebDashboard(onResult: (Boolean) -> Unit)
    fun stopWebDashboard()
    fun disconnectWebComputer(id: String)
    /** Saves (or with null removes) the dashboard password; every computer has to sign in again. */
    fun setWebPassword(password: String?)
    fun openWifiSettings()
}

/** The phone's language and the translations shipped with the app. */
interface Languages {
    /** The phone's language tag, e.g. "hi-IN". */
    fun systemLanguage(): String
    /** Translation table for a shipped language tag (English text → translation); empty if missing. */
    fun translations(tag: String): Map<String, String>
}

/** What the About screen shows, and the app-level actions around it. */
interface AppInfo {
    /** Version shown to users, e.g. "0.1.0-build.42". */
    val appVersion: String
    /** A license text shipped with the app (e.g. "GPL-3.0"), or null if missing. */
    fun licenseText(id: String): String?
    /** Opens a web page in the browser. */
    fun openUrl(url: String)
    /** Closes the screens and opens the app again from the start (used after switching layouts). */
    fun restartApp()
}

/** Used only for exchange rates (daily unless turned off, and only with other currencies). */
interface Network {
    /** The body of an HTTPS GET to [url], or null on any failure (no connection, timeout, not 200). */
    suspend fun httpGet(url: String): String?
}

/** The model files Smart entry loads. */
interface SmartModels {
    /** A Smart entry model file (bytes), or null when the app doesn't ship it. */
    fun readSmartModel(name: String): ByteArray?
}

/**
 * Device features the shared UI needs, one small capability per field. Android implements them in androidMain;
 * another platform (iOS, desktop) only has to provide its own. Screens take only the fields they use.
 * Callbacks run on the main thread.
 */
data class Platform(
    val pinSecurity: PinSecurity,
    val photos: Photos,
    val voice: Voice,
    val location: Location,
    val backgroundJobs: BackgroundJobs,
    val messageCapture: MessageCapture,
    val webDashboard: WebDashboardControl,
    val languages: Languages,
    val appInfo: AppInfo,
    val network: Network,
    val smartModels: SmartModels,
)

enum class BiometricStatus { AVAILABLE, NOT_ENROLLED, UNSUPPORTED }

val LocalPlatform = compositionLocalOf<Platform> { error("No platform services") }

/** One-shot actions requested from outside the UI (shortcut, widget, notification). */
enum class LaunchAction { ADD_EXPENSE, ADD_INCOME, ADD_TRANSFER, VOICE, INBOX, SEARCH, WEB_DASHBOARD }
