package app.moneytracker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Android side of [Log]: every line goes to Logcat (tag "MT.<tag>") and to `files/logs/app.log`, which rolls over to
 * `app.log.1` at [MAX_BYTES], so at most ~512 KB is kept. The file is private to the app (allowBackup is off) and holds no personal data by the [Log] privacy rule. Read it from a debug build with
 * `adb shell run-as in.amrahul.moneytracker.debug cat files/logs/app.log`.
 */
object AndroidLog {
    private const val MAX_BYTES = 256 * 1024L
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "mt-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun logFile(ctx: Context) = File(File(ctx.filesDir, "logs"), "app.log")

    fun install(ctx: Context) {
        val debuggable = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        Log.minLevel = if (debuggable) Log.Level.DEBUG else Log.Level.INFO
        file = logFile(ctx).also { it.parentFile?.mkdirs() }
        Log.install(Log.Sink { level, tag, message, error -> write(level, tag, message, error) })
        installCrashHandler()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        Log.i("App") { "start v$version (${if (debuggable) "debug" else "release"}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}" }
    }

    private fun write(level: Log.Level, tag: String, message: String, error: Throwable?) {
        val t = "MT.$tag"
        when (level) {
            Log.Level.DEBUG -> android.util.Log.d(t, message, error)
            Log.Level.INFO -> android.util.Log.i(t, message, error)
            Log.Level.WARN -> android.util.Log.w(t, message, error)
            Log.Level.ERROR -> android.util.Log.e(t, message, error)
        }
        val f = file ?: return
        val now = Date()
        val thread = Thread.currentThread().name
        writer.execute {
            runCatching {
                if (f.length() > MAX_BYTES) f.renameTo(File(f.path + ".1"))
                f.appendText(buildString {
                    append(synchronized(stamp) { stamp.format(now) }).append(' ').append(level.letter).append(' ')
                        .append(tag).append(" [").append(thread).append("] ").append(message).append('\n')
                    if (error != null) append(android.util.Log.getStackTraceString(error).lineSequence().take(25).joinToString("\n")).append('\n')
                })
            }
        }
    }

    /** Writes the crash to the log file synchronously, then hands it to the default handler (which ends the app). */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                android.util.Log.e("MT.Crash", "uncaught on ${thread.name}", error)
                file?.appendText("${synchronized(stamp) { stamp.format(Date()) }} E Crash [${thread.name}] uncaught\n" +
                    android.util.Log.getStackTraceString(error) + "\n")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
