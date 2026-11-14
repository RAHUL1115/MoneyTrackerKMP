package app.moneytracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import app.moneytracker.core.WebDashboardControl
import kotlinx.coroutines.flow.StateFlow

/**
 * Starts and stops the same-Wi-Fi web dashboard for the shared UI. [askNotificationPermission] asks for the
 * notification permission (it needs the activity's permission launcher) and then calls back.
 */
class AndroidWebDashboardControl(
    private val activity: Activity,
    private val askNotificationPermission: (then: (Boolean) -> Unit) -> Unit,
) : WebDashboardControl {
    private val ctx: Context get() = activity.applicationContext

    override val state: StateFlow<app.moneytracker.core.WebDashboardState> = app.moneytracker.web.WebDashboard.state

    override fun refreshWebDashboard() = app.moneytracker.web.WebDashboard.refresh()

    override fun startWebDashboard(onResult: (Boolean) -> Unit) {
        // The notification is how it's stopped from outside the app, so ask for it; the dashboard runs either way.
        askNotificationPermission { _ ->
            // Listen first, so a missing network or busy port is reported here; the service then keeps it running.
            if (app.moneytracker.web.WebDashboard.startServer(ctx) == null) return@askNotificationPermission onResult(false)
            app.moneytracker.web.WebDashboardService.start(ctx)
            onResult(true)
        }
    }

    override fun stopWebDashboard() = app.moneytracker.web.WebDashboardService.stop(ctx)

    override fun disconnectWebComputer(id: String) = app.moneytracker.web.WebDashboard.disconnect(id)

    override fun setWebPassword(password: String?) {
        val repo = (ctx as MoneyTrackerApp).database.repo ?: return
        repo.settingsStore.putSetting(app.moneytracker.data.Prefs.WEB_PASSWORD, password?.let { app.moneytracker.web.WebPassword.hash(it) } ?: "")
        app.moneytracker.web.WebDashboard.signOutAll()
    }

    override fun openWifiSettings() {
        val panel = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
        runCatching { activity.startActivity(panel) }.onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
    }
}
