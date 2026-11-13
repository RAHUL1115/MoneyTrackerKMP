package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The entry list: ordering within a day, note suggestions, bulk edits and resetting entries. */
class EntriesTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val all = DateRange(LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"))

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    // Entry order comes from creation time (ms); keep inserts apart so "newest" is unambiguous.
    private fun expense(note: String, cat: String = "cat_ex_0", acc: String = "acc_cash", time: Int? = null, date: LocalDate = today) =
        Thread.sleep(2).let { _ -> repo.txnStore.create(TxnDraft(DraftType.EXPENSE, date, acc, categoryId = cat, amount = 1_000, note = note, timeOfDay = time)) }

    @Test fun noteSuggestionsAreNewestFirstAndCarryCategoryAndAccount() {
        expense("Lunch", cat = "cat_ex_0", acc = "acc_cash")
        expense("Lunch", cat = "cat_ex_1", acc = "acc_bank")
        expense("Laundry")
        val s = repo.learningStore.noteSuggestions("L")
        assertEquals(listOf("Laundry", "Lunch"), s.map { it.note })
        assertEquals("cat_ex_1", s.last().categoryId)
        assertEquals("acc_bank", s.last().accountId)
        // A literal "%" must not act as a wildcard.
        assertTrue(repo.learningStore.noteSuggestions("%").isEmpty())
        // Reset hides earlier notes; turning autocomplete off hides everything.
        repo.learningStore.resetAutocomplete()
        Thread.sleep(2)
        assertTrue(repo.learningStore.noteSuggestions("L").isEmpty())
        expense("Lemonade")
        assertEquals(listOf("Lemonade"), repo.learningStore.noteSuggestions("L").map { it.note })
        repo.settingsStore.putSetting(Prefs.AUTOCOMPLETE.key, "0")
        assertTrue(repo.learningStore.noteSuggestions("L").isEmpty())
    }

    @Test fun timeOfDayOrdersEntriesWithinADay() = runTest {
        expense("morning", time = 9 * 60)
        expense("evening", time = 20 * 60)
        expense("no time")
        assertEquals(listOf("evening", "morning", "no time"), repo.txnStore.txnsBetween(all).first().map { it.note })
        repo.settingsStore.putSetting(Prefs.TIME_SORT.key, "ASC")
        assertEquals(listOf("no time", "morning", "evening"), repo.txnStore.txnsBetween(all).first().map { it.note })
        assertEquals(20 * 60, repo.txnStore.txnsBetween(all).first().last().timeOfDay)
    }

    @Test fun bulkEditsRespectEntryKinds() = runTest {
        val a = expense("a")
        val b = expense("b")
        val t = repo.txnStore.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_cash", amount = 500))
        assertEquals(2, repo.txnStore.setCategoryMany(listOf(a, b, t), "cat_ex_2"))
        assertEquals(2, repo.txnStore.setAccountMany(listOf(a, b, t), "acc_bank"))
        val newDate = LocalDate.parse("2026-03-01")
        repo.txnStore.setDateMany(listOf(t), newDate)
        // Both transfer legs move together.
        assertTrue(repo.txnStore.txnsBetween(all).first().filter { it.transferGroupId != null }.all { it.date == newDate })
        repo.txnStore.deleteMany(listOf(a, t))
        assertEquals(listOf("b"), repo.txnStore.txnsBetween(all).first().map { it.note })
    }

    @Test fun resetEntriesOnlyKeepsAccounts() = runTest {
        expense("x")
        repo.freshStart.reset(everything = false)
        assertTrue(repo.txnStore.txnsBetween(all).first().isEmpty())
        assertEquals(4, repo.accountStore.accounts.first().size)
        repo.accountStore.saveAccount(repo.accountStore.accounts.first().first().copy(id = "extra", name = "Extra"))
        repo.freshStart.reset(everything = true)
        assertEquals(4, repo.accountStore.accounts.first().size)   // back to the starting set
    }
}
