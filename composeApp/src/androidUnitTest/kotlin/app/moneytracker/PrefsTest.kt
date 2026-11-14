package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.ClockTime
import app.moneytracker.data.ColorSet
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.data.SmsOriginal
import app.moneytracker.data.StartScreen
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.choiceSpecs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Typed settings: one default per setting, forgiving reads, and the stored text other code (backups, imports) relies on. */
class PrefsTest {
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-03-15") })
    }

    private val all: List<Setting<*>> = Prefs::class.java.declaredFields
        .filter { Setting::class.java.isAssignableFrom(it.type) }
        .map { it.isAccessible = true; it.get(Prefs) as Setting<*> }

    @Test fun everySettingHasItsOwnKey() {
        assertTrue(all.size >= 53, "found ${all.size}")
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test fun nothingStoredGivesTheDefaults() {
        all.forEach { s -> assertEquals(s.default, repo.settingsStore.settingNow(s), s.key) }
        // The ones that are on unless turned off.
        listOf(Prefs.AUTOCOMPLETE, Prefs.HABIT_ACCOUNT, Prefs.NOTE_BUTTON, Prefs.SMS_ALERT, Prefs.RATES_AUTO).forEach { assertTrue(repo.settingsStore.settingNow(it), it.key) }
        assertFalse(repo.settingsStore.settingNow(Prefs.USE_SUBCATEGORIES))
        assertEquals(CurrencyFormat("₹", 2, false), repo.settingsStore.currencyFormatNow())
        assertEquals(ClockTime(21, 0), repo.settingsStore.settingNow(Prefs.REMINDER_TIME))
        assertEquals(30, repo.settingsStore.settingNow(Prefs.SMS_IMPORT_DAYS))
    }

    @Test fun storedTextIsWhatBackupsAndImportsExpect() {
        repo.settingsStore.putSetting(Prefs.CARRY_OVER, true); assertEquals("1", repo.settingsStore.settingNow(Prefs.CARRY_OVER.key))
        repo.settingsStore.putSetting(Prefs.AUTOCOMPLETE, false); assertEquals("0", repo.settingsStore.settingNow(Prefs.AUTOCOMPLETE.key))
        repo.settingsStore.putSetting(Prefs.COLOR_SET, ColorSet.SWAPPED); assertEquals("B", repo.settingsStore.settingNow(Prefs.COLOR_SET.key))
        repo.settingsStore.putSetting(Prefs.SMS_SHOW_ORIGINAL, SmsOriginal.WHILE_EDITING); assertEquals("2", repo.settingsStore.settingNow(Prefs.SMS_SHOW_ORIGINAL.key))
        repo.settingsStore.putSetting(Prefs.WEEK_START, DayOfWeek.MONDAY); assertEquals("MONDAY", repo.settingsStore.settingNow(Prefs.WEEK_START.key))
        repo.settingsStore.putSetting(Prefs.REMINDER_TIME, ClockTime(7, 5)); assertEquals("07:05", repo.settingsStore.settingNow(Prefs.REMINDER_TIME.key))
        repo.settingsStore.putSetting(Prefs.MONTH_START_DAY, 25); assertEquals("25", repo.settingsStore.settingNow(Prefs.MONTH_START_DAY.key))
        assertEquals(25, repo.settingsStore.periodSettingsNow().startDay)
    }

    @Test fun oddStoredValuesFallBackOrStayInRange() {
        repo.settingsStore.putSetting(Prefs.AUTOCOMPLETE.key, "yes"); assertTrue(repo.settingsStore.settingNow(Prefs.AUTOCOMPLETE))       // default on
        repo.settingsStore.putSetting(Prefs.CARRY_OVER.key, "yes"); assertFalse(repo.settingsStore.settingNow(Prefs.CARRY_OVER))         // default off
        repo.settingsStore.putSetting(Prefs.START_SCREEN.key, "GONE"); assertEquals(StartScreen.DAILY, repo.settingsStore.settingNow(Prefs.START_SCREEN))
        repo.settingsStore.putSetting(Prefs.MONTH_START_DAY.key, "40"); assertEquals(31, repo.settingsStore.settingNow(Prefs.MONTH_START_DAY))
        repo.settingsStore.putSetting(Prefs.WIDGET_OPACITY.key, "-5"); assertEquals(0, repo.settingsStore.settingNow(Prefs.WIDGET_OPACITY))
        repo.settingsStore.putSetting(Prefs.REMINDER_TIME.key, "25:00"); assertEquals(ClockTime(21, 0), repo.settingsStore.settingNow(Prefs.REMINDER_TIME))
        repo.settingsStore.putSetting(Prefs.CURRENCY_DECIMALS.key, "x"); assertEquals(2, repo.settingsStore.settingNow(Prefs.CURRENCY_DECIMALS))
    }

    @Test fun retiredSettingsAreRemovedAndOthersKept() {
        Prefs.retired.forEach { repo.settingsStore.putSetting(it, "1") }
        repo.settingsStore.putSetting(Prefs.CARRY_OVER, true)
        repo.settingsStore.dropRetiredSettings()
        Prefs.retired.forEach { assertNull(repo.settingsStore.settingNow(it), it) }
        assertTrue(repo.settingsStore.settingNow(Prefs.CARRY_OVER))
        assertTrue(Prefs.retired.none { k -> all.any { it.key == k } })   // never retire a key still in use
    }

    @Test fun clockTimeReadsOnlyRealTimes() {
        assertEquals(ClockTime(9, 5), ClockTime.parse(" 9:5 "))
        assertEquals("09:05", ClockTime(9, 5).toString())
        listOf("", "9", "24:00", "12:60", "a:b", "1:2:3").forEach { assertNull(ClockTime.parse(it), it) }
    }

    @Test fun liveSettingEmitsTypedValues() = runTest {
        assertEquals(StartScreen.DAILY, repo.settingsStore.setting(Prefs.START_SCREEN).first())
        repo.settingsStore.putSetting(Prefs.START_SCREEN, StartScreen.CALENDAR)
        assertEquals(StartScreen.CALENDAR, repo.settingsStore.setting(Prefs.START_SCREEN).first())
        repo.settingsStore.putSetting(Prefs.SYMBOL_AFTER, true)
        assertEquals(CurrencyFormat("₹", 2, true), repo.settingsStore.currency.first())
    }

    @Test fun everyChoiceOptionCanBeStoredAndReadBack() {
        listOf(true, false).forEach { newLayout ->
            choiceSpecs(newLayout).forEach { spec -> roundTrip(spec.setting, spec.options.map { it.first }) }
        }
    }

    private fun <T> roundTrip(s: Setting<T>, values: List<Any?>) {
        @Suppress("UNCHECKED_CAST")
        values.forEach { v -> repo.settingsStore.putSetting(s, v as T); assertEquals(v, repo.settingsStore.settingNow(s), "${s.key} = $v") }
    }
}
