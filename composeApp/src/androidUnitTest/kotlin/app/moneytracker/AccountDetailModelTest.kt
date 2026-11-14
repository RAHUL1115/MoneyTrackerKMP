package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.accounts.AccountActionsModel
import app.moneytracker.accounts.AccountDetailModel
import app.moneytracker.accounts.BalanceChange
import app.moneytracker.accounts.cycles
import app.moneytracker.accounts.parseBalance
import app.moneytracker.accounts.paySources
import app.moneytracker.accounts.periodSums
import app.moneytracker.data.DraftType
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.CardStatus
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.Periods
import app.moneytracker.domain.Txn
import app.moneytracker.domain.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** An account's detail screen: its history, period summaries, card cycles, balance correction and bill payment. */
class AccountDetailModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private val fmt = CurrencyFormat()
    private lateinit var repo: MoneyRepository
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun detail(id: String) =
        AccountDetailModel(id, repo.views, repo.accountStore, repo.categoryStore, repo.settingsStore, repo.txnStore, repo.tagStore, repo.io, scope)
    private fun actions(id: String) = AccountActionsModel(id, repo.txnStore, repo.entryEditor, repo.io, scope)
    private fun add(type: DraftType, date: LocalDate, account: String, amount: Long, to: String? = null) =
        repo.txnStore.create(TxnDraft(type, date, account, toAccountId = to, categoryId = if (type == DraftType.EXPENSE) "cat_ex_0" else "cat_in_0", amount = amount))
    private fun balance(id: String) = repo.accountStore.balancesNow()[id] ?: 0L
    private val march = Periods.month(YearMonth(2026, 3), 1)

    @Test fun historyHasThePeriodsEntriesAndARunningBalanceOverEverything() = runTest {
        add(DraftType.INCOME, LocalDate(2026, 2, 10), "acc_cash", 10_000)
        val spend = add(DraftType.EXPENSE, LocalDate(2026, 3, 10), "acc_cash", 2_500)
        val model = detail("acc_cash")

        val h = model.history(march).first()

        assertEquals(listOf(spend), h.entries.map { it.id })
        assertEquals(2, h.history.size)
        assertEquals(7_500L, h.running[spend])
    }

    @Test fun historyIsReadAgainWhenAnEntryIsAdded() = runTest {
        val model = detail("acc_cash")
        val flow = model.history(march)
        assertTrue(flow.first().entries.isEmpty())
        add(DraftType.EXPENSE, LocalDate(2026, 3, 2), "acc_cash", 100)
        assertEquals(1, flow.first().entries.size)
    }

    @Test fun theLineIsNullForAnotherOrDeletedAccount() = runTest {
        val model = detail("acc_cash")
        val summary = model.summary.first { it != null }
        assertEquals("acc_cash", model.lineIn(summary)?.account?.id)
        assertNull(detail("missing").lineIn(summary))
    }

    @Test fun monthlySumsCoverTheYearAndCarryTheBalance() = runTest {
        add(DraftType.INCOME, LocalDate(2026, 1, 10), "acc_cash", 10_000)
        add(DraftType.EXPENSE, LocalDate(2026, 3, 10), "acc_cash", 2_500)
        val history = repo.txnStore.accountTxns("acc_cash")

        val months = periodSums(history, YearMonth(2026, 3), yearly = false, startDay = 1, today = today)
        assertEquals(12, months.size)
        assertEquals(YearMonth(2026, 3), months[2].month)
        assertEquals(10_000L, months[0].deposit)
        assertEquals(2_500L, months[2].withdrawal)
        assertEquals(7_500L, months[2].balance)
        assertEquals(10_000L, months[1].balance)

        val years = periodSums(history, YearMonth(2026, 3), yearly = true, startDay = 1, today = today)
        assertEquals(listOf("2026"), years.map { it.label })
        assertNull(years.single().month)
    }

    @Test fun cardCyclesSettleTheOldestChargesFirst() = runTest {
        val open = Periods.cardCycle(today, 1)
        val last = Periods.cardCycle(open.start.minus(DatePeriod(days = 1)), 1)
        add(DraftType.EXPENSE, last.start, "acc_card", 1_000)
        add(DraftType.EXPENSE, open.start, "acc_card", 500)
        add(DraftType.TRANSFER, today, "acc_bank", 700, to = "acc_card")

        val c = cycles(repo.txnStore.accountTxns("acc_card"), 1, today, 3)

        assertEquals(listOf(open, last), c.take(2).map { it.range })
        assertEquals(listOf(500L, 1_000L, 0L), c.map { it.charged })
        assertEquals(listOf(500L, 300L, 0L), c.map { it.unpaid })
    }

    @Test fun billsArePaidFromVisibleNonCardAccountsThePaymentAccountFirst() {
        val card = Account("card", "cards", "Card", cardPaymentAccountId = "bank")
        val groups = listOf(AccountGroup("cards", "Cards", AccountType.CREDIT_CARD, 0), AccountGroup("banks", "Banks", AccountType.BANK, 1))
        val accounts = listOf(
            Account("cash", "banks", "Cash"), Account("bank", "banks", "Bank"), Account("old", "banks", "Old", hidden = true),
            Account("card2", "cards", "Other card"), card,
        )
        assertEquals(listOf("bank", "cash"), paySources(card, accounts, groups).map { it.id })
    }

    @Test fun aTypedBalanceReadsItsSign() {
        assertEquals(-1_250L, parseBalance(" -12.50 ", fmt))
        assertEquals(-500L, parseBalance("−5", fmt))
        assertEquals(500L, parseBalance("5", fmt))
        assertNull(parseBalance("abc", fmt))
        // The classic layout only takes the ASCII minus.
        assertNull(parseBalance("−5", fmt, minusSigns = listOf("-")))
    }

    @Test fun cardsAreAdjustedByWhatIsOwed() {
        val group = AccountGroup("cards", "Cards", AccountType.CREDIT_CARD, 0)
        val line = AccountLine(Account("card", "cards", "Card"), group, balance = -800, effectiveBalance = -800, card = CardStatus(0, 800, 0))
        val change = BalanceChange(line, typed = 500)
        assertEquals(800L, change.shown)
        assertEquals(-500L, change.target)
        assertEquals(300L, change.diff)
        assertTrue(change.increase)
        assertTrue(change.canContinue)
        assertEquals(false, BalanceChange(line, typed = 800).canContinue)
        assertEquals(false, BalanceChange(line, typed = null).canContinue)
    }

    @Test fun adjustingTheBalanceHandsBackWhatItAddedForUndo() = runTest {
        val model = actions("acc_cash")
        var added = emptyList<Txn>()
        model.adjustBalance(5_000, asIncomeExpense = false) { added = it }
        assertEquals(1, added.size)
        assertEquals(5_000L, balance("acc_cash"))

        model.undoAdjust(added)
        assertEquals(0L, balance("acc_cash"))
    }

    @Test fun payingABillCanBeUndone() = runTest {
        add(DraftType.EXPENSE, today, "acc_card", 1_000)
        val model = actions("acc_card")
        var paid: String? = null
        model.pay(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_card", amount = 1_000)) { paid = it }
        assertEquals(0L, balance("acc_card"))
        assertEquals(-1_000L, balance("acc_bank"))

        model.undoPay(requireNotNull(paid))
        assertEquals(-1_000L, balance("acc_card"))
        assertEquals(0L, balance("acc_bank"))
    }
}
