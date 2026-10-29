package app.moneytracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.AudioAttributes
import android.provider.Settings
import android.provider.Telephony
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.ui.LaunchAction
import app.moneytracker.ui.tr
import java.util.Calendar

/** Intent extra / action names shared by shortcuts, widgets and notifications. */
object Launch {
    const val EXTRA = "launchAction"
    fun intent(ctx: Context, action: LaunchAction): Intent =
        Intent(ctx, MainActivity::class.java).setAction("app.moneytracker.${action.name}").putExtra(EXTRA, action.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(ctx: Context, action: LaunchAction): PendingIntent = PendingIntent.getActivity(
        ctx, action.ordinal + 100, intent(ctx, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun parse(intent: Intent?): LaunchAction? =
        (intent?.getStringExtra(EXTRA) ?: intent?.action?.removePrefix("app.moneytracker."))
            ?.let { n -> LaunchAction.entries.firstOrNull { it.name == n } }
}

/** Translation tables shipped in assets/i18n/<tag>.json (English text → translation). */
object Translations {
    private val cache = mutableMapOf<String, Map<String, String>>()
    fun load(ctx: Context, tag: String): Map<String, String> = synchronized(cache) {
        cache.getOrPut(tag) {
            runCatching {
                val o = org.json.JSONObject(ctx.assets.open("i18n/$tag.json").bufferedReader().use { it.readText() })
                o.keys().asSequence().associateWith { o.getString(it) }
            }.getOrDefault(emptyMap())
        }
    }

    /** For notifications and widgets (no Compose): the table the user picked, or the phone's language. */
    fun current(ctx: Context, setting: String?): Map<String, String> =
        load(ctx, setting?.takeIf { it.isNotEmpty() } ?: app.moneytracker.ui.I18n.resolve(
            android.content.res.Resources.getSystem().configuration.locales[0].toLanguageTag()))
}

private fun repo(ctx: Context) = (ctx.applicationContext as MoneyTrackerApp).database.let { it.repo ?: it.open() }

/** Receivers and widgets can run without the UI: load the user's language before building text. */
private fun ensureTranslations(ctx: Context, r: app.moneytracker.data.MoneyRepository = repo(ctx)) {
    if (app.moneytracker.ui.I18n.table.isEmpty()) app.moneytracker.ui.I18n.table = Translations.current(ctx, r.settingNow(Keys.LANGUAGE))
}

// ───────────────────────── notifications

object Notifications {
    const val CH_REMINDER = "reminder"
    const val CH_QUICK = "quick_add"
    const val CH_DETECTED = "detected"
    private const val ID_REMINDER = 1
    private const val ID_QUICK = 2
    internal const val ID_DETECTED_SUMMARY = 3

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, tr("Daily reminder"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = tr("A reminder at the time you choose to record the day's spending.")
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUICK, tr("Quick add"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("Silent, always-on buttons to add income, expenses or transfers from the notification shade.")
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DETECTED, tr("Detected transactions"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = tr("Alerts when a bank SMS looks like a transaction, whether it was saved automatically or is waiting for your review.")
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: android.app.Notification, tag: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        createChannels(ctx)   // refreshes channel names in the current language
        NotificationManagerCompat.from(ctx).notify(tag, id, n)
    }

    fun showReminder(ctx: Context) = post(ctx, ID_REMINDER, NotificationCompat.Builder(ctx, CH_REMINDER)
        .setGroup(CH_REMINDER)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr("Recorded today's spending?"))
        .setContentText(tr("Tap to add anything you missed."))
        .setContentIntent(Launch.pending(ctx, LaunchAction.ADD_EXPENSE))
        .setAutoCancel(true).build())

    /** Icon buttons for income / expense / transfer. Only a collapsed view is set, so the notification has no expand toggle. */
    private fun quickAddView(ctx: Context) = RemoteViews(ctx.packageName, R.layout.notification_quick_add).apply {
        setOnClickPendingIntent(R.id.qa_income, Launch.pending(ctx, LaunchAction.ADD_INCOME))
        setOnClickPendingIntent(R.id.qa_expense, Launch.pending(ctx, LaunchAction.ADD_EXPENSE))
        setOnClickPendingIntent(R.id.qa_transfer, Launch.pending(ctx, LaunchAction.ADD_TRANSFER))
        setContentDescription(R.id.qa_income, tr("Add income"))
        setContentDescription(R.id.qa_expense, tr("Add expense"))
        setContentDescription(R.id.qa_transfer, tr("Add transfer"))
    }

    /**
     * Android 14+ lets the user swipe away even ongoing notifications, so the delete intent
     * re-posts it through [QuickAddDismissReceiver] while the setting is on.
     */
    fun showQuickAdd(ctx: Context) = post(ctx, ID_QUICK, NotificationCompat.Builder(ctx, CH_QUICK)
        .setGroup(CH_QUICK)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr("Quick add")).setColor(0xFF0F766E.toInt())
        .setOngoing(true).setAutoCancel(false).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_LOW)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .setCustomContentView(quickAddView(ctx))
        .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setDeleteIntent(PendingIntent.getBroadcast(ctx, ID_QUICK, Intent(ctx, QuickAddDismissReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build().apply { flags = flags or android.app.Notification.FLAG_NO_CLEAR })

    fun cancelQuickAdd(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_QUICK)

    /** Puts quick add back if the setting is on and it isn't showing (swiped away, or cleared while the app was stopped). */
    fun restoreQuickAdd(ctx: Context) {
        val r = repo(ctx)
        if (r.settingNow(Keys.QUICK_ADD_NOTIFICATION) != "1") return
        if (ctx.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ID_QUICK }) return
        ensureTranslations(ctx, r)
        showQuickAdd(ctx)
    }

    fun showDetected(ctx: Context, id: String, title: String, text: String) {
        post(ctx, 1000, detectedNotification(ctx, title, text), tag = id)
        post(ctx, ID_DETECTED_SUMMARY, detectedSummary(ctx))
    }

    internal fun detectedNotification(ctx: Context, title: String, text: String): android.app.Notification =
        NotificationCompat.Builder(ctx, CH_DETECTED)
            .setSmallIcon(R.drawable.ic_notification)
            // All SMS detections share a kind-based group, regardless of bank or instrument.
            .setGroup(CH_DETECTED)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(Launch.pending(ctx, LaunchAction.INBOX))
            .setAutoCancel(true).build()

    /** A shared summary makes Android bundle SMS alerts without bundling reminder / quick add. */
    internal fun detectedSummary(ctx: Context): android.app.Notification =
        NotificationCompat.Builder(ctx, CH_DETECTED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr("Detected transactions"))
            .setContentText(tr("Tap to review and add"))
            .setContentIntent(Launch.pending(ctx, LaunchAction.INBOX))
            .setGroup(CH_DETECTED).setGroupSummary(true)
            // The individual incoming SMS alerts make sound, never the summary update.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true).build()
}

// ───────────────────────── daily reminder

object Reminders {
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** One alarm at a time, re-armed when it fires: on time even in Doze, no exact-alarm permission. */
    fun schedule(ctx: Context, hour: Int, minute: Int) {
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pending(ctx))
    }

    /** Arms the next reminder from the saved time, if the reminder is on. */
    fun rearm(ctx: Context, r: app.moneytracker.data.MoneyRepository = repo(ctx)) {
        if (r.settingNow(Keys.REMINDER) != "1") return
        val (h, m) = (r.settingNow(Keys.REMINDER_TIME) ?: "21:00").split(":").map { it.toIntOrNull() ?: 0 }
        schedule(ctx, h, m)
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))

    /** Re-applies saved settings (after reboot or app update). */
    fun restore(ctx: Context) {
        val r = repo(ctx)
        ensureTranslations(ctx, r)
        rearm(ctx, r)
        if (r.settingNow(Keys.QUICK_ADD_NOTIFICATION) == "1") Notifications.showQuickAdd(ctx)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread { try { ensureTranslations(ctx); Notifications.showReminder(ctx); Reminders.rearm(ctx) } finally { pending.finish() } }.start()
    }
}

/** The quick-add notification was swiped away: put it back unless the user turned it off. */
class QuickAddDismissReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread { try { Notifications.restoreQuickAdd(ctx) } finally { pending.finish() } }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread { try { Reminders.restore(ctx); runCatching { Detected.catchUp(ctx) }; SummaryWidget.updateAll(ctx) } finally { pending.finish() } }.start()
    }
}

