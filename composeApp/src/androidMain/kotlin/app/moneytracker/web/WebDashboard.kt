package app.moneytracker.web

import app.moneytracker.Log
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteException
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.moneytracker.MainActivity
import app.moneytracker.MoneyTrackerApp
import app.moneytracker.R
import app.moneytracker.SummaryWidget
import app.moneytracker.data.Prefs
import app.moneytracker.ui.WebDashboardState
import app.moneytracker.ui.WebVisitor
import app.moneytracker.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * The web dashboard's server, owned by [WebDashboardService] while it runs. It listens only on the phone's
 * Wi-Fi (or hotspot) address, so it is never reachable over mobile data.
 */
object WebDashboard {
    private const val PORT_FIRST = 8080
    private const val PORT_LAST = 8089

    private val mutable = MutableStateFlow(WebDashboardState())
    val state: StateFlow<WebDashboardState> = mutable

    private var server: HttpServer? = null
    @Volatile private var api: WebApi? = null
    @Volatile private var host: String = ""
    private val assets = HashMap<String, ByteArray>()

    /** The phone's address on the local network: Wi-Fi first, then its own hotspot. Null on mobile data only. */
    fun localAddress(): Inet4Address? = try {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback && !it.isVirtual }
        fun rank(name: String) = when {
            name.startsWith("wlan") -> 0
            name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 1
            name.startsWith("eth") -> 2
            else -> 9
        }
        all.filter { rank(it.name) < 9 }.sortedBy { rank(it.name) }.firstNotNullOfOrNull { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
        }
    } catch (e: SocketException) {
        Log.w("Web", e) { "network interfaces unreadable" }
        null
    }

    fun refresh() {
        val ip = localAddress()
        mutable.value = mutable.value.copy(wifi = ip != null)
    }

    fun computers(): List<WebVisitor> = api?.computers()?.map { WebVisitor(it.id, it.name, it.lastSeen) }.orEmpty()

    fun lastVisit(): Long = api?.lastVisit ?: 0L

    fun disconnect(id: String) { api?.disconnect(id) }
    fun signOutAll() { api?.signOutAll() }

    /** Starts listening; returns the address, or null when there's no local network or no free port. */
    fun startServer(ctx: Context): String? {
        stopServer(null)
        val ip = localAddress() ?: run {
            Log.w("Web") { "not started: no Wi-Fi address" }
            mutable.value = WebDashboardState(wifi = false, error = tr("Connect to Wi-Fi first."))
            return null
        }
        val app = ctx.applicationContext
        val a = WebApi(
            repo = { (app as MoneyTrackerApp).database.repo },
            asset = { name -> pageFile(app, name) },
            host = { host },
            onVisit = { publish() },
        )
        val s = HttpServer(a::handle)
        val port = try {
            s.start(ip, PORT_FIRST..PORT_LAST)
        } catch (e: IOException) {
            Log.e("Web", e) { "server failed to start" }
            mutable.value = WebDashboardState(wifi = true, error = tr("Couldn't start: ports {0}–{1} are in use on this phone.", PORT_FIRST, PORT_LAST))
            return null
        }
        server = s
        api = a
        host = "${ip.hostAddress}:$port"
        mutable.value = WebDashboardState(running = true, wifi = true, address = host)
        Log.i("Web") { "dashboard started on port $port" }
        return host
    }

    /** A file of the browser page from assets/web, cached once read; null when there's no such file. */
    private fun pageFile(app: Context, name: String): ByteArray? = synchronized(assets) {
        assets[name] ?: try {
            app.assets.open("web/$name").use { it.readBytes() }.also { assets[name] = it }
        } catch (e: IOException) {
            Log.d("Web") { "no page file: ${e::class.simpleName}" }
            null
        }
    }

    fun stopServer(reason: String?) {
        if (server != null) Log.i("Web") { "dashboard stopped${reason?.let { ": $it" }.orEmpty()}" }
        server?.stop()
        server = null
        api = null
        val was = mutable.value
        mutable.value = WebDashboardState(running = false, wifi = localAddress() != null, stopReason = reason.takeIf { was.running })
    }

    /** The address the server is bound to is still the phone's. */
    fun stillOnNetwork(): Boolean = server != null && localAddress()?.hostAddress == host.substringBefore(':')

    fun publish() {
        val s = mutable.value
        if (s.running) mutable.value = s.copy(computers = computers())
        WebDashboardService.current?.updateNotification()
    }
}

/**
 * Keeps the web dashboard running while the app is in the background, with an ongoing notification that has
 * Stop and Copy address. It stops itself after the chosen idle time, and when the phone leaves the network.
 */
class WebDashboardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var stopping = false

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(null); return START_NOT_STICKY }
            ACTION_COPY -> {
                WebDashboard.state.value.address?.let { copy("http://$it") }
                return START_NOT_STICKY
            }
        }
        current = this
        stopping = false
        createChannel(this)
        // Foreground first (Android requires it within seconds of starting), then the server.
        startForegroundCompat(build(tr("Starting…")))
        if (!WebDashboard.state.value.running) {
            if (WebDashboard.startServer(this) == null) { shutdown(null, keepError = true); return START_NOT_STICKY }
        }
        acquireLocks()
        updateNotification()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, CHECK_MS)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    private fun check() {
        if (!WebDashboard.state.value.running) return shutdown(null)
        if (!WebDashboard.stillOnNetwork()) return shutdown(tr("Stopped because the phone left the Wi-Fi network."))
        val minutes = autoStopMinutes()
        if (minutes > 0 && System.currentTimeMillis() - WebDashboard.lastVisit() > minutes * 60_000L) {
            return shutdown(tr("Stopped after {0} minutes with no visits.", minutes))
        }
        WebDashboard.publish()
    }

    /** The chosen idle time; the default while the database is closed or being swapped by a restore. */
    private fun autoStopMinutes(): Int {
        val repo = (application as MoneyTrackerApp).database.repo ?: return Prefs.WEB_AUTO_STOP.default
        return try {
            repo.settingNow(Prefs.WEB_AUTO_STOP)
        } catch (e: SQLiteException) {
            Log.w("Web", e) { "auto-stop setting unreadable" }
            Prefs.WEB_AUTO_STOP.default
        } catch (e: IllegalStateException) {
            Log.w("Web", e) { "auto-stop setting unreadable: database closed" }
            Prefs.WEB_AUTO_STOP.default
        }
    }

    private fun shutdown(reason: String?, keepError: Boolean = false) {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(tick)
        if (!keepError) WebDashboard.stopServer(reason)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (WebDashboard.state.value.running) WebDashboard.stopServer(null)
        releaseLocks()
        if (current === this) current = null
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoneyTracker:webDashboard").apply { setReferenceCounted(false); acquire() }
        if (wifiLock == null) wifiLock = newWifiLock()
    }

    /** Keeps Wi-Fi awake for the page; the dashboard still runs, just slower, without it. */
    private fun newWifiLock(): WifiManager.WifiLock? {
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        return try {
            wm.createWifiLock(mode, "MoneyTracker:webDashboard").apply { setReferenceCounted(false); acquire() }
        } catch (e: SecurityException) {
            Log.w("Web", e) { "no Wi-Fi lock" }
            null
        }
    }

    private fun releaseLocks() {
        // Both locks are not reference-counted, so releasing a held one can't under-lock.
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(tr("Web dashboard address"), text))
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, tr("Address copied"), Toast.LENGTH_SHORT).show()
    }

    fun updateNotification() {
        if (stopping) return
        val s = WebDashboard.state.value
        val address = s.address ?: return
        val n = s.computers.size
        val who = when (n) { 0 -> tr("No computer connected"); 1 -> tr("1 computer connected"); else -> tr("{0} computers connected", n) }
        post(build("http://$address · $who"))
    }

    @SuppressLint("MissingPermission")
    private fun post(n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(this).notify(ID, n)
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java).setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, WebDashboardService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle(tr("Web dashboard is on"))
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, tr("Stop"), action(ACTION_STOP, 41))
            .addAction(0, tr("Copy address"), action(ACTION_COPY, 42))
            .build()
    }

    companion object {
        const val CHANNEL = "web_dashboard"
        const val ID = 7
        const val ACTION_STOP = "app.moneytracker.web.STOP"
        const val ACTION_COPY = "app.moneytracker.web.COPY"
        /** MainActivity opens the Web dashboard screen for this. */
        const val ACTION_OPEN = "app.moneytracker.WEB_DASHBOARD"
        const val DEFAULT_AUTO_STOP = 30
        private const val CHECK_MS = 30_000L

        @Volatile var current: WebDashboardService? = null
            private set

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Web dashboard"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr("Shown while the web dashboard is on, so you can stop it at any time.")
                    setShowBadge(false)
                })
        }

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, WebDashboardService::class.java))
        fun stop(ctx: Context) {
            if (current != null) ctx.startService(Intent(ctx, WebDashboardService::class.java).setAction(ACTION_STOP))
            else WebDashboard.stopServer(null)
        }
    }
}
