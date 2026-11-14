package app.moneytracker.insights

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate

/** The Budget screens of both layouts: moving between months, setting and undoing budgets, what can get one. */
class BudgetModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private val march = YearMonth(2026, 3)
    private lateinit var repo: MoneyRepository
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun model(start: YearMonth = march) =
        BudgetModel(repo.reportStore, repo.categoryStore, repo.settingsStore, repo.views, repo.today, Dispatchers.Unconfined, scope, start)

    private fun spend(amount: Long, date: LocalDate = today, category: String = "cat_ex_0") =
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, date, "acc_cash", categoryId = category, amount = amount))

    @Test fun savingSetsTheBudgetFromTheShownMonthOnward() {
        spend(12_000)
        val m = model()
        val change = m.save("cat_ex_0", 30_000)
        assertTrue(change.added)
        val line = m.state.value.budget.categoryLines.single()
        assertEquals(30_000, line.budget)
        assertEquals(12_000, line.spent)
        m.step(1)
        assertEquals(30_000, m.state.value.budget.budgets["cat_ex_0"])        // carries into April
        m.step(-2)
        assertNull(m.state.value.budget.budgets["cat_ex_0"])                 // not before March
    }

    @Test fun changesSayWhetherABudgetWasAddedChangedOrRemoved() {
        val m = model()
        assertTrue(m.save(TOTAL_BUDGET, 50_000).added)
        val changed = m.save(TOTAL_BUDGET, 60_000)
        assertFalse(changed.added); assertFalse(changed.removed); assertEquals(50_000, changed.previous)
        assertTrue(m.save(TOTAL_BUDGET, 0).removed)
        assertNull(m.state.value.budget.total)
    }

    @Test fun undoPutsThePreviousAmountBack() {
        val m = model()
        m.save("cat_ex_0", 30_000)
        val change = m.save("cat_ex_0", 45_000)
        m.undo(change)
        assertEquals(30_000, m.state.value.budget.budgets["cat_ex_0"])
        m.undo(m.save("cat_ex_1", 5_000))
        assertNull(m.state.value.budget.budgets["cat_ex_1"])
    }

    @Test fun showsTheMonthBeforeAndTodaysMonth() {
        spend(7_000, date = LocalDate.parse("2026-02-10")); spend(3_000)
        val m = model()
        assertEquals(3_000, m.state.value.budget.spent)
        assertEquals(7_000, m.state.value.before.spent)
        m.pickMonth(YearMonth(2025, 11))
        assertEquals(YearMonth(2025, 11), m.state.value.month)
        assertEquals(march, m.state.value.thisMonth)
        assertEquals(LocalDate.parse("2025-11-01"), m.state.value.range.start)
    }

    @Test fun subCategoriesCanHaveBudgetsOnlyWhenInUse() {
        repo.settingsStore.putSetting(Prefs.USE_SUBCATEGORIES, false)
        assertTrue(model().state.value.options.none { it.parentId != null })
        repo.settingsStore.putSetting(Prefs.USE_SUBCATEGORIES, true)
        val m = model()
        assertTrue(m.state.value.options.any { it.id == "cat_ex_0_1" })
        m.save("cat_ex_0_1", 1_000)
        assertTrue(m.state.value.unbudgeted.none { it.id == "cat_ex_0_1" })
    }

    @Test fun followsNewEntries() {
        val m = model()
        m.save("cat_ex_0", 10_000)
        spend(2_500)
        assertEquals(2_500, m.state.value.budget.categoryLines.single().spent)
    }

    @Test fun typedAmountsEmptyMeansRemove() {
        val currency = CurrencyFormat()
        assertEquals(0, budgetAmount("  ", currency))
        assertEquals(1_250, budgetAmount("12.50", currency))
        assertNull(budgetAmount("abc", currency))
    }
}