// ───────────────────────── SMS capture

/**
 * Disabled in the manifest; enabled only when the user turns on "Detect bank SMS" (and grants the permission).
 * Parsing is regex + a tiny learned table, so the receiver finishes in milliseconds.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        Thread {
            try {
                val r = repo(ctx)
                messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, parts) ->
                    val text = parts.joinToString("") { it.messageBody.orEmpty() }
                    r.ingestMessage("SMS", sender, text, parts.first().timestampMillis)?.let { Detected.alert(ctx, r, it) }
                }
            } finally { pending.finish() }
        }.start()
    }
}

/** Shared by SMS and the inbox import. */
object Detected {
    fun alert(ctx: Context, r: app.moneytracker.data.MoneyRepository, c: app.moneytracker.domain.Capture) {
        if (r.settingNow(Keys.SMS_ALERT) == "0") return
        ensureTranslations(ctx, r)
        val fmt = app.moneytracker.domain.CurrencyFormat(r.settingNow(Keys.CURRENCY_SYMBOL) ?: "₹", r.settingNow(Keys.CURRENCY_DECIMALS)?.toIntOrNull() ?: 2)
        val amount = c.amount?.let { fmt.format(kotlin.math.abs(it)) }.orEmpty()
        val saved = c.status == app.moneytracker.domain.CaptureStatus.SAVED
        val headline = when {
            app.moneytracker.domain.isRefund(c.kind, c.amount) -> tr("Refund {0}", amount)
            c.kind?.name == "INCOME" -> tr("Received {0}", amount)
            else -> tr("Spent {0}", amount)
        }
        Notifications.showDetected(ctx, c.id,
            headline + c.merchant.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            if (saved) tr("Saved automatically") else tr("Tap to review and add"))
    }

