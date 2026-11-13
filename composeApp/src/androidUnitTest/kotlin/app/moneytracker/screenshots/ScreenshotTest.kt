package app.moneytracker.screenshots

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.ThemeMode
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.seedDemoData
import app.moneytracker.ui.App
import app.moneytracker.ui.Navigator
import app.moneytracker.ui.Screen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.internal.bytecode.InstrumentationConfiguration
import java.util.Locale
import java.util.TimeZone

/**
 * Draws the real app ([App]) over the demo data on a fixed day, opens a screen and saves it as a PNG under
 * `src/androidUnitTest/screenshots/`. `recordRoborazziDebug` rewrites the PNGs, `verifyRoborazziDebug` fails when
 * a screen looks different, and a plain `testDebugUnitTest` just draws them.
 */
@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h780dp-xhdpi")
abstract class ScreenshotTest(private val newLayout: Boolean) {
    @get:Rule val compose = createComposeRule()

    protected val repo = demoRepository()

    @Before fun pinLocaleAndClock() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(zoneWhereItIsNow(GREETING_HOUR))
    }

    /** Opens the app on its home screen with [screens] pushed on top, and waits until it has settled. */
    protected fun show(vararg screens: Screen, dark: Boolean = false) {
        repo.settingsStore.putSetting(Prefs.THEME, if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        val navigator = Navigator().apply { screens.forEach(::push) }
        compose.setContent {
            App(repo, quietBackups, navigator, quietPlatform, locked = false, onUnlocked = {}, launchAction = null,
                onLaunchHandled = {}, newLayout = newLayout)
        }
        compose.waitForIdle()
    }

    /** Taps the first element showing [text] (a tab, a group, a view switch). */
    protected fun tap(text: String) {
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    /** Scrolls down to the first element showing [text] and taps it (a row further down a long screen). */
    protected fun scrollToAndTap(text: String) {
        compose.onAllNodesWithText(text).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Types [text] into the first text field on screen. */
    protected fun type(text: String) {
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(text)
        compose.waitForIdle()
    }

    /** Saves the whole window as `<layout>/<name>.png`. */
    protected fun shoot(name: String) {
        compose.onRoot().captureRoboImage("${if (newLayout) "new" else "classic"}/$name.png")
    }

    /** The id of the demo entry whose note is [note]. */
    protected fun entryId(note: String) = repo.txnStore.allTxns().first { it.note == note }.id

    companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-10-21")

        /** Home greets by the phone's hour ("Good morning"); the shots always see this hour. */
        private const val GREETING_HOUR = 9
    }
}

/** A fresh in-memory database with the demo entries, a finished onboarding and no passcode. */
private fun demoRepository(): MoneyRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    MoneyDatabase.Schema.create(driver)
    val noonOfToday = ScreenshotTest.TODAY.toEpochDays() * 86_400_000L + 12 * 3_600_000L
    val repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, now = { noonOfToday }, today = { ScreenshotTest.TODAY })
    seedDemoData(repo, ScreenshotTest.TODAY)
    repo.settingsStore.putSetting(Prefs.ONBOARDED, true)
    return repo
}

/**
 * A fixed-offset zone in which the wall clock reads [hour] right now. The app asks the system clock for the hour in
 * a few places (Home's greeting); this keeps those the same whenever the tests run, without touching app code.
 */
private fun zoneWhereItIsNow(hour: Int): TimeZone {
    val utcHour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).hour
    val offset = Math.floorMod(hour - utcHour + 12, 24) - 12   // -12..+11, all real offsets
    return TimeZone.getTimeZone(java.time.ZoneOffset.ofHours(offset))
}

/**
 * Robolectric, except the SQLite JDBC driver comes from the normal class loader. Otherwise the driver registers
 * itself from Robolectric's copy and the plain database tests that run next in the same JVM find no driver.
 */
class ScreenshotRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method)).doNotAcquirePackage("org.sqlite").build()
}
