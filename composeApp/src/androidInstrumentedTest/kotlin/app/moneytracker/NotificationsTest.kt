package app.moneytracker

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Run on a fresh debug install; channel sound/importance remain under the user's control. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun detectedNotificationsShareOneKindGroupAndAudibleDefaultPriority() {
        val first = Notifications.detectedNotification(context, "HDFC card: spent ₹250", "Tap to review and add")
        val second = Notifications.detectedNotification(context, "ICICI bank: spent ₹500", "Saved automatically")
        assertEquals(Notifications.CH_DETECTED, first.group)
        assertEquals(Notifications.CH_DETECTED, second.group)
        assertEquals(first.group, second.group)
        assertFalse(first.group == Notifications.CH_QUICK)
        assertFalse(first.group == Notifications.CH_REMINDER)
        assertEquals(Notification.PRIORITY_DEFAULT, first.priority)
        if (Build.VERSION.SDK_INT >= 26) assertEquals(Notification.GROUP_ALERT_CHILDREN, first.groupAlertBehavior)
        // On Android 8+, NotificationCompat delegates sound to the channel and clears defaults.
        if (Build.VERSION.SDK_INT < 26) assertEquals(Notification.DEFAULT_SOUND, first.defaults and Notification.DEFAULT_SOUND)
        assertEquals(0, first.flags and Notification.FLAG_GROUP_SUMMARY)
        if (Build.VERSION.SDK_INT >= 26) assertEquals(Notifications.CH_DETECTED, first.channelId)
    }

    @Test fun detectedSummaryBundlesOnlySmsAndDoesNotAlertTwice() {
        val summary = Notifications.detectedSummary(context)
        assertEquals(Notifications.CH_DETECTED, summary.group)
        assertEquals(Notification.FLAG_GROUP_SUMMARY, summary.flags and Notification.FLAG_GROUP_SUMMARY)
        if (Build.VERSION.SDK_INT >= 26) assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        assertEquals(Notification.PRIORITY_DEFAULT, summary.priority)
    }

    @Test fun detectedChannelDefaultsSurviveRecreation() {
        if (Build.VERSION.SDK_INT < 26) return
        Notifications.createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(Notifications.CH_DETECTED)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        assertEquals(android.media.AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertEquals(setOf(Notifications.CH_REMINDER, Notifications.CH_QUICK, Notifications.CH_DETECTED),
            manager.notificationChannels.map { it.id }.toSet())
        Notifications.createChannels(context)
        val recreated = manager.getNotificationChannel(Notifications.CH_DETECTED)
        assertEquals(channel.sound, recreated.sound)
        assertEquals(channel.importance, recreated.importance)
    }
}