    /** "Import SMS": reads the inbox for the last [days] days; returns how many new messages were picked up. */
    fun importInbox(ctx: Context, days: Int): Int = importSince(ctx, System.currentTimeMillis() - days * DAY_MS)

    /**
     * SMS that arrived while the receiver couldn't run (app force-stopped, or killed by an aggressive
     * battery saver) are picked up on the next app open or boot. Duplicates are dropped by [ingestMessage].
     */
    fun catchUp(ctx: Context) {
        if (ctx.packageManager.getComponentEnabledSetting(ComponentName(ctx, SmsReceiver::class.java)) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val r = repo(ctx)
        val now = System.currentTimeMillis()
        val last = r.settingNow(Keys.SMS_LAST_SCAN)?.toLongOrNull() ?: (now - DAY_MS)
        importSince(ctx, maxOf(last - 60 * 60 * 1000, now - 7 * DAY_MS))   // an hour of overlap; at most a week back
        r.putSetting(Keys.SMS_LAST_SCAN, now.toString())
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun importSince(ctx: Context, since: Long): Int {
        val r = repo(ctx)
        var added = 0
        ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (r.ingestMessage("SMS", c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2)) != null) added++
            }
        }
        return added
    }
}

// ───────────────────────── home-screen widget

/** Theme, accent, opacity and money format shared by all widgets (Configuration › Widget settings). */
private class WidgetStyle(ctx: Context, r: app.moneytracker.data.MoneyRepository) {
    init { ensureTranslations(ctx, r) }
    private val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dark = when (r.settingNow(Keys.WIDGET_THEME)) { "DARK" -> true; "LIGHT" -> false; else -> night }
    private val opacity = (r.settingNow(Keys.WIDGET_OPACITY)?.toIntOrNull() ?: 100).coerceIn(0, 100)
    private val swapped = r.settingNow(Keys.COLOR_SET) == "B"
    val ink = if (dark) 0xFFECEDEF.toInt() else 0xFF1B1C1F.toInt()
    val subtle = if (dark) 0xFF8F949E.toInt() else 0xFF8A8F99.toInt()
    private val surface = if (dark) 0xFF1A1B1E.toInt() else 0xFFFFFFFF.toInt()
    /** Inset panels and card tiles: the app's page grey. */
    val panel = if (dark) 0xFF26282D.toInt() else 0xFFF3F4F6.toInt()
    val incomeColor = if (swapped) 0xFFE5484D.toInt() else 0xFF16A34A.toInt()
    val expenseColor = if (swapped) 0xFF16A34A.toInt() else 0xFFE5484D.toInt()
    /** The app's accent and its light tint; "Follow system" reads the wallpaper colours like MainActivity does. */
    private val accentChoice = app.moneytracker.ui.AccentChoice.entries.firstOrNull { it.name == r.settingNow(Keys.ACCENT) } ?: app.moneytracker.ui.AccentChoice.TEAL
    private val systemAccent = accentChoice == app.moneytracker.ui.AccentChoice.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val accent: Int =
        if (systemAccent) ctx.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        else (if (dark) accentChoice.dark else accentChoice.light).toInt()
    val accentTint: Int =
        if (systemAccent) ctx.getColor(if (dark) android.R.color.system_accent1_800 else android.R.color.system_accent1_100)
        else (if (dark) accentChoice.tintDark else accentChoice.tintLight).toInt()
    val fmt = app.moneytracker.domain.CurrencyFormat(r.settingNow(Keys.CURRENCY_SYMBOL) ?: "₹",
        r.settingNow(Keys.CURRENCY_DECIMALS)?.toIntOrNull() ?: 2, r.settingNow(Keys.SYMBOL_AFTER) == "1")

