package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.RepeatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V4FeaturesTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val all = DateRange(LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"))

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    // Entry order comes from creation time (ms); keep inserts apart so "newest" is unambiguous.
    private fun expense(note: String, cat: String = "cat_ex_0", acc: String = "acc_cash", time: Int? = null, date: LocalDate = today) =
        Thread.sleep(2).let { _ -> repo.create(TxnDraft(DraftType.EXPENSE, date, acc, categoryId = cat, amount = 1_000, note = note, timeOfDay = time)) }

    @Test fun noteSuggestionsAreNewestFirstAndCarryCategoryAndAccount() {
        expense("Lunch", cat = "cat_ex_0", acc = "acc_cash")
        expense("Lunch", cat = "cat_ex_1", acc = "acc_bank")
        expense("Laundry")
        val s = repo.noteSuggestions("L")
        assertEquals(listOf("Laundry", "Lunch"), s.map { it.note })
        assertEquals("cat_ex_1", s.last().categoryId)
        assertEquals("acc_bank", s.last().accountId)
        // A literal "%" must not act as a wildcard.
        assertTrue(repo.noteSuggestions("%").isEmpty())
        // Reset hides earlier notes; turning autocomplete off hides everything.
        repo.resetAutocomplete()
        Thread.sleep(2)
        assertTrue(repo.noteSuggestions("L").isEmpty())
        expense("Lemonade")
        assertEquals(listOf("Lemonade"), repo.noteSuggestions("L").map { it.note })
        repo.putSetting(Keys.AUTOCOMPLETE, "0")
        assertTrue(repo.noteSuggestions("L").isEmpty())
    }

    @Test fun timeOfDayOrdersEntriesWithinADay() = runTest {
        expense("morning", time = 9 * 60)
        expense("evening", time = 20 * 60)
        expense("no time")
        assertEquals(listOf("evening", "morning", "no time"), repo.txnsBetween(all).first().map { it.note })
        repo.putSetting(Keys.TIME_SORT, "ASC")
        assertEquals(listOf("no time", "morning", "evening"), repo.txnsBetween(all).first().map { it.note })
        assertEquals(20 * 60, repo.txnsBetween(all).first().last().timeOfDay)
    }

    @Test fun bulkEditsRespectEntryKinds() = runTest {
        val a = expense("a")
        val b = expense("b")
        val t = repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_cash", amount = 500))
        assertEquals(2, repo.setCategoryMany(listOf(a, b, t), "cat_ex_2"))
        assertEquals(2, repo.setAccountMany(listOf(a, b, t), "acc_bank"))
        val newDate = LocalDate.parse("2026-03-01")
        repo.setDateMany(listOf(t), newDate)
        // Both transfer legs move together.
        assertTrue(repo.txnsBetween(all).first().filter { it.transferGroupId != null }.all { it.date == newDate })
        repo.deleteMany(listOf(a, t))
        assertEquals(listOf("b"), repo.txnsBetween(all).first().map { it.note })
    }

    @Test fun hiddenAccountsAndOrdering() = runTest {
        repo.setAccountHidden("acc_cash", true)
        assertTrue(repo.accounts.first().first { it.id == "acc_cash" }.hidden)
        repo.reorderAccounts(listOf("acc_savings", "acc_card", "acc_bank", "acc_cash"))
        assertEquals("acc_savings", repo.accounts.first().first().id)
    }

    @Test fun resetEntriesOnlyKeepsAccounts() = runTest {
        expense("x")
        repo.reset(everything = false)
        assertTrue(repo.txnsBetween(all).first().isEmpty())
        assertEquals(4, repo.accounts.first().size)
        repo.saveAccount(repo.accounts.first().first().copy(id = "extra", name = "Extra"))
        repo.reset(everything = true)
        assertEquals(4, repo.accounts.first().size)   // back to the starting set
    }

    @Test fun everyFourMonths() {
        assertEquals(LocalDate.parse("2026-05-31"), Recurrence.nthOccurrence(LocalDate.parse("2026-01-31"), RepeatType.FOUR_MONTHLY, 1))
    }
}

class AdjustmentTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    @Test fun adjustmentIsNeutralByDefaultAndEditable() = runTest {
        repo.adjustBalance("acc_cash", 5_000)
        val adj = repo.allTxns().single()
        assertEquals(app.moneytracker.domain.TxnKind.ADJUST_UP, adj.kind)
        assertEquals(app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY, adj.categoryId)
        // Neutral: no income or expense in reports.
        assertEquals(0L, app.moneytracker.domain.totals(repo.reportRows(DateRange(today, today)).first()).income)
        assertEquals(5_000L, repo.balances.first()["acc_cash"])
        // Edit: smaller, decrease, other account.
        repo.updateAdjustment(adj, today, 1_200, "acc_bank", up = false, note = "fix")
        val edited = repo.allTxns().single()
        assertEquals(app.moneytracker.domain.TxnKind.ADJUST_DOWN, edited.kind)
        assertEquals(-1_200L, repo.balances.first()["acc_bank"])
        assertEquals(null, repo.balances.first()["acc_cash"])
    }

    @Test fun adjustmentCanBeIncomeOrExpense() = runTest {
        repo.adjustBalance("acc_cash", -300, asIncomeExpense = true)
        val t = repo.allTxns().single()
        assertEquals(app.moneytracker.domain.TxnKind.EXPENSE, t.kind)
        assertEquals("Difference", t.note)
        assertEquals(app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY, t.categoryId)
    }
}
