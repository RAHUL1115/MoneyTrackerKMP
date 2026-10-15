package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Device features the shared UI needs. Android implements it in androidMain; another platform
 * (iOS, desktop) only has to provide its own implementation — screens stay unchanged.
 * Callbacks run on the main thread.
 */
interface PlatformServices {
    // security
    fun hashPin(pin: String): String
    /** Re-checked whenever the app returns to the foreground (e.g. after enrolling a fingerprint in Settings). */
    val biometricStatus: StateFlow<BiometricStatus>
    /** Opens the system screen for adding a fingerprint / face. */
    fun openBiometricEnrollment()
    /**
     * Shows the system biometric prompt. [onError] gets a user-facing message for real failures
     * (lockout, sensor unavailable); it is not called when the user simply taps "Use passcode".
     */
    fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit = {})

    // photos (stored privately, referenced by file name)
    fun pickPhoto(onPicked: (fileName: String) -> Unit)
    fun takePhoto(onTaken: (fileName: String) -> Unit)
    fun deletePhotoFile(fileName: String)
    @Composable fun PhotoImage(fileName: String, modifier: Modifier)
    /** Whole photo, scaled to fit (full-screen viewer). */
    @Composable fun PhotoFull(fileName: String, modifier: Modifier)

    // input helpers
    val voiceAvailable: Boolean
    fun listen(onText: (String) -> Unit)
    fun currentLocation(onResult: (lat: Double, lng: Double) -> Unit, onUnavailable: () -> Unit)
    fun openMap(lat: Double, lng: Double)

    // background features; each returns via [onResult] whether it is now active (permissions may be refused)
    fun setReminder(enabled: Boolean, hour: Int, minute: Int, onResult: (Boolean) -> Unit)
    fun setQuickAddNotification(enabled: Boolean, onResult: (Boolean) -> Unit)
    fun setMessageCapture(enabled: Boolean, onResult: (Boolean) -> Unit)
    /** Reads bank messages already in the SMS inbox (last [days] days); [onResult] gets how many were added, null if refused. */
    fun importInboxMessages(days: Int, onResult: (Int?) -> Unit)
    fun refreshWidgets()

    // languages
    /** The phone's language tag, e.g. "hi-IN". */
    fun systemLanguage(): String
    /** Translation table for a shipped language tag (English text → translation); empty if missing. */
    fun translations(tag: String): Map<String, String>

    // about
    /** Version shown to users, e.g. "0.1.0-build.42". */
    val appVersion: String
    /** A license text shipped with the app (e.g. "GPL-3.0"), or null if missing. */
    fun licenseText(id: String): String?
    /** Opens a web page in the browser (the app itself has no network access). */
    fun openUrl(url: String)
}

enum class BiometricStatus { AVAILABLE, NOT_ENROLLED, UNSUPPORTED }

val LocalPlatform = compositionLocalOf<PlatformServices> { error("No platform services") }

/** One-shot actions requested from outside the UI (shortcut, widget, notification). */
enum class LaunchAction { ADD_EXPENSE, ADD_INCOME, ADD_TRANSFER, VOICE, INBOX, SEARCH }