    fun background(v: RemoteViews) {
        v.setInt(R.id.w_bg, "setColorFilter", surface)
        v.setInt(R.id.w_bg, "setImageAlpha", opacity * 255 / 100)
    }
    fun openApp(ctx: Context, v: RemoteViews) =
        v.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    /** Fixed labels from the layouts, set here so widgets follow the app language. */
    fun labels(v: RemoteViews, vararg idToText: Pair<Int, String>) = idToText.forEach { (id, text) -> v.setTextViewText(id, tr(text)) }

    /** The app's two add buttons: income on the accent tint, and the accent "+" for an expense. */
    fun addButtons(ctx: Context, v: RemoteViews) {
        v.setInt(R.id.w_add_expense_bg, "setColorFilter", accent)
        v.setContentDescription(R.id.w_add_expense, tr("Add expense"))
        v.setOnClickPendingIntent(R.id.w_add_expense, Launch.pending(ctx, LaunchAction.ADD_EXPENSE))
        v.setInt(R.id.w_add_income_bg, "setColorFilter", accentTint)
        v.setInt(R.id.w_add_income_icon, "setColorFilter", incomeColor)
        v.setContentDescription(R.id.w_add_income, tr("Add income"))
        v.setOnClickPendingIntent(R.id.w_add_income, Launch.pending(ctx, LaunchAction.ADD_INCOME))
    }

