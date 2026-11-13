package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTest {
    private val lines = mutableListOf<String>()

    @BeforeTest fun setUp() {
        Log.minLevel = Log.Level.DEBUG
        Log.install(Log.Sink { level, tag, message, _ -> lines += "${level.letter} $tag $message" })
    }

    @AfterTest fun tearDown() {
        Log.install()
        Log.minLevel = Log.Level.INFO
    }

    @Test fun levelBelowMinimumIsNotBuilt() {
        Log.minLevel = Log.Level.INFO
        var built = false
        Log.d("T") { built = true; "x" }
        Log.i("T") { "shown" }
        assertFalse(built)
        assertEquals(listOf("I T shown"), lines)
    }

    @Test fun attemptLogsAndSwallowsFailure() {
        val r = Log.attempt("T", "thing") { error("boom") }
        assertEquals(null, r)
        assertEquals(listOf("W T thing failed"), lines)
    }

    @Test fun brokenSinkOrMessageNeverThrows() {
        Log.install(Log.Sink { _, _, _, _ -> error("sink down") })
        Log.i("T") { error("message down") }
        assertTrue(Log.recent().last().contains("log message failed"))
    }

    @Test fun phoneNumbersAreHiddenBankIdsKept() {
        assertEquals("AD-HDFCBK-S", Log.sender("AD-HDFCBK-S"))
        assertEquals("a phone number", Log.sender("+919876543210"))
    }

    /** The privacy rule: an SMS outcome is logged without its text, amount, merchant or account names. */
    @Test fun smsLogsCarryNoPersonalData() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        val repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-04-20") })
        repo.freshStart.seedIfEmpty()
        val bank = repo.accountStore.accounts.first().first { it.id == "acc_bank" }
        repo.accountStore.saveAccount(bank.copy(name = "Secret Savings 5678"))
        val text = "Rs. 4,321.00 debited from your account xx5678 to CORNER BAKERY on 28-oct-26 (UPI ref 123456789012)."
        lines.clear()
        repo.messageIntake.ingestMessage("SMS", "AD-HDFCBK", text, 1_776_600_000_000)
        repo.messageIntake.ingestMessage("SMS", "+919876543210", "hi, lunch at 1?", 1_776_600_000_000)
        val all = lines.joinToString("\n")
        assertTrue(all.contains("waiting for review") || all.contains("saved automatically"), all)
        assertTrue(all.contains("not a transaction"), all)
        for (secret in listOf("4,321", "4321", "CORNER", "BAKERY", "Secret Savings", "5678", "9876543210", "lunch")) {
            assertFalse(all.contains(secret, ignoreCase = true), "log leaked '$secret':\n$all")
        }
    }
}
