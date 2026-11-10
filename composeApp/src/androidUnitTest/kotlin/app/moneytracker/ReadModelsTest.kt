package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.ReadModels
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The finished views: each flow and its snapshot agree, and every screen's numbers come from one mapping. */
class ReadModelsTest {
    private val today = LocalDate.parse("2026-03-15")
    private val march = YearMonth(2026, 3)
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    private fun spend(amount: Long, account: String = "acc_cash", category: String? = "cat_ex_0", date: LocalDate = today) =
        repo.create(TxnDraft(DraftType.EXPENSE, date, account, categoryId = category, amount = amount))

    @Test fun budgetFlowAndSnapshotAgree() = runTest {
        repo.setBudget(TOTAL_BUDGET, march, 100_000)
        repo.setBudget("cat_ex_0", march, 30_000)
        spend(12_000); spend(5_000, category = "cat_ex_0_1"); spend(2_000, category = null)
        val flow = repo.views.budget(march).first()
        val now = repo.views.budgetNow(march)
        assertEquals(now.lines, flow.lines)
        assertEquals(now.spentByMain, flow.spentByMain)
    }

    @Test fun budgetCountsSubCategoriesUnderTheirMainAndKeepsUncategorized() {
        repo.setBudget("cat_ex_0", march, 30_000)
        repo.setBudget("cat_ex_0_1", march, 10_000)
        spend(12_000); spend(5_000, category = "cat_ex_0_1"); spend(2_000, category = null)
        val b = repo.views.budgetNow(march)
        assertEquals(17_000, b.spentByMain["cat_ex_0"])                       // main includes its sub-category
        assertEquals(2_000, b.spentByMain[ReadModels.UNCATEGORIZED])
        assertEquals(19_000, b.spent)
        assertEquals(5_000, b.categoryLines.single { it.categoryId == "cat_ex_0_1" }.spent)
        // No explicit total: the synthesised one covers main categories only (the sub-category is inside its parent).
        assertEquals(30_000, b.total!!.budget); assertEquals(17_000, b.total!!.spent)
    }

    @Test fun cardSpendingFollowsTheCardAtPaymentSetting() {
        spend(10_000, account = "acc_card")
        assertEquals(10_000, repo.views.budgetNow(march).spent)
        repo.putSetting(Prefs.CARD_AT_PAYMENT.key, "1")
        assertEquals(0, repo.views.budgetNow(march).spent)                    // counted when the bill is paid instead
    }

    @Test fun monthTotalsSplitCardsAndSavingsAndCompareWithLastMonth() = runTest {
        spend(4_000, date = LocalDate.parse("2026-02-10"))
        spend(3_000); spend(1_000, account = "acc_card")
        repo.saveAccount(repo.accountsNow().single { it.id == "acc_bank" }.copy(transferAsExpense = true))
        repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_cash", toAccountId = "acc_bank", amount = 500))
        val m = repo.views.monthNow(march)
        assertEquals(3_000, m.otherExpense); assertEquals(1_000, m.cardExpense)
        assertEquals(500, m.saved)
        assertEquals(4_000, m.previousExpense)
        assertEquals(m.totals.expense * 100 / 4_000, m.comparedToLastMonth!!.toLong())
        val flow = repo.views.month(march).first()
        assertEquals(m.totals, flow.totals); assertEquals(m.cardExpense, flow.cardExpense); assertEquals(m.saved, flow.saved)
    }

    @Test fun accountSummaryFlowAndSnapshotAgree() = runTest {
        spend(2_500); spend(1_000, account = "acc_card")
        val now = repo.views.accountsNow()
        assertEquals(now, repo.views.accounts.first())
        assertEquals(now, repo.views.latestAccounts)
        assertTrue(now.lines.single { it.account.id == "acc_card" }.card != null)
        assertEquals(now.lines.single { it.account.id == "acc_card" }.card, repo.views.cardStatusNow(repo.accountsNow().single { it.id == "acc_card" }))
    }

    @Test fun yearRollupStopsAtTodayAndSumsWeeksIntoMonths() {
        spend(1_000, date = LocalDate.parse("2026-01-05")); spend(2_000); spend(700, date = LocalDate.parse("2026-03-02"))
        val ps = repo.periodSettingsNow()
        val year = ReadModels.yearRollup(repo.reportRowsNow(ps.year(2026)), ps, 2026, today)
        assertEquals(listOf(3, 2, 1), year.months.map { it.month.month })      // up to March, newest first
        assertEquals(3_700, year.totals.expense)
        year.months.forEach { m -> assertEquals(m.totals.expense, m.weeks.sumOf { it.second.expense }) }
        // A later year with nothing in it shows just January; one with a future entry reaches that month.
        assertEquals(listOf(1), ReadModels.yearRollup(emptyList(), ps, 2027, today).months.map { it.month.month })
        val future = repo.reportRowsNow(ps.year(2027)) + repo.reportRowsNow(ps.month(YearMonth(2026, 3)))
            .map { it.copy(date = LocalDate.parse("2027-05-01")) }
        assertEquals(5, ReadModels.yearRollup(future, ps, 2027, today).months.first().month.month)
    }

    @Test fun snapshotReadsMatchTheirFlows() = runTest {
        spend(1_000)
        assertEquals(repo.categories.first(), repo.categoriesNow())
        assertEquals(repo.accounts.first(), repo.accountsNow())
        assertEquals(repo.balances.first(), repo.balancesNow())
        val range = repo.periodSettingsNow().month(march)
        assertEquals(repo.reportRows(range).first(), repo.reportRowsNow(range))
        assertEquals(repo.txnsBetween(range).first(), repo.txnsNow(range))
        assertEquals(repo.totalsNow(range), app.moneytracker.domain.totals(repo.reportRows(range).first()))
        assertNull(repo.views.budgetNow(march).total)                          // no budgets set
    }
}
