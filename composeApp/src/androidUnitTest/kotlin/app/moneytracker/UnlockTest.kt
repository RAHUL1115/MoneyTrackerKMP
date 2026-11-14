package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.core.BiometricStatus
import app.moneytracker.core.PinSecurity
import app.moneytracker.core.unlock
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.db.MoneyDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

/** The lock screen's passcode rules: old hashes upgrade, the passcode from before a restore still works, wrong ones don't. */
class UnlockTest {
    private lateinit var repo: MoneyRepository

    /** Portable hashes are "s2:<pin>"; an older install-only hash is "old:<pin>". */
    private val pins = object : PinSecurity {
        override fun hashPin(pin: String) = "s2:$pin"
        override fun checkPin(pin: String, stored: String) = stored == "s2:$pin" || stored == "old:$pin"
        override fun portablePinHash(stored: String): String? = null
        override val biometricStatus = MutableStateFlow(BiometricStatus.UNSUPPORTED)
        override fun openBiometricEnrollment() {}
        override fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit) {}
    }

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-03-15") })
    }

    @Test fun rightPasscodeUnlocksAndKeepsAPortableHash() {
        repo.settingsStore.putSetting(Prefs.PASSCODE, "s2:1234")
        assertTrue(pins.unlock("1234", "s2:1234", repo.settingsStore))
        assertEquals("s2:1234", repo.settingsStore.settingNow(Prefs.PASSCODE))
    }

    @Test fun olderHashIsRewrittenInThePortableForm() {
        repo.settingsStore.putSetting(Prefs.PASSCODE, "old:1234")
        assertTrue(pins.unlock("1234", "old:1234", repo.settingsStore))
        assertEquals("s2:1234", repo.settingsStore.settingNow(Prefs.PASSCODE))
    }

    @Test fun passcodeFromBeforeARestoreStillUnlocksAndBecomesThePasscode() {
        repo.settingsStore.putSetting(Prefs.PASSCODE, "s2:9999")
        repo.settingsStore.putSetting(Prefs.PASSCODE_PREVIOUS, "old:1234")
        assertTrue(pins.unlock("1234", "s2:9999", repo.settingsStore))
        assertEquals("s2:1234", repo.settingsStore.settingNow(Prefs.PASSCODE))
        assertEquals("", repo.settingsStore.settingNow(Prefs.PASSCODE_PREVIOUS))
    }

    @Test fun wrongPasscodeStaysLocked() {
        repo.settingsStore.putSetting(Prefs.PASSCODE, "s2:1234")
        repo.settingsStore.putSetting(Prefs.PASSCODE_PREVIOUS, "old:5555")
        assertFalse(pins.unlock("0000", "s2:1234", repo.settingsStore))
        assertEquals("s2:1234", repo.settingsStore.settingNow(Prefs.PASSCODE))
        assertEquals("old:5555", repo.settingsStore.settingNow(Prefs.PASSCODE_PREVIOUS))
    }
}
