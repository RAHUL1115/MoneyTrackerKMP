package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.core.BiometricStatus
import app.moneytracker.core.PinSecurity
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.ui.LockModel
import app.moneytracker.ui.LockSetup
import app.moneytracker.ui.PasscodeModel
import app.moneytracker.ui.PinStep
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

/** The passcode screen's steps: set it twice, confirm before changing or removing it, and the biometric switch. */
class PasscodeModelTest {
    private lateinit var repo: MoneyRepository
    private lateinit var scope: CoroutineScope
    private lateinit var model: PasscodeModel

    /** Portable hashes are "s2:<pin>"; the biometric prompt succeeds while [scanWorks]. */
    private class FakePins(var scanWorks: Boolean = true) : PinSecurity {
        var prompts = 0
        override fun hashPin(pin: String) = "s2:$pin"
        override fun checkPin(pin: String, stored: String) = stored == "s2:$pin"
        override fun portablePinHash(stored: String): String? = null
        override val biometricStatus = MutableStateFlow(BiometricStatus.AVAILABLE)
        override fun openBiometricEnrollment() {}
        override fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
            prompts++
            if (scanWorks) onSuccess() else onError("Sensor locked")
        }
    }
    private val pins = FakePins()

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-03-15") })
        scope = CoroutineScope(Dispatchers.Unconfined + Job())
        model = PasscodeModel(repo.settingsStore, pins, Dispatchers.Unconfined, scope)
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun setup(biometricOn: Boolean = false) =
        LockSetup(repo.settingsStore.settingNow(Prefs.PASSCODE), biometricOn, BiometricStatus.AVAILABLE)

    @Test fun aNewPasscodeIsTypedTwiceThenOffersBiometrics() {
        model.startSet()
        assertFalse(model.enterNew("1234", setup()))
        assertEquals(PinStep.CONFIRM, model.state.value.step)
        assertTrue(model.enterNew("1234", setup()))
        assertEquals("s2:1234", repo.settingsStore.settingNow(Prefs.PASSCODE))
        assertEquals(PinStep.NONE, model.state.value.step)
        assertTrue(model.state.value.offerBiometric)
        model.dismissBiometricOffer()
        assertFalse(model.state.value.offerBiometric)
    }

    @Test fun aMismatchStartsOver() {
        model.startSet()
        model.enterNew("1234", setup())
        assertFalse(model.enterNew("9999", setup()))
        assertEquals(PinStep.NEW, model.state.value.step)
        assertEquals("Didn't match, try again", model.state.value.error)
        assertEquals("", repo.settingsStore.settingNow(Prefs.PASSCODE))
    }

    @Test fun turningOffNeedsTheCurrentPin() {
        repo.settingsStore.putSetting(Prefs.PASSCODE, "s2:1234")
        repo.settingsStore.putSetting(Prefs.BIOMETRIC, true)
        model.startTurnOff()
        assertFalse(model.verify("0000", setup()))
        assertEquals("Wrong passcode", model.state.value.error)
        assertTrue(model.verify("1234", setup()))
        assertEquals(PinStep.NONE, model.state.value.step)
        assertEquals("", repo.settingsStore.settingNow(Prefs.PASSCODE))
        assertFalse(repo.settingsStore.settingNow(Prefs.BIOMETRIC))
        assertEquals("Passcode turned off", model.state.value.message)
    }

    @Test fun aBiometricScanCanStandInForTheCurrentPin() {
        repo.settingsStore.putSetting(Prefs.PASSCODE, "s2:1234")
        model.startChange()
        model.verifyWithBiometric(setup(biometricOn = false))
        assertEquals(0, pins.prompts)
        model.verifyWithBiometric(setup(biometricOn = true))
        assertEquals(PinStep.NEW, model.state.value.step)
    }

    @Test fun biometricsNeedAPasscodeAndOneGoodScan() {
        model.setBiometric(true, setup())
        assertEquals("Set a passcode first; it's the fallback if biometrics fail.", model.state.value.message)
        repo.settingsStore.putSetting(Prefs.PASSCODE, "s2:1234")
        pins.scanWorks = false
        model.setBiometric(true, setup())
        assertEquals("Sensor locked", model.state.value.message)
        assertFalse(repo.settingsStore.settingNow(Prefs.BIOMETRIC))
        pins.scanWorks = true
        model.setBiometric(true, setup())
        assertTrue(repo.settingsStore.settingNow(Prefs.BIOMETRIC))
        model.setBiometric(false, setup(biometricOn = true))
        assertFalse(repo.settingsStore.settingNow(Prefs.BIOMETRIC))
    }

    @Test fun theLockScreenCountsWrongTries() {
        val lock = LockModel(pins, repo.settingsStore)
        assertFalse(lock.unlock("0000", "s2:1234"))
        assertFalse(lock.unlock("1111", "s2:1234"))
        assertEquals("Wrong passcode (2)", lock.error.value)
        assertTrue(lock.unlock("1234", "s2:1234"))
        lock.showError(null)
        assertNull(lock.error.value)
    }
}
