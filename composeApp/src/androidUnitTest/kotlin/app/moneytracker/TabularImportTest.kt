package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.moneymanager.TabularImport
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** Importing CSV or spreadsheet rows, including the app's own export. */
class TabularImportTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val march = Periods.month(YearMonth(2026, 3), 1)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @Test fun importOwnExportRoundTrips() = runTest {
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 12_345, note = "Lunch, with \"team\""))
        repo.txnStore.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_savings", amount = 50_000))
        val rows = TabularImport.exportRows(repo.txnStore.allTxns(), repo.accountStore.accounts.first(), repo.categoryStore.categories.first(), CurrencyFormat(), emptyList(), emptyMap())
        val csv = TabularImport.toCsv(rows)
        val result = repo.sheetImport.importRows(TabularImport.parseCsv(csv))
        assertEquals(2, result.imported)
        assertEquals(0, result.createdAccounts)
        assertEquals(0, result.createdCategories)
        val lunches = repo.txnStore.allTxns().filter { it.note == "Lunch, with \"team\"" }
        assertEquals(2, lunches.size)
        assertTrue(lunches.all { it.amount == 12_345L && it.categoryId == "cat_ex_0_1" })
    }

    @Test fun importSimpleSheetCreatesAccountsAndCategories() = runTest {
        val rows = listOf(
            listOf("Date", "Account", "Category", "Amount", "Note"),
            listOf("13/03/2026", "Wallet", "Snacks", "-45.50", "Chips"),
            listOf("2026-03-14", "Wallet", "Gifts", "100", "From aunt"),
            listOf("bad date", "Wallet", "", "1", ""),
        )
        val r = repo.sheetImport.importRows(rows)
        assertEquals(2, r.imported); assertEquals(1, r.skipped); assertEquals(1, r.createdAccounts)
        val t = totals(repo.reportStore.reportRows(march).first())
        assertEquals(4_550, t.expense); assertEquals(10_000, t.income)
    }
}
