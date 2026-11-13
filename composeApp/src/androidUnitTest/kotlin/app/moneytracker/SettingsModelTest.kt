package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.ClockTime
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.UiLayout
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.BudgetEntry
import app.moneytracker.domain.Tag
import app.moneytracker.domain.YearMonth
import app.moneytracker.ui.BackgroundJobs
import app.moneytracker.ui.BackgroundState
import app.moneytracker.ui.SettingRow
import app.moneytracker.ui.SettingsModel
import app.moneytracker.ui.SettingsOverviewModel
import app.moneytracker.ui.budgetedCount
import app.moneytracker.ui.searchRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings screens' holders: every write lands in the store, and the overview counts what is there. */
class SettingsModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private lateinit var scope: CoroutineScope
    private lateinit var model: SettingsModel

    /** Records reminder calls; the phone allows whatever [allowed] says. */
    private class FakeJobs(var allowed: Boolean = true) : BackgroundJobs {
        val reminders = mutableListOf<Triple<Boolean, Int, Int>>()
        var quickAdd: Boolean? = null
        override fun setReminder(enabled: Boolean, hour: Int, minute: Int, onResult: (Boolean) -> Unit) {
            reminders += Triple(enabled, hour, minute); onResult(enabled && allowed)
        }
        override fun setQuickAddNotification(enabled: Boolean, onResult: (Boolean) -> Unit) { quickAdd = enabled; onResult(enabled && allowed) }
        override fun backgroundState() = BackgroundState("test", restricted = false)
        override fun openBackgroundSettings(autostart: Boolean) = false
    }
    private val jobs = FakeJobs()

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
        scope = CoroutineScope(Dispatchers.Unconfined + Job())
        model = SettingsModel(repo.settingsStore, repo.learningStore, jobs, Dispatchers.Unconfined, scope)
    }

    @AfterTest fun tearDown() = scope.cancel()

    private val settings get() = repo.settingsStore

    @Test fun saveWritesTheSetting() {
        model.save(Prefs.CARRY_OVER, true)
        assertTrue(settings.settingNow(Prefs.CARRY_OVER))
    }

    @Test fun resetSuggestionsForgetsLearnedNotesThenReports() {
        var done = false
        model.resetSuggestions { done = true }
        assertTrue(done)
        assertTrue(settings.settingNow(Prefs.AUTOCOMPLETE_SINCE) > 0)
    }

    @Test fun textEditChecksAndSavesTrimmedText() {
        val edit = model.textEdit(Prefs.MONTH_START_DAY)
        assertEquals("1", edit.initial)
        assertFalse(edit.valid("32"))
        assertTrue(edit.valid(" 25 "))
        model.saveText(Prefs.MONTH_START_DAY, edit, " 25 ")
        assertEquals(25, settings.settingNow(Prefs.MONTH_START_DAY))
        assertTrue(jobs.reminders.isEmpty())
    }

    @Test fun aNewReminderTimeReschedulesTheReminder() {
        val edit = model.textEdit(Prefs.REMINDER_TIME)
        model.saveText(Prefs.REMINDER_TIME, edit, "07:30")
        assertEquals(ClockTime(7, 30), settings.settingNow(Prefs.REMINDER_TIME))
        assertEquals(listOf(Triple(true, 7, 30)), jobs.reminders)
    }

    @Test fun reminderSwitchFollowsWhatThePhoneAllowed() {
        model.setReminder(true, ClockTime(21, 0))
        assertTrue(settings.settingNow(Prefs.REMINDER))
        jobs.allowed = false
        model.setQuickAdd(true)
        assertFalse(settings.settingNow(Prefs.QUICK_ADD_NOTIFICATION))
    }

    @Test fun switchingLayoutSavesThenRestarts() {
        var restartedWith: UiLayout? = null
        model.switchLayout(toNew = true) { restartedWith = settings.settingNow(Prefs.UI_LAYOUT) }
        assertEquals(UiLayout.NEW, restartedWith)
    }

    @Test fun onboardingSavesChoicesAndRestartsOnlyForTheNewLayout() {
        var restarts = 0
        model.finishOnboarding(symbol = " ", decimals = 0, newLayout = false) { restarts++ }
        assertEquals("₹", settings.settingNow(Prefs.CURRENCY_SYMBOL))
        assertEquals(0, settings.settingNow(Prefs.CURRENCY_DECIMALS))
        assertEquals(UiLayout.CLASSIC, settings.settingNow(Prefs.UI_LAYOUT))
        assertTrue(settings.settingNow(Prefs.ONBOARDED))
        assertEquals(0, restarts)
        model.finishOnboarding(symbol = "$", decimals = 2, newLayout = true) { restarts++ }
        assertEquals(1, restarts)
    }

    private fun overviewModel() = SettingsOverviewModel(
        repo.currencyStore, repo.categoryStore, repo.tagStore, repo.repeatStore, repo.bookmarkStore, repo.captureStore,
        repo.reportStore, settings, { today },
    )

    @Test fun overviewCountsWhatIsThere() = runTest {
        val overview = overviewModel()
        val now = overview.overviewNow()
        assertEquals(repo.categoryStore.categoriesNow().count { it.kind.name == "EXPENSE" && it.parentId == null }, now.expenseCategories)
        assertEquals(0, now.budgeted)

        repo.tagStore.saveTag(Tag("t1", "Goa trip", 0))
        repo.reportStore.setBudget("cat_ex_0", YearMonth(2026, 3), 5_000)
        val live = overview.overview.first { it.budgeted == 1 }
        assertEquals(now.tags + 1, live.tags)
        assertEquals(live, overview.overviewNow())
    }

    @Test fun aBudgetCountsOnlyWhileItsLatestAmountIsAboveZero() {
        val entries = listOf(
            BudgetEntry("food", 202601, 5_000), BudgetEntry("food", 202603, 0),
            BudgetEntry("fuel", 202602, 2_000),
        )
        assertEquals(1, budgetedCount(entries))
    }

    @Test fun searchMatchesEveryWordAnywhereInARow() {
        val rows = listOf(
            "Look" to SettingRow(title = "Theme", value = "Dark", words = "night mode", onClick = {}),
            "Money" to SettingRow(title = "Currency symbol", value = "₹", onClick = {}),
        )
        assertEquals(listOf("Theme"), searchRows(rows, " look  NIGHT ").map { it.second.title })
        assertTrue(searchRows(rows, "   ").isEmpty())
    }
}
