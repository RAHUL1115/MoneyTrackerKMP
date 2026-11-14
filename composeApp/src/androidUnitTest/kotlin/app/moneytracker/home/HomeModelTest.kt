package app.moneytracker.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate

/** Home (new layout): today, yesterday and this period's spending, the budget left, waiting SMS and recent entries. */
class HomeModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private val yesterday = LocalDate.parse("2026-03-14")
    private lateinit var repo: MoneyRepository
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun model() = HomeModel(repo.reportStore, repo.txnStore, repo.captureStore, repo.settingsStore, repo.views, repo.today, scope)

    private fun spend(amount: Long, date: LocalDate = today, time: Int? = null) =
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, date, "acc_cash", categoryId = "cat_ex_0", amount = amount, timeOfDay = time))

    @Test fun spentTodayYesterdayAndThisPeriod() {
        spend(1_000); spend(2_000, date = yesterday); spend(4_000, date = LocalDate.parse("2026-03-02"))
        val s = model().state.value
        assertEquals(1_000, s.spentToday)
        assertEquals(2_000, s.spentYesterday)
        assertEquals(7_000, s.monthSpent)
        assertEquals(YearMonth(2026, 3), s.month)
    }

    @Test fun balanceCorrectionsAreNotEntries() {
        repo.txnStore.adjustBalance("acc_cash", -5_000)
        val s = model().state.value
        assertEquals(0, s.spentToday)
        assertEquals(1, s.days.size)                                         // just today, empty
        assertTrue(s.days.single().entries.isEmpty())
    }

    @Test fun yesterdayShowsOnlyWithEntriesAndTodaysNewestComeFirst() {
        spend(100, time = 8 * 60); spend(200, time = 20 * 60)
        assertEquals(listOf(true), model().state.value.days.map { it.isToday })
        spend(300, date = yesterday)
        val days = model().state.value.days
        assertEquals(listOf(today, yesterday), days.map { it.date })
        assertEquals(listOf(200L, 100), days.first().entries.map { it.amount })
        assertEquals(300, days.last().totals.expense)
    }

    @Test fun budgetLeftAndDaysToGo() {
        assertNull(model().state.value.budget)
        repo.reportStore.setBudget(TOTAL_BUDGET, YearMonth(2026, 3), 31_000)
        spend(10_000)
        val s = model().state.value
        assertEquals(21_000, s.budget?.remaining)
        assertEquals(17, s.daysLeft)                                         // 15th to 31st, today included
    }

    @Test fun bankSmsWaitingAddUp() {
        listOf(1_500L, null, 2_500L).forEachIndexed { i, amount ->
            repo.captureStore.addCapture(Capture("cap$i", 1_000L + i, "sms", "BANK", "Paid $i", amount, TxnKind.EXPENSE, "Shop", null, CaptureStatus.PENDING))
        }
        val s = model().state.value
        assertEquals(3, s.pending.size)
        assertEquals(4_000, s.pendingSum)
    }

    @Test fun followsNewEntries() {
        val m = model()
        spend(900)
        assertEquals(900, m.state.value.spentToday)
        assertEquals(1, m.state.value.days.single().entries.size)
    }
}
