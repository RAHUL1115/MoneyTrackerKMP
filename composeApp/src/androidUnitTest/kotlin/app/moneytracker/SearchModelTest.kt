package app.moneytracker

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.TxnKind
import app.moneytracker.ui.ActKind
import app.moneytracker.ui.SearchDates
import app.moneytracker.ui.SearchModel
import app.moneytracker.ui.SearchQuery
import app.moneytracker.ui.SearchState
import app.moneytracker.ui.SearchType
import app.moneytracker.ui.entryFeed
import app.moneytracker.ui.searchRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The search screen's holder: what is searched, how results are narrowed, and the filter it hands to Activity. */
class SearchModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-03-02"), "acc_cash", categoryId = "cat_ex_0", amount = 2_500, note = "Pizza night"))
        repo.txnStore.create(TxnDraft(DraftType.INCOME, LocalDate.parse("2026-02-20"), "acc_bank", categoryId = "cat_in_0", amount = 90_000, note = "Pizza refund prize"))
        repo.txnStore.create(TxnDraft(DraftType.TRANSFER, LocalDate.parse("2026-03-05"), "acc_bank", toAccountId = "acc_cash", amount = 1_000, note = "Pizza cash"))
    }

    private fun model(scope: CoroutineScope, q: SearchQuery = SearchQuery()) = SearchModel(
        repo.txnStore, repo.accountStore, repo.tagStore, repo.settingsStore, repo.entryFeed(), repo.today, Dispatchers.Unconfined, scope, q,
    )

    /** Query changes are Compose state; tell its observers, as a frame would. */
    private suspend fun SearchModel.settled(test: (SearchState) -> Boolean): SearchState {
        Snapshot.sendApplyNotifications()
        return state.first(test)
    }

    @Test fun nothingIsSearchedUntilSomethingIsAsked() = runTest {
        val m = model(backgroundScope)
        val s = m.settled { true }
        assertFalse(s.active)
        assertTrue(s.results.isEmpty())
    }

    @Test fun textFindsEachTransferOnceNewestFirstWithTotals() = runTest {
        val m = model(backgroundScope)
        m.setText("pizza")
        val s = m.settled { it.results.size == 3 }
        assertEquals(listOf("Pizza cash", "Pizza night", "Pizza refund prize"), s.results.map { it.note })
        assertEquals(TxnKind.TRANSFER_OUT, s.results.first().kind)
        assertEquals(90_000, s.earned)
        assertEquals(2_500, s.spent)
    }

    @Test fun typeAndAccountChipsNarrowTheResults() = runTest {
        val m = model(backgroundScope)
        m.setText("pizza")
        m.toggleType(SearchType.EXPENSES)
        assertEquals(listOf("Pizza night"), m.settled { it.results.size == 1 }.results.map { it.note })
        m.toggleType(SearchType.EXPENSES)
        m.toggleAccount("acc_bank")
        assertEquals(listOf("Pizza cash", "Pizza refund prize"), m.settled { it.results.size == 2 }.results.map { it.note })
    }

    @Test fun amountsAreParsedAndDatesLimitTheSearch() = runTest {
        val m = model(backgroundScope)
        m.setMin("50")
        assertEquals(listOf("Pizza refund prize"), m.settled { it.active && it.results.size == 1 }.results.map { it.note })
        m.setMin("")
        m.setDates(SearchDates.MONTH)
        assertEquals(listOf("Pizza cash", "Pizza night"), m.settled { it.results.size == 2 }.results.map { it.note })
    }

    @Test fun searchRangesFollowTheMonthSettings() {
        val ps = PeriodSettings(startDay = 1)
        assertEquals(DateRange(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28")), searchRange(SearchDates.LAST, ps, today))
        assertEquals(DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31")), searchRange(SearchDates.THREE, ps, today))
        val custom = searchRange(SearchDates.CUSTOM, ps, today, from = LocalDate.parse("2026-03-10"), to = LocalDate.parse("2026-03-01"))
        assertEquals(DateRange(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-10")), custom)
    }

    @Test fun activityFilterTakesAccountsTagsKindAndMonth() = runTest {
        val tag = repo.tagStore.tagNamed("Goa trip")
        val m = model(backgroundScope)
        m.toggleType(SearchType.TRANSFERS)
        m.toggleAccount("acc_cash")
        m.pickTag(repo.tagStore.tagsNow().first { it.id == tag })
        m.setDates(SearchDates.LAST)
        val s = m.settled { it.active && it.range.start.monthNumber == 2 }
        val r = s.activityRequest(m.query)
        assertEquals(ActKind.MOVED, r.kind)
        assertEquals(setOf("acc_cash"), r.filter.accountIds)
        assertEquals(setOf(tag), r.filter.tagIds)
        assertEquals(202602, r.periodKey)
        m.clear()
        assertNull(m.settled { !it.active }.activityRequest(m.query).periodKey)
    }

    @Test fun pickingATagWhileTypingHashClearsTheText() = runTest {
        val tag = repo.tagStore.tagNamed("Goa trip")
        val m = model(backgroundScope)
        m.setText("#go")
        m.pickTag(repo.tagStore.tagsNow().first { it.id == tag })
        assertEquals("", m.query.text)
        assertEquals(listOf(tag), m.query.tagIds)
    }

    @Test fun theQuerySurvivesBeingSavedAndRestored() = runTest {
        val q = SearchQuery("pizza", setOf(SearchType.INCOME), setOf("acc_bank"), listOf("t1"), "1", "2", SearchDates.CUSTOM, today, null)
        val saver = SearchModel.saver { model(backgroundScope, it) }
        val saved = with(saver) { SaverScope { true }.save(model(backgroundScope, q)) }
        assertEquals(q, saver.restore(saved!!)!!.query)
    }
}
