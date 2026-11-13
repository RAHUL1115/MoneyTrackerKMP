package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** Search by text, amount and tags, and tags on entries. */
class SearchAndTagsTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val all = DateRange(LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"))

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    @Test fun searchMatchesNoteCategoryAccountAndAmountRange() = runTest {
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 25_000, note = "Pizza night"))
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_bank", categoryId = "cat_ex_2_0", amount = 900_000, note = "March rent"))
        assertEquals(listOf("Pizza night"), repo.search("pizza", all, null, null).map { it.note })
        assertEquals(listOf("Pizza night"), repo.search("eating", all, null, null).map { it.note })      // sub-category name
        assertEquals(listOf("March rent"), repo.search("bank", all, null, null).map { it.note })         // account name
        assertEquals(listOf("March rent"), repo.search("", all, 100_000, null).map { it.note })          // amount filter
    }

    @Test fun searchByTagNameSelectedTagsAndTransferDestination() = runTest {
        val trip = repo.tagNamed("Goa trip")
        val work = repo.tagNamed("Work")
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 25_000, note = "Beach shack", tagIds = listOf(trip)))
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 9_000, note = "Taxi", tagIds = listOf(trip, work)))
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 4_000, note = "goa snacks"))
        repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_cash", toAccountId = "acc_bank", amount = 1_000, note = "Deposit"))
        // "#goa" looks at tag names only: the untagged "goa snacks" note is not a match.
        assertEquals(setOf("Beach shack", "Taxi"), repo.search("#goa", all, null, null).map { it.note }.toSet())
        assertEquals(setOf("Beach shack", "Taxi", "goa snacks"), repo.search("goa", all, null, null).map { it.note }.toSet())
        // Selected tags: any one of them is enough, as in Money Manager.
        assertEquals(listOf("Taxi"), repo.search("", all, null, null, setOf(work)).map { it.note })
        assertEquals(setOf("Beach shack", "Taxi"), repo.search("", all, null, null, setOf(trip, work)).map { it.note }.toSet())
        // A transfer is found by the account it went to.
        assertEquals(listOf("Deposit"), repo.search("bank", all, null, null).map { it.note })
    }

    @Test fun tagsAndTemplatesAndNotesPersist() = runTest {
        val tag = repo.tagNamed("Trip")
        val id = repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", amount = 100, tagIds = listOf(tag)))
        assertEquals(listOf(tag), repo.tagsOf(id))
        assertEquals(listOf("Trip"), repo.txnTagNames.first()[id])
        assertEquals(1, repo.search("trip", all, null, null).size)
        assertEquals(tag, repo.tagNamed("trip"))   // case-insensitive reuse
    }
}
