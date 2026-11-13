package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Periods
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.YearMonth
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** Repeating entries and how periods start. */
class RepeatsAndPeriodsTest {
    private val today = LocalDate.parse("2026-04-20")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    @Test fun ruleDescriptionReachesGeneratedEntries() = runTest {
        repo.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-02-20"), "acc_bank", categoryId = "cat_ex_0", amount = 500,
            note = "Rent", memo = "Flat 4B", repeat = RepeatType.MONTHLY))
        val made = repo.accountTxns("acc_bank").filter { it.note == "Rent" }
        assertEquals(3, made.size)                                        // Feb, Mar, Apr
        assertTrue(made.all { it.memo == "Flat 4B" })
        assertEquals("Flat 4B", repo.rules.first().single().memo)
    }

    @Test fun everyFourMonths() {
        assertEquals(LocalDate.parse("2026-05-31"), Recurrence.nthOccurrence(LocalDate.parse("2026-01-31"), RepeatType.FOUR_MONTHLY, 1))
    }

    @Test fun weekendShiftMovesPeriodStart() {
        // 2026-03-25 is a Wednesday; 2026-04-25 is a Saturday.
        val before = Periods.month(YearMonth(2026, 5), 25, Periods.WeekendShift.BEFORE)
        assertEquals(LocalDate.parse("2026-04-24"), before.start)
        val after = Periods.month(YearMonth(2026, 5), 25, Periods.WeekendShift.AFTER)
        assertEquals(LocalDate.parse("2026-04-27"), after.start)
    }
}
