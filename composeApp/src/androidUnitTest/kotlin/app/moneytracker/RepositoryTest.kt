package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Periods
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryTest {
    private var today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    private suspend fun balance(id: String) = repo.balances.first()[id] ?: 0L

    @Test fun incomeExpenseAndTransferMoveBalances() = runTest {
        repo.create(TxnDraft(DraftType.INCOME, today, "acc_bank", categoryId = "cat_in_0", amount = 100_000))
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0", amount = 2_500))
        repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_cash", amount = 10_000, fee = 100))

        assertEquals(100_000 - 10_000 - 100L, balance("acc_bank"))
        assertEquals(-2_500 + 10_000L, balance("acc_cash"))

        val rows = repo.reportRows(Periods.month(YearMonth(2026, 3), 1)).first()
        val t = totals(rows)
        assertEquals(100_000, t.income)
        assertEquals(2_600, t.expense)               // expense + transfer fee; plain transfer is neutral
    }

    @Test fun deletingTransferRemovesBothLegsAndFee() = runTest {
        val id = repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_cash", amount = 5_000, fee = 50))
        repo.delete(repo.txn(id)!!)
        assertEquals(0, balance("acc_bank")); assertEquals(0, balance("acc_cash"))
    }

    @Test fun transferToSavingsCountsAsExpense() = runTest {
        repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_savings", amount = 7_000))
        assertEquals(7_000, totals(repo.reportRows(Periods.month(YearMonth(2026, 3), 1)).first()).expense)
    }

    @Test fun installmentsCreateMonthlyRowsAndDeleteAsPlan() = runTest {
        val id = repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_3", amount = 10_000, installments = 3))
        val all = repo.allTxns()
        assertEquals(listOf(3334L, 3333L, 3333L), all.map { it.amount })
        assertEquals(listOf("(1/3)", "(2/3)", "(3/3)"), all.map { it.installmentLabel })
        assertEquals(LocalDate.parse("2026-05-15"), all.last().date)
        assertEquals(-3334L, balance("acc_card"))         // only the installment already due counts toward today's balance
        repo.delete(repo.txn(id)!!, wholeInstallmentPlan = true)
        assertEquals(0, repo.allTxns().size)
    }

    @Test fun recurringGeneratesIdempotently() = runTest {
        repo.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-01-10"), "acc_bank", categoryId = "cat_ex_2",
            amount = 50_000, note = "Rent", repeat = RepeatType.MONTHLY))
        assertEquals(3, repo.allTxns().size)            // Jan, Feb, Mar 10th
        assertEquals(0, repo.generateRecurring())        // running again adds nothing
        today = LocalDate.parse("2026-04-10")
        assertEquals(1, repo.generateRecurring())
        assertEquals(4, repo.allTxns().count { it.kind == TxnKind.EXPENSE })
    }

    @Test fun adjustBalanceWritesDifference() = runTest {
        repo.create(TxnDraft(DraftType.INCOME, today, "acc_cash", amount = 1_000))
        repo.adjustBalance("acc_cash", 750)
        assertEquals(750, balance("acc_cash"))
        assertEquals(0, totals(repo.reportRows(Periods.month(YearMonth(2026, 3), 1)).first()).expense)   // adjustments aren't expense
    }

    @Test fun budgetFromMonthOnward() = runTest {
        repo.setBudget("cat_ex_0", YearMonth(2026, 1), 20_000)
        repo.setBudget("cat_ex_0", YearMonth(2026, 3), 30_000)
        val entries = repo.budgetEntries(YearMonth(2026, 2)).first()
        assertEquals(listOf(20_000L), entries.map { it.amount })
        repo.setBudget("cat_ex_0", YearMonth(2026, 2), 25_000)  // replaces the later March change
        assertEquals(listOf(20_000L, 25_000L), repo.budgetEntries(YearMonth(2026, 12)).first().map { it.amount })
    }
}
