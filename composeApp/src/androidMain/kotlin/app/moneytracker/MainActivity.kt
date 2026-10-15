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
    private var locked by mutableStateOf(true)
    private var launchAction by mutableStateOf<LaunchAction?>(null)
    private var backgroundedAt = 0L
    private lateinit var platform: AndroidPlatform

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = false)
        val db = (application as MoneyTrackerApp).database
        platform = AndroidPlatform(this)
        val backup = AndroidBackupController(this, db, hashPin = platform::hashPin)
        backup.autoBackupIfDue()
        launchAction = Launch.parse(intent)
        readSystemAccent()

        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { navigator.back() }
        }
        onBackPressedDispatcher.addCallback(this, back)

        setContent {
            LaunchedEffect(Unit) { snapshotFlow { navigator.canPop }.collect { back.isEnabled = it } }
            App(
                repo = db.repo, backup = backup, navigator = navigator, platform = platform,
                locked = locked, onUnlocked = { locked = false },
                launchAction = launchAction, onLaunchHandled = { launchAction = null },
                onDarkThemeChanged = ::applySystemBars,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Launch.parse(intent)?.let { launchAction = it }
    }

    override fun onStart() {
        super.onStart()
        // Re-lock after a minute in the background (App only shows the lock if a passcode is set).
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > LOCK_AFTER_MS) locked = true
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
        backgroundedAt = SystemClock.elapsedRealtime()
        SummaryWidget.updateAll(this)
    }

    companion object { private const val LOCK_AFTER_MS = 60_000L }

    /** Transparent bars; icons dark on the light style, light on the dark style (whatever the phone's own mode). */
    private fun applySystemBars(dark: Boolean) {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