    /** Colours, labels and taps of the Expense / Income / Transfer pills; icon-only when [labels] don't fit. */
    fun pills(ctx: Context, v: RemoteViews, labels: Boolean) {
        data class Pill(val root: Int, val bg: Int, val icon: Int, val label: Int, val text: String, val description: String, val color: Int, val action: LaunchAction)
        listOf(
            Pill(R.id.w_add_expense, R.id.w_add_expense_bg, R.id.w_add_expense_icon, R.id.w_add_expense_label, "Expense", "Add expense", expenseColor, LaunchAction.ADD_EXPENSE),
            Pill(R.id.w_add_income, R.id.w_add_income_bg, R.id.w_add_income_icon, R.id.w_add_income_label, "Income", "Add income", incomeColor, LaunchAction.ADD_INCOME),
            Pill(R.id.w_add_transfer, R.id.w_add_transfer_bg, R.id.w_add_transfer_icon, R.id.w_add_transfer_label, "Transfer", "Add transfer", subtle, LaunchAction.ADD_TRANSFER),
        ).forEach { p ->
            // The pill shape keeps its low alpha; the filter only changes its hue.
            v.setInt(p.bg, "setColorFilter", p.color)
            v.setInt(p.icon, "setColorFilter", p.color)
            v.setTextViewText(p.label, tr(p.text)); v.setTextColor(p.label, ink)
            v.setViewVisibility(p.label, if (labels) android.view.View.VISIBLE else android.view.View.GONE)
            v.setContentDescription(p.root, tr(p.description))
            v.setOnClickPendingIntent(p.root, Launch.pending(ctx, p.action))
        }
    }
}

/** A widget's current size in dp (portrait width); 0 when the launcher doesn't report it. */
internal class WidgetSize(val widthDp: Int)
internal fun sizeOf(manager: AppWidgetManager, id: Int) = WidgetSize(manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))

/** Base for the home-screen widgets: renders off the main thread with the user's data. */
abstract class MoneyWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try { ids.forEach { manager.updateAppWidget(it, build(ctx, repo(ctx), sizeOf(manager, it))) }; afterUpdate(manager, ids) }
            finally { pending.finish() }
        }.start()
    }

    /** Called after the widgets were redrawn, e.g. to reload list rows. */
    open fun afterUpdate(manager: AppWidgetManager, ids: IntArray) {}

    /** Resized: rebuild so the layout fits the new width. */
    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        val pending = goAsync()
        Thread { try { manager.updateAppWidget(id, build(ctx, repo(ctx), sizeOf(manager, id))) } finally { pending.finish() } }.start()
    }

    internal abstract fun build(ctx: Context, r: app.moneytracker.data.MoneyRepository, size: WidgetSize): RemoteViews

    protected fun monthLabel(r: app.moneytracker.data.MoneyRepository): Pair<String, app.moneytracker.domain.YearMonth> {
        val label = r.periodSettingsNow().labelFor(r.today())
        return (java.text.DateFormatSymbols().shortMonths[label.month - 1] + " " + label.year) to label
    }
}

/** This month like the app's main screen: month, income / expenses / total and the add buttons (fixed 4x1). */
class SummaryWidget : MoneyWidget() {
    override fun build(ctx: Context, r: app.moneytracker.data.MoneyRepository, size: WidgetSize): RemoteViews {
        val st = WidgetStyle(ctx, r)
        val (month, label) = monthLabel(r)
        val t = r.totalsNow(r.periodSettingsNow().month(label))
        val v = RemoteViews(ctx.packageName, R.layout.widget_summary)
        st.background(v)
        v.setTextViewText(R.id.w_month, month); v.setTextColor(R.id.w_month, st.ink)
        listOf(R.id.w_l_income, R.id.w_l_expense, R.id.w_l_total).forEach { v.setTextColor(it, st.subtle) }
        st.labels(v, R.id.w_l_income to "Income", R.id.w_l_expense to "Expenses", R.id.w_l_total to "Total")
        v.setTextViewText(R.id.w_income, st.fmt.format(t.income)); v.setTextColor(R.id.w_income, st.incomeColor)
        v.setTextViewText(R.id.w_expense, st.fmt.format(t.expense)); v.setTextColor(R.id.w_expense, st.expenseColor)
        v.setTextViewText(R.id.w_total, st.fmt.format(t.net)); v.setTextColor(R.id.w_total, st.ink)
        st.openApp(ctx, v); st.addButtons(ctx, v)
        return v
    }

