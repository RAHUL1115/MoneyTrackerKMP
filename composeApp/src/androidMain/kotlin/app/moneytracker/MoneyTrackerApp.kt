package app.moneytracker

import android.app.Application

class MoneyTrackerApp : Application() {
    val database by lazy { DatabaseManager(this).also { it.open() } }

    /**
     * Unlock state lives with the process, not the activity: a re-created activity (system language change) stays
     * unlocked, while a new process (the app was closed in the background) always asks again. Main thread only.
     */
    var unlocked = false
    /** When the app last left the screen (elapsedRealtime), for re-locking after a while away; 0 = not yet. */
    var backgroundedAt = 0L

    override fun onCreate() {
        super.onCreate()
        AndroidLog.install(this)
        Notifications.createChannels(this)
    }
}
