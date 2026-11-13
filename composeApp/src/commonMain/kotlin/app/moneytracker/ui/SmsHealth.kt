package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Whether detection can run (SMS permission) and how the phone treats the app in the background; re-checked while shown. */
internal data class SmsHealth(val permission: Boolean, val background: BackgroundState)

@Composable
internal fun rememberSmsHealth(): SmsHealth {
    val backgroundJobs = LocalPlatform.current.backgroundJobs
    val messageCapture = LocalPlatform.current.messageCapture
    var health by remember { mutableStateOf(SmsHealth(messageCapture.messagePermissionGranted(), backgroundJobs.backgroundState())) }
    // No lifecycle hook in common code: a cheap re-check picks up changes made in the phone's settings.
    LaunchedEffect(messageCapture, backgroundJobs) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            health = SmsHealth(messageCapture.messagePermissionGranted(), backgroundJobs.backgroundState())
        }
    }
    return health
}

private val autostartMakers = listOf("vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "huawei", "honor", "letv")

/** Android 13+ blocks SMS permissions for apps installed outside a store until "restricted settings" are allowed. */
internal fun smsPermissionSteps(): String =
    tr("If Android says it's a restricted setting (common when installed from GitHub): open Settings › Apps › MoneyTracker, tap ⋮ › Allow restricted settings, then allow SMS under Permissions.")

/** Makers with their own autostart manager that the app knows how to open. */
internal fun hasAutostartManager(maker: String) = autostartMakers.any { it in maker }

/** Where this phone keeps the switches that let MoneyTracker start when an SMS arrives. */
internal fun backgroundSteps(maker: String): String = when {
    listOf("vivo", "iqoo").any { it in maker } -> tr("Settings › Battery › Background power consumption management › MoneyTracker › Allow. Then turn on MoneyTracker in Autostart (Settings › Apps › Autostart, or i Manager › App manager).")
    listOf("xiaomi", "redmi", "poco").any { it in maker } -> tr("Turn on Autostart for MoneyTracker, then set Battery saver to No restrictions (Settings › Apps › Manage apps › MoneyTracker).")
    listOf("oppo", "realme", "oneplus").any { it in maker } -> tr("Settings › Apps › MoneyTracker › Battery: allow background activity and auto launch.")
    "samsung" in maker -> tr("Settings › Battery › Background usage limits: make sure MoneyTracker isn't under Sleeping or Deep sleeping apps. In app info, set Battery to Unrestricted.")
    listOf("huawei", "honor").any { it in maker } -> tr("Settings › Battery › App launch › MoneyTracker: choose Manage manually and turn everything on.")
    else -> tr("Settings › Apps › MoneyTracker › Battery › Unrestricted.")
}
