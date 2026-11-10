package app.moneytracker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.fragment.app.FragmentActivity
import app.moneytracker.ui.App
import app.moneytracker.ui.LaunchAction
import app.moneytracker.ui.Navigator

/** FragmentActivity (not just ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {
    private val navigator = Navigator()
    private val process get() = application as MoneyTrackerApp
    private var locked by mutableStateOf(true)
    private var launchAction by mutableStateOf<LaunchAction?>(null)
    private lateinit var platform: AndroidPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = false)
        val db = (application as MoneyTrackerApp).database
        platform = AndroidPlatform(this)
        // Older passcode hashes only work with this install's salt; store them with it so backups carry the passcode.
        db.repo?.let { repo ->
            val hash = repo.settingNow(app.moneytracker.data.MoneyRepository.Keys.PASSCODE)
            if (!hash.isNullOrBlank() && !app.moneytracker.ui.isPortablePinHash(hash))
                platform.portablePinHash(hash)?.let { repo.putSetting(app.moneytracker.data.MoneyRepository.Keys.PASSCODE, it) }
        }
        val backup = AndroidBackupController(this, db, hashPin = platform::hashPin, portablePinHash = platform::portablePinHash)
        backup.autoBackupIfDue()
        // Coming back after the app was closed in the background (or re-created): the same screens, and the launch
        // shortcut that opened it isn't replayed over them.
        val saved = savedInstanceState?.getStringArrayList(NAV_STACK)
        if (saved != null) navigator.restoreState(saved) else launchAction = Launch.parse(intent)
        if (skipLockOnce) { skipLockOnce = false; process.unlocked = true }
        locked = !process.unlocked
        // Layout is fixed for the life of this activity; Settings restarts the app to switch it.
        val newLayout = db.repo?.settingNow(app.moneytracker.data.MoneyRepository.Keys.UI_LAYOUT) == "NEW"
        readSystemAccent()

        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { navigator.back() }
        }
        onBackPressedDispatcher.addCallback(this, back)

        setContent {
            LaunchedEffect(Unit) { snapshotFlow { navigator.canPop }.collect { back.isEnabled = it } }
            App(
                repo = db.repo, backup = backup, navigator = navigator, platform = platform,
                locked = locked, onUnlocked = { locked = false; process.unlocked = true },
                launchAction = launchAction, onLaunchHandled = { launchAction = null },
                onDarkThemeChanged = ::applySystemBars,
                newLayout = newLayout,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Launch.parse(intent)?.let { launchAction = it }
    }

    override fun onStart() {
        super.onStart()
        val returning = process.backgroundedAt != 0L
        Log.d("App") { if (returning) "back on screen after ${(SystemClock.elapsedRealtime() - process.backgroundedAt) / 1000} s" else "on screen" }
        // Re-lock after a minute in the background (App only shows the lock if a passcode is set).
        if (returning && SystemClock.elapsedRealtime() - process.backgroundedAt > LOCK_AFTER_MS) { locked = true; process.unlocked = false }
        // One short thread per return to the screen; nothing is scheduled.
        Thread {
            if (returning) Log.attempt("App", "repeat entries on return") { (application as MoneyTrackerApp).database.repo?.generateRecurring() }   // cold start already ran it on open
            Log.attempt("App", "restoring quick add") { Notifications.restoreQuickAdd(applicationContext) }
            Log.attempt("Sms", "catch-up on open") { Detected.catchUp(applicationContext) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        platform.refreshBiometricStatus()
        readSystemAccent()   // the wallpaper may have changed while we were away
    }

    /** Wallpaper colours (Android 12+) for the "Follow system" accent: deep tone on light, soft tone on dark. */
    private fun readSystemAccent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        fun c(id: Int) = getColor(id).toLong() and 0xFFFFFFFFL
        val tones = app.moneytracker.ui.SystemAccent(
            light = c(android.R.color.system_accent1_600), dark = c(android.R.color.system_accent1_200),
            tintLight = c(android.R.color.system_accent1_100), tintDark = c(android.R.color.system_accent1_800),
        )
        if (app.moneytracker.ui.systemAccent != tones) app.moneytracker.ui.systemAccent = tones
    }

    override fun onStop() {
        super.onStop()
        process.backgroundedAt = SystemClock.elapsedRealtime()
        SummaryWidget.updateAll(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(NAV_STACK, navigator.saveState())
    }

    companion object {
        private const val LOCK_AFTER_MS = 60_000L
        private const val NAV_STACK = "navStack"
        /** Set just before a restart the user asked for, so the new activity doesn't ask for the passcode again. In-process only. */
        @Volatile var skipLockOnce = false
    }

    /** Transparent bars; icons dark on the light style, light on the dark style (whatever the phone's own mode). */
    private fun applySystemBars(dark: Boolean) {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
