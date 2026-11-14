package app.moneytracker.platform

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.moneytracker.MainActivity
import app.moneytracker.Translations
import app.moneytracker.core.AppInfo
import app.moneytracker.core.Languages

/** Version, shipped licenses and app-level actions, read from the installed package. */
class AndroidAppInfo(private val activity: Activity) : AppInfo {
    private val ctx: Context get() = activity.applicationContext

    override val appVersion: String
        get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    override fun licenseText(id: String): String? =
        runCatching { ctx.assets.open("licenses/$id.txt").bufferedReader().use { it.readText() } }.getOrNull()

    override fun openUrl(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun restartApp() {
        // A fresh task with a new activity; the process (and the open database) stays.
        MainActivity.skipLockOnce = true
        activity.startActivity(Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java)))
        activity.finish()
    }
}

/** The phone's language and the translation tables shipped in the app's assets. */
class AndroidLanguages(private val ctx: Context) : Languages {
    override fun systemLanguage(): String = android.content.res.Resources.getSystem().configuration.locales[0].toLanguageTag()
    override fun translations(tag: String): Map<String, String> = Translations.load(ctx, tag)
}
