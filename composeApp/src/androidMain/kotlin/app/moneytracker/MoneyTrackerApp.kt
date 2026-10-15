package app.moneytracker

import android.app.Application

class MoneyTrackerApp : Application() {
    val database by lazy { DatabaseManager(this).also { it.open() } }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
