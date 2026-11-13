package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.totals
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class AdjustmentTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @Test fun adjustmentIsNeutralByDefaultAndEditable() = runTest {
        repo.txnStore.adjustBalance("acc_cash", 5_000)
        val adj = repo.txnStore.allTxns().single()
        assertEquals(app.moneytracker.domain.TxnKind.ADJUST_UP, adj.kind)
        assertEquals(app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY, adj.categoryId)
        // Neutral: no income or expense in reports.
        assertEquals(0L, app.moneytracker.domain.totals(repo.reportStore.reportRows(DateRange(today, today)).first()).income)
        assertEquals(5_000L, repo.accountStore.balances.first()["acc_cash"])
        // Edit: smaller, decrease, other account.
        repo.txnStore.updateAdjustment(adj, today, 1_200, "acc_bank", up = false, note = "fix")
        val edited = repo.txnStore.allTxns().single()
        assertEquals(app.moneytracker.domain.TxnKind.ADJUST_DOWN, edited.kind)
        assertEquals(-1_200L, repo.accountStore.balances.first()["acc_bank"])
        assertEquals(null, repo.accountStore.balances.first()["acc_cash"])
    }

    @Test fun adjustmentCanBeIncomeOrExpense() = runTest {
        repo.txnStore.adjustBalance("acc_cash", -300, asIncomeExpense = true)
        val t = repo.txnStore.allTxns().single()
        assertEquals(app.moneytracker.domain.TxnKind.EXPENSE, t.kind)
        assertEquals("Difference", t.note)
        assertEquals(app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY, t.categoryId)
    }
}
