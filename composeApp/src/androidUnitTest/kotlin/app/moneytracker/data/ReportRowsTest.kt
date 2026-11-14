package app.moneytracker.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Periods
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** How entries become report rows, including credit card spending counted at payment. */
class ReportRowsTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val march = Periods.month(YearMonth(2026, 3), 1)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @Test fun cardAtPaymentMovesExpenseToBillPayment() = runTest {
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_0", amount = 3_000))
        repo.txnStore.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_card", amount = 3_000))
        assertEquals(3_000, totals(repo.reportStore.reportRows(march).first()).expense)
        repo.settingsStore.putSetting(Prefs.CARD_AT_PAYMENT.key, "1")
        assertEquals(3_000, totals(repo.reportStore.reportRows(march).first()).expense)   // counted once, at payment
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_0", amount = 1_000))
        assertEquals(3_000, totals(repo.reportStore.reportRows(march).first()).expense)   // unpaid purchase not yet expense
    }

    /** Filters and search rebuild report rows from entries; those rows must follow the same rules as the unfiltered ones. */
    @Test fun rowsBuiltFromEntriesMatchRepositoryRows() = runTest {
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_0", amount = 3_000, note = "Shoes"))
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_0", amount = 1_000))
        repo.txnStore.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_card", amount = 3_000))
        repo.txnStore.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_savings", amount = 500))
        repo.txnStore.create(TxnDraft(DraftType.INCOME, today, "acc_bank", categoryId = "cat_in_0", amount = 9_000))
        for (cardAtPayment in listOf("0", "1")) {
            repo.settingsStore.putSetting(Prefs.CARD_AT_PAYMENT.key, cardAtPayment)
            val rules = repo.reportStore.reportRules.first()
            val fromEntries = repo.txnStore.txnsNow(march).filter { it.kind != TxnKind.TRANSFER_IN }.map { rules.row(it) }
            val fromRepo = repo.reportStore.reportRows(march).first()
            assertEquals(fromRepo.sortedBy { it.txnId }, fromEntries.sortedBy { it.txnId }, "cardAtPayment=$cardAtPayment")
            assertEquals(totals(fromRepo), totals(fromEntries))
        }
    }
}
