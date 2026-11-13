package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.TAG_USAGE_EXCEPTION
import app.moneytracker.domain.cardStatus
import app.moneytracker.domain.cardUsage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** Credit card maths: usage per cycle and the prepayment and usage-exception tags. */
class CreditCardTest {
    private val today = LocalDate.parse("2026-04-20")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    private fun spend(date: String, amount: Long) =
        repo.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse(date), "acc_card", categoryId = "cat_ex_0", amount = amount))

    @Test fun cardUsageMeasuresLastCycleOrThreeCycleAverage() {
        // Cycles start on the 1st: Apr is current, Mar/Feb/Jan are the previous three.
        spend("2026-04-02", 100); spend("2026-03-10", 300); spend("2026-02-10", 600); spend("2026-01-10", 900)
        val txns = repo.accountTxns("acc_card")
        assertEquals(300, cardUsage(txns, 1, today, averageOf3 = false).measured)
        assertEquals(600, cardUsage(txns, 1, today, averageOf3 = true).measured)
        assertEquals(100, cardUsage(txns, 1, today, averageOf3 = false).thisCycle)
    }

    @Test fun systemTagsExistAndShapeCardMaths() = runTest {
        assertTrue(repo.tags.first().any { it.id == TAG_PREPAYMENT } && repo.tags.first().any { it.id == TAG_USAGE_EXCEPTION })
        val pre = spend("2026-04-05", 700)                                   // current cycle, but prepaid → due now
        spend("2026-04-06", 50)
        val skip = spend("2026-03-05", 200); spend("2026-03-06", 100)        // last cycle; one excluded from usage
        repo.setTags(pre, listOf(TAG_PREPAYMENT)); repo.setTags(skip, listOf(TAG_USAGE_EXCEPTION))
        val txns = repo.accountTxns("acc_card")
        val status = cardStatus(txns, 1, today, repo.txnIdsWithTag(TAG_PREPAYMENT))
        assertEquals(700, status.overdue); assertEquals(300, status.statementDue); assertEquals(50, status.currentCycle)
        assertEquals(100, cardUsage(txns, 1, today, false, repo.txnIdsWithTag(TAG_USAGE_EXCEPTION)).measured)
    }
}
