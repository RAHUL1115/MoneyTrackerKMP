package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.watchAllTables
import app.moneytracker.db.MoneyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Widgets follow the database on their own: every kind of write is seen, once per transaction. */
class DataChangesTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: MoneyRepository
    private var changes = 0
    private lateinit var stop: () -> Unit

    @BeforeTest fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
        stop = watchAllTables(driver, Prefs.bookkeeping) { changes++ }
    }

    @Test fun entriesSettingsAndAccountsAreSeen() {
        val id = repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0", amount = 1_000))
        assertEquals(1, changes)                                   // one transaction, one call
        repo.deleteMany(listOf(id))
        assertEquals(2, changes)
        repo.putSetting(Prefs.CARD_AT_PAYMENT.key, "1")
        assertEquals(3, changes)
        repo.deleteAccount("acc_bank")
        assertEquals(4, changes)
    }

    @Test fun bookkeepingAndUnchangedSettingsAreNotChanges() {
        repo.putSetting(Prefs.SMS_LAST_SCAN, 123L)                 // the inbox catch-up on every open
        repo.putSetting(Prefs.WIDGET_OPACITY, 100)                  // first save of a value: it is new to the table
        assertEquals(1, changes)
        repo.putSetting(Prefs.WIDGET_OPACITY, 100)                  // same again
        repo.putSetting(Prefs.SMS_LAST_SCAN, 456L)
        assertEquals(1, changes)
        repo.putSetting(Prefs.WIDGET_OPACITY, 60)
        assertEquals(2, changes)
    }

    @Test fun readsAreNotChangesAndStoppingEndsIt() {
        repo.accountsNow(); repo.views.monthNow(app.moneytracker.domain.YearMonth(2026, 3))
        assertEquals(0, changes)
        stop()
        repo.putSetting(Prefs.CARD_AT_PAYMENT.key, "1")
        assertEquals(0, changes)
    }
}
