package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyManagerBackup
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TabularImport
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.cardStatus
import app.moneytracker.ui.byMainCategory
import app.moneytracker.domain.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefundTest {
    private val today = LocalDate.parse("2026-04-20")
    private val april = DateRange(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"))
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    private fun draft(type: DraftType, amount: Long, account: String = "acc_bank", date: String = "2026-04-10") =
        TxnDraft(type, LocalDate.parse(date), account, categoryId = "cat_ex_0", amount = amount)

    private fun balance(account: String) = repo.accountTxns(account).sumOf { it.signedAccountValue }

    @Test fun refundIsStoredAsNegativeExpenseAndNetsTheCategory() = runTest {
        repo.create(draft(DraftType.EXPENSE, 500_00))
        val id = repo.create(draft(DraftType.REFUND, 500_00, date = "2026-04-12"))

        val stored = repo.txn(id)!!
        assertEquals(TxnKind.EXPENSE, stored.kind)
        assertEquals(-500_00L, stored.amount)
        assertTrue(stored.isRefund)

        assertEquals(0L, repo.totalsNow(april).expense)
        val food = byMainCategory(repo.reportRows(april).first(), repo.categoryById.first(), Flow.EXPENSE)
        assertEquals(0L, food["cat_ex_0"])
        assertEquals(0L, balance("acc_bank"))                     // −500 then +500
    }

    @Test fun partialRefundAndOtherAccount() = runTest {
        repo.create(draft(DraftType.EXPENSE, 500_00, account = "acc_card"))
        repo.create(draft(DraftType.REFUND, 200_00, account = "acc_bank"))   // refunded to a different account
        assertEquals(300_00L, repo.totalsNow(april).expense)
        assertEquals(-500_00L, balance("acc_card"))
        assertEquals(200_00L, balance("acc_bank"))
    }

    @Test fun refundOnCardLowersCycleSpend() {
        repo.create(draft(DraftType.EXPENSE, 900_00, account = "acc_card", date = "2026-04-05"))
        repo.create(draft(DraftType.REFUND, 400_00, account = "acc_card", date = "2026-04-08"))
        val s = cardStatus(repo.accountTxns("acc_card"), 1, today)
        assertEquals(500_00L, s.cycleSpend)
    }

    @Test fun editorRoundTripKeepsRefundPositive() {
        val id = repo.create(draft(DraftType.REFUND, 120_00))
        val d = repo.draftFrom(repo.txn(id)!!)
        assertEquals(DraftType.REFUND, d.type)
        assertEquals(120_00L, d.amount)
        val again = repo.update(repo.txn(id)!!, d.copy(amount = 150_00))
        assertEquals(-150_00L, repo.txn(again)!!.amount)
    }

    @Test fun recurringRefundGeneratesRefunds() = runTest {
        repo.create(draft(DraftType.REFUND, 50_00, date = "2026-02-15").copy(repeat = RepeatType.MONTHLY))
        val made = repo.accountTxns("acc_bank")
        assertEquals(3, made.size)                                 // Feb, Mar, Apr
        assertTrue(made.all { it.isRefund && it.amount == -50_00L })
        assertEquals(-50_00L, repo.rules.first().single().amount)
    }

    @Test fun smsRefundBecomesPendingRefund() {
        val sms = "Rs.499.00 refund credited to your a/c XX1234 for order at Amazon on 10-04-26"
        val p = SmsParser.parse("AD-HDFCBK", sms)!!
        assertTrue(p.isRefund)
        val c = repo.ingestMessage("SMS", "AD-HDFCBK", sms, 1_776_000_000_000)!!
        assertEquals(TxnKind.EXPENSE, c.kind)
        assertEquals(-499_00L, c.amount)
        val d = repo.draftFromCapture(c)
        assertEquals(DraftType.REFUND, d.type)
        assertEquals(499_00L, d.amount)
        // An ordinary credit is still income.
        assertTrue(!SmsParser.parse("AD-HDFCBK", "Rs.5000 credited to a/c XX1234 salary")!!.isRefund)
    }

    @Test fun spreadsheetExportAndImportKeepRefunds() = runTest {
        repo.create(draft(DraftType.REFUND, 75_00))
        val rows = TabularImport.exportRows(repo.allTxns(), repo.accounts.first(), repo.categories.first(),
            app.moneytracker.domain.CurrencyFormat("₹", 2), emptyList(), emptyMap())
        assertEquals("Refund", rows[1][1]); assertEquals("75", rows[1][6])

        val parsed = repo.parseRows(rows + listOf(listOf("2026-04-11", "Expense", "Bank Account", "", "🍔 Food", "", "-20", "", "", "", "")))
        assertEquals(listOf(DraftType.REFUND, DraftType.REFUND), parsed.rows.map { it.type })
        assertEquals(listOf(75_00L, 20_00L), parsed.rows.map { it.amount })
    }

    @Test fun moneyManagerNegativeExpenseImportsAsRefund() = runTest {
        val c = MoneyManagerImportTest().fixture()
        c.createStatement().execute("INSERT INTO INOUTCOME VALUES (10,'t10','a1','0','c2','','Pizza refund','1791000000009','2026-10-05','1','-250.50','','','-250.50','','','',0,0,'INR_INR',-250.5,'','')")
        val data = MoneyManagerBackup.read(JdbcRowSource(c), TimeZone.UTC)
        repo.replaceWithMoneyManager(data)
        val refund = repo.allTxns().single { it.id == "t10" }
        assertEquals(TxnKind.EXPENSE, refund.kind)
        assertEquals(-250_50L, refund.amount)
        assertTrue(refund.isRefund)
        assertEquals(250_50L, repo.allTxns().single { it.id == "t1" }.amount)   // ordinary expenses stay positive
    }
}
