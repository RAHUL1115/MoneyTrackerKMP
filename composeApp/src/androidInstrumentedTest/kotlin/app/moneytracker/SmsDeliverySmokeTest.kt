package app.moneytracker

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.moneytracker.data.MoneyRepository
import app.moneytracker.domain.Account
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in emulator smoke test. Adds synthetic accounts/messages and leaves notifications for inspection. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class SmsDeliverySmokeTest {
    @Test fun incomingPlatformSmsSharesDetectedGroupSeparateFromOtherKinds() = runBlocking {
        assumeTrue("Explicit opt-in required: -e smsSmoke true",
            InstrumentationRegistry.getArguments().getString("smsSmoke") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val app = ctx.applicationContext as MoneyTrackerApp
        val repo = app.database.repo ?: app.database.open()
        repo.seedIfEmpty()
        repo.saveAccount(Account("smoke_hdfc_bank", "grp_bank", "HDFC Bank", smsSender = "HDFCBK"))
        repo.saveAccount(Account("smoke_hdfc_card", "grp_credit_card", "HDFC Credit Card", cardSettlementDay = 1,
            cardPaymentAccountId = "smoke_hdfc_bank", smsSender = "HDFCBK"))
        repo.putSetting(MoneyRepository.Keys.SMS_AUTO_SAVE, "0")
        repo.putSetting(MoneyRepository.Keys.SMS_ALERT, "1")
        repo.putSetting(MoneyRepository.Keys.QUICK_ADD_NOTIFICATION, "1")
        Notifications.showQuickAdd(ctx)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        // Opt-in synthetic test: discard previous debug-app SMS alerts before checking grouping.
        manager.activeNotifications.filter { it.notification.channelId == Notifications.CH_DETECTED }
            .forEach { manager.cancel(it.tag, it.id) }
        if (manager.activeNotifications.none { it.id == 1 && it.tag == null }) {
            Notifications.showReminder(ctx)
            Thread.sleep(2000) // Do not let the reminder rate-limit the first SMS sound.
        }
        val count = (InstrumentationRegistry.getArguments().getString("smsCount")?.toIntOrNull() ?: 4).coerceIn(1, 4)
        val runTag = System.currentTimeMillis().toString().takeLast(10)
        val messages = (0 until count).map { i ->
            if (i % 2 == 0) "Rs. ${250 + i} spent on your credit card xx1234 at SMOKESHOP on 28-oct-26 (UPI ref: $runTag$i). - HDFC"
            else "Rs. ${250 + i} debited from your HDFC account xx5678 to SMOKESHOP on 28-oct-26 (UPI ref: $runTag$i)."
        }
        for (text in messages) {
            inject(ctx, "AD-HDFCBK-S", text)
            // Space alerts so Android's per-app sound rate limiting does not suppress the next one.
            Thread.sleep(2000)
        }
        val captures = withTimeout(20_000) {
            repo.pendingCaptures.first { rows -> rows.count { it.text in messages } == messages.size }
                .filter { it.text in messages }
        }
        assertEquals(count, captures.size)
        for (capture in captures) {
            assertEquals(if ("credit card" in capture.text) "smoke_hdfc_card" else "smoke_hdfc_bank", capture.accountId, capture.text)
        }
        val alerts = manager.activeNotifications.filter { n -> captures.any { it.id == n.tag } }
        assertEquals(count, alerts.size)
        assertEquals(1, alerts.map { it.groupKey }.distinct().size)
        assertTrue(alerts.all { it.notification.channelId == Notifications.CH_DETECTED })
        assertTrue(alerts.all { it.notification.group == Notifications.CH_DETECTED })
        assertTrue(alerts.none { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 })
        val summary = manager.activeNotifications.single { it.id == Notifications.ID_DETECTED_SUMMARY && it.tag == null }
        assertEquals(alerts.first().groupKey, summary.groupKey)
        assertTrue(summary.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(setOf(Notifications.CH_DETECTED, Notifications.CH_QUICK, Notifications.CH_REMINDER),
            manager.activeNotifications.map { it.notification.group }.toSet())
        val channel = manager.getNotificationChannel(Notifications.CH_DETECTED)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNotNull(channel.sound)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "Delivered $count SMS PDUs through Android telephony; ${(count + 1) / 2} matched HDFC Credit Card and ${count / 2} matched HDFC Bank; one shared SMS group distinct from reminder and Quick add, default importance and sound configured.\n")
        })
    }

    private fun inject(ctx: Context, sender: String, text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pdu = deliverPdu(sender, text)
        val decoded = SmsMessage.createFromPdu(pdu, "3gpp")
        assertEquals(sender, decoded.originatingAddress)
        assertEquals(text, decoded.messageBody)
        val done = CountDownLatch(1)
        var result = Int.MIN_VALUE
        val action = "${ctx.packageName}.SMOKE_SMS_INJECTED"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { result = resultCode; done.countDown() }
        }
        ContextCompat.registerReceiver(ctx, receiver, android.content.IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        val callback = PendingIntent.getBroadcast(ctx, 123, Intent(action).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MODIFY_PHONE_STATE)
            SmsManager.getDefault().injectSmsPdu(pdu, "3gpp", callback)
            assertTrue(done.await(20, TimeUnit.SECONDS), "SMS injection callback timed out")
            assertEquals(Telephony.Sms.Intents.RESULT_SMS_HANDLED, result, "Platform SMS injection failed")
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            callback.cancel()
            ctx.unregisterReceiver(receiver)
        }
    }

    /** Minimal SMS-DELIVER encoder for the GSM-7 ASCII subset used by these synthetic messages. */
    private fun deliverPdu(sender: String, text: String): ByteArray = ByteArrayOutputStream().apply {
        require(text.length <= 160)
        write(0) // No SMSC address.
        write(4) // SMS-DELIVER.
        write((sender.length * 7 + 3) / 4)
        write(0xD0) // Alphanumeric originating address.
        write(pack(sender))
        write(0) // Protocol ID.
        write(0x11) // GSM-7, message class 1 (required by Android's injectSmsPdu API).
        val stamp = SimpleDateFormat("yyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        stamp.chunked(2).forEach { write(it[0].digitToInt() or (it[1].digitToInt() shl 4)) }
        write(0) // UTC offset.
        write(text.length)
        write(pack(text))
    }.toByteArray()

    private fun pack(text: String): ByteArray {
        require(text.all { it == ' ' || it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".:()-" })
        val packed = ByteArray((text.length * 7 + 7) / 8)
        text.forEachIndexed { index, char ->
            for (bit in 0..6) {
                if (char.code and (1 shl bit) != 0) {
                    val offset = index * 7 + bit
                    packed[offset / 8] = (packed[offset / 8].toInt() or (1 shl (offset % 8))).toByte()
                }
            }
        }
        return packed
    }
}
