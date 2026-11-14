package app.moneytracker.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.moneytracker.app.LocalBackups
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Navigator
import app.moneytracker.app.Screen
import app.moneytracker.backup.fresh.snapshotWhen
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.core.rememberSetting
import app.moneytracker.core.tr
import app.moneytracker.data.Prefs
import app.moneytracker.domain.CategoryKind
import app.moneytracker.entry.classic.save
import app.moneytracker.settings.classic.MorePlace
import app.moneytracker.settings.classic.SettingHub
import app.moneytracker.settings.classic.SettingRow
import app.moneytracker.webdashboard.classic.webDashboardStatus

/** The screens that gather related places. */
@Composable
fun rememberSettingHubs(model: SettingsModel, overview: SettingsOverview): List<SettingHub> {
    val nav = LocalNavigator.current
    val appVersion = LocalPlatform.current.appInfo.appVersion
    val subs by rememberSetting(Prefs.USE_SUBCATEGORIES)
    val capture by rememberSetting(Prefs.CAPTURE_ENABLED)
    val passcode = rememberSetting(Prefs.PASSCODE).value.isNotBlank()
    val lastBackup = rememberLastBackup()
    return listOf(
        labelsHub(nav, overview, subs) { model.save(Prefs.USE_SUBCATEGORIES, !subs) },
        reuseHub(nav, overview),
        smsHub(nav, overview, capture),
        safetyHub(nav, lastBackup, passcode),
        helpHub(nav, appVersion),
    )
}

private fun labelsHub(nav: Navigator, o: SettingsOverview, subs: Boolean, toggleSubs: () -> Unit) =
    SettingHub("labels", tr("Categories & tags"), tr("Everything you label entries with."), listOf(
        SettingRow(tr("Expense categories"), o.expenseCategories.toString(), tr("Add, rename, reorder"), "category sub emoji") {
            nav.push(Screen.Categories(CategoryKind.EXPENSE))
        },
        SettingRow(tr("Income categories"), o.incomeCategories.toString(), tr("Salary, refunds and more"), "category salary") {
            nav.push(Screen.Categories(CategoryKind.INCOME))
        },
        SettingRow(tr("Sub-categories"), null, tr("Pick a sub-category (Food › Lunch) when adding an entry"), "category", subs, toggleSubs),
        SettingRow(tr("Tags"), o.tags.toString(), tr("Extra labels like “Goa trip”; search them with #"), "label hashtag") { nav.push(Screen.Tags) },
    ))

private fun reuseHub(nav: Navigator, o: SettingsOverview) =
    SettingHub("reuse", tr("Repeat & bookmarks"), tr("Entries you save once and use again."), listOf(
        SettingRow(tr("Repeat entries"), o.repeats.toString(), tr("Rent, SIPs, subscriptions: added on their date"), "recurring installment rent subscription") {
            nav.push(Screen.Recurring)
        },
        SettingRow(tr("Bookmarks"), o.bookmarks.toString(), tr("Saved entries you add in one tap"), "quick add favourite star template") { nav.push(Screen.Templates) },
    ))

private fun smsHub(nav: Navigator, o: SettingsOverview, capture: Boolean) =
    SettingHub("sms", tr("Bank SMS"), tr("Payments read from your bank's text messages, on this phone only."), listOf(
        SettingRow(tr("Detected transactions"), if (o.toReview == 0) null else o.toReview.toString(),
            if (o.toReview == 0) tr("Nothing to review") else tr("{0} to review", o.toReview), "message inbox review bank detected paste") {
            nav.push(Screen.Inbox)
        },
        SettingRow(tr("Bank SMS settings"), if (capture) tr("On") else tr("Off"),
            tr("Detection, blocked senders and words, import from the inbox"), "message text detect auto block filter import") {
            nav.push(Screen.MessageSettings)
        },
    ))

private fun safetyHub(nav: Navigator, lastBackup: String?, passcode: Boolean) =
    SettingHub("safety", tr("Backup & security"), tr("Keep your data safe and private."), listOf(
        SettingRow(tr("Backup & restore"), null, lastBackup?.let { tr("Last backup: {0}", it) } ?: tr("Not backed up yet"),
            "backup restore export import csv excel mmbak snapshot reset") { nav.push(Screen.Backup) },
        SettingRow(tr("Passcode"), if (passcode) tr("On") else tr("Off"), tr("Lock with a PIN, fingerprint or face"),
            "pin lock password fingerprint face biometric security") { nav.push(Screen.Security) },
    ))

private fun helpHub(nav: Navigator, appVersion: String) =
    SettingHub("help", tr("Help & about"), tr("How things work, and who made this."), listOf(
        SettingRow(tr("Help"), null, null, "guide how faq") { nav.push(Screen.Help) },
        SettingRow(tr("About MoneyTracker"), appVersion.ifEmpty { null }, null, "version privacy source github feedback") { nav.push(Screen.About) },
        SettingRow(tr("Open-source licences"), "GPL-3.0", null, "license licence legal libraries") { nav.push(Screen.License("GPL-3.0")) },
    ))

/**
 * Places with their status. The last place is Settings on classic and Help & about on the new layout.
 */
@Composable
fun rememberMorePlaces(newLayout: Boolean, overview: SettingsOverview): List<MorePlace> {
    val nav = LocalNavigator.current
    val capture by rememberSetting(Prefs.CAPTURE_ENABLED)
    val passcode = rememberSetting(Prefs.PASSCODE).value.isNotBlank()
    val lastBackup = rememberLastBackup()
    val o = overview
    return listOf(
        MorePlace("labels", tr("Categories & tags"),
            tr("{0} expense · {1} income · {2} tags", o.expenseCategories, o.incomeCategories, o.tags)) { nav.push(Screen.MoreHub("labels")) },
        MorePlace("budget", tr("Budget"), if (o.budgeted == 0) tr("Not set") else tr("Set for {0} categories", o.budgeted)) { nav.push(Screen.Budget) },
        MorePlace("reuse", tr("Repeat & bookmarks"), tr("{0} repeats · {1} bookmarks", o.repeats, o.bookmarks)) { nav.push(Screen.MoreHub("reuse")) },
        MorePlace("sms", tr("Bank SMS"),
            if (!capture) tr("Off") else if (o.toReview == 0) tr("On") else tr("On · {0} to review", o.toReview)) {
            nav.push(Screen.MoreHub("sms"))
        },
        MorePlace("safety", tr("Backup & security"),
            listOf(lastBackup?.let { tr("Backed up {0}", it) } ?: tr("No backup yet"), if (passcode) tr("passcode on") else tr("passcode off")).joinToString(" · ")) {
            nav.push(Screen.MoreHub("safety"))
        },
        MorePlace("web", tr("Web dashboard"), webDashboardStatus()) { nav.push(Screen.WebDashboard) },
        if (newLayout) MorePlace("help", tr("Help & about"), tr("Guide, version, licences")) { nav.push(Screen.MoreHub("help")) }
        else MorePlace("settings", tr("Settings"), tr("Money, calendar, look, entering")) { nav.push(Screen.Configuration) },
    )
}

/** When the newest snapshot was taken ("today", "3 days ago"), or null when there is none. */
@Composable
private fun rememberLastBackup(): String? {
    val today = LocalRepo.current.today
    val snapshots by LocalBackups.current.saved.localBackups.collectAsState()
    return snapshots.firstOrNull()?.let { snapshotWhen(it.name, today()) }
}