    companion object {
        /** Refreshes every kind of widget after data or settings change. */
        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            listOf(SummaryWidget(), QuickAddWidget(), CardsWidget()).forEach { w ->
                val ids = manager.getAppWidgetIds(ComponentName(ctx, w::class.java))
                if (ids.isNotEmpty()) Thread {
                    ids.forEach { manager.updateAppWidget(it, w.build(ctx, repo(ctx), sizeOf(manager, it))) }
                    w.afterUpdate(manager, ids)
                }.start()
            }
        }
    }
}

/** Quick-add widget: Expense / Income / Transfer pills. */
class QuickAddWidget : MoneyWidget() {
    override fun build(ctx: Context, r: app.moneytracker.data.MoneyRepository, size: WidgetSize): RemoteViews {
        val st = WidgetStyle(ctx, r)
        val v = RemoteViews(ctx.packageName, R.layout.widget_quick_add)
        st.background(v); st.pills(ctx, v, labels = size.widthDp == 0 || size.widthDp >= 200)
        return v
    }
}

/** Credit cards: a scrolling list of every card with what's due and this cycle's spending (4x2). */
class CardsWidget : MoneyWidget() {
    override fun build(ctx: Context, r: app.moneytracker.data.MoneyRepository, size: WidgetSize): RemoteViews {
        val st = WidgetStyle(ctx, r)
        val v = RemoteViews(ctx.packageName, R.layout.widget_cards)
        st.background(v)
        v.setTextColor(R.id.w_title, st.ink); v.setTextColor(R.id.w_empty, st.subtle)
        st.labels(v, R.id.w_title to "Credit cards", R.id.w_empty to "No credit cards")
        v.setRemoteAdapter(R.id.w_list, Intent(ctx, CardsWidgetService::class.java))
        v.setEmptyView(R.id.w_list, R.id.w_empty)
        // Rows fill in this template; a template must be mutable on Android 12+.
        v.setPendingIntentTemplate(R.id.w_list, PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)))
        st.openApp(ctx, v)
        return v
    }

    /** The list's rows come from [CardsWidgetService]; ask it to reload. */
    override fun afterUpdate(manager: AppWidgetManager, ids: IntArray) = manager.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
}

/** Rows for [CardsWidget]'s list. */
class CardsWidgetService : android.widget.RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var rows: List<RemoteViews> = emptyList()

        /** Runs on a binder thread, so reading the database here is fine. */
        override fun onDataSetChanged() {
            val ctx = applicationContext
            val r = repo(ctx)
            val st = WidgetStyle(ctx, r)
            val cardGroups = r.groupsNow().filter { it.type.isCreditCard }.map { it.id }.toSet()
            val prepaid = r.txnIdsWithTag(app.moneytracker.domain.TAG_PREPAYMENT)
            rows = r.accountsNow().filter { it.groupId in cardGroups && !it.hidden }.map { a ->
                val s = app.moneytracker.domain.cardStatus(r.accountTxns(a.id), a.cardSettlementDay ?: 1, r.today(), prepaid)
                val owed = s.statementDue + s.overdue
                RemoteViews(ctx.packageName, R.layout.widget_card_item).apply {
                    setInt(R.id.w_card_bg, "setColorFilter", st.panel)
                    setTextViewText(R.id.w_card_name, a.name); setTextColor(R.id.w_card_name, st.ink)
                    setTextViewText(R.id.w_card_cycle, tr("This cycle {0}", st.fmt.format(s.cycleSpend))); setTextColor(R.id.w_card_cycle, st.subtle)
                    setTextViewText(R.id.w_card_due, tr("Due {0}", st.fmt.format(owed))); setTextColor(R.id.w_card_due, if (owed > 0) st.expenseColor else st.subtle)
                    setOnClickFillInIntent(R.id.w_card, Intent())
                }
            }
        }

        override fun onCreate() {}
        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int) = rows[position]
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
