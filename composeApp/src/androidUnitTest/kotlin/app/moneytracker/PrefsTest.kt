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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        all.forEach { s -> assertEquals(s.default, repo.settingNow(s), s.key) }
        // The ones that are on unless turned off.
        listOf(Prefs.AUTOCOMPLETE, Prefs.HABIT_ACCOUNT, Prefs.NOTE_BUTTON, Prefs.SMS_ALERT, Prefs.RATES_AUTO).forEach { assertTrue(repo.settingNow(it), it.key) }
        assertFalse(repo.settingNow(Prefs.USE_SUBCATEGORIES))
        assertEquals(CurrencyFormat("₹", 2, false), repo.currencyFormatNow())
        assertEquals(ClockTime(21, 0), repo.settingNow(Prefs.REMINDER_TIME))
        assertEquals(30, repo.settingNow(Prefs.SMS_IMPORT_DAYS))
    }

    @Test fun storedTextIsWhatBackupsAndImportsExpect() {
        repo.putSetting(Prefs.CARRY_OVER, true); assertEquals("1", repo.settingNow(Prefs.CARRY_OVER.key))
        repo.putSetting(Prefs.AUTOCOMPLETE, false); assertEquals("0", repo.settingNow(Prefs.AUTOCOMPLETE.key))
        repo.putSetting(Prefs.COLOR_SET, ColorSet.SWAPPED); assertEquals("B", repo.settingNow(Prefs.COLOR_SET.key))
        repo.putSetting(Prefs.SMS_SHOW_ORIGINAL, SmsOriginal.WHILE_EDITING); assertEquals("2", repo.settingNow(Prefs.SMS_SHOW_ORIGINAL.key))
        repo.putSetting(Prefs.WEEK_START, DayOfWeek.MONDAY); assertEquals("MONDAY", repo.settingNow(Prefs.WEEK_START.key))
        repo.putSetting(Prefs.REMINDER_TIME, ClockTime(7, 5)); assertEquals("07:05", repo.settingNow(Prefs.REMINDER_TIME.key))
        repo.putSetting(Prefs.MONTH_START_DAY, 25); assertEquals("25", repo.settingNow(Prefs.MONTH_START_DAY.key))
        assertEquals(25, repo.periodSettingsNow().startDay)
    }

    @Test fun oddStoredValuesFallBackOrStayInRange() {
        repo.putSetting(Prefs.AUTOCOMPLETE.key, "yes"); assertTrue(repo.settingNow(Prefs.AUTOCOMPLETE))       // default on
        repo.putSetting(Prefs.CARRY_OVER.key, "yes"); assertFalse(repo.settingNow(Prefs.CARRY_OVER))         // default off
        repo.putSetting(Prefs.START_SCREEN.key, "GONE"); assertEquals(StartScreen.DAILY, repo.settingNow(Prefs.START_SCREEN))
        repo.putSetting(Prefs.MONTH_START_DAY.key, "40"); assertEquals(31, repo.settingNow(Prefs.MONTH_START_DAY))
        repo.putSetting(Prefs.WIDGET_OPACITY.key, "-5"); assertEquals(0, repo.settingNow(Prefs.WIDGET_OPACITY))
        repo.putSetting(Prefs.REMINDER_TIME.key, "25:00"); assertEquals(ClockTime(21, 0), repo.settingNow(Prefs.REMINDER_TIME))
        repo.putSetting(Prefs.CURRENCY_DECIMALS.key, "x"); assertEquals(2, repo.settingNow(Prefs.CURRENCY_DECIMALS))
    }

    @Test fun retiredSettingsAreRemovedAndOthersKept() {
        Prefs.retired.forEach { repo.putSetting(it, "1") }
        repo.putSetting(Prefs.CARRY_OVER, true)
        repo.dropRetiredSettings()
        Prefs.retired.forEach { assertNull(repo.settingNow(it), it) }
        assertTrue(repo.settingNow(Prefs.CARRY_OVER))
        assertTrue(Prefs.retired.none { k -> all.any { it.key == k } })   // never retire a key still in use
    }

    @Test fun clockTimeReadsOnlyRealTimes() {
        assertEquals(ClockTime(9, 5), ClockTime.parse(" 9:5 "))
        assertEquals("09:05", ClockTime(9, 5).toString())
        listOf("", "9", "24:00", "12:60", "a:b", "1:2:3").forEach { assertNull(ClockTime.parse(it), it) }
    }

    @Test fun liveSettingEmitsTypedValues() = runTest {
        assertEquals(StartScreen.DAILY, repo.setting(Prefs.START_SCREEN).first())
        repo.putSetting(Prefs.START_SCREEN, StartScreen.CALENDAR)
        assertEquals(StartScreen.CALENDAR, repo.setting(Prefs.START_SCREEN).first())
        repo.putSetting(Prefs.SYMBOL_AFTER, true)
        assertEquals(CurrencyFormat("₹", 2, true), repo.currency.first())
    }

    @Test fun everyChoiceOptionCanBeStoredAndReadBack() {
        listOf(true, false).forEach { newLayout ->
            choiceSpecs(newLayout).forEach { spec -> roundTrip(spec.setting, spec.options.map { it.first }) }
        }
    }

    private fun <T> roundTrip(s: Setting<T>, values: List<Any?>) {
        @Suppress("UNCHECKED_CAST")
        values.forEach { v -> repo.putSetting(s, v as T); assertEquals(v, repo.settingNow(s), "${s.key} = $v") }
    }
}
