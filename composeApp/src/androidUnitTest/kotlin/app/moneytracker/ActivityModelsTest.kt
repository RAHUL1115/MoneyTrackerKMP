package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.activity.ActKind
import app.moneytracker.activity.ActivityFilterRequest
import app.moneytracker.activity.ActivityModel
import app.moneytracker.activity.BulkEditModel
import app.moneytracker.activity.BulkResult
import app.moneytracker.activity.CalendarModel
import app.moneytracker.activity.EntryListModel
import app.moneytracker.activity.MonthsModel
import app.moneytracker.activity.calendarGrid
import app.moneytracker.activity.classic.TxnFilter
import app.moneytracker.activity.entryFeed
import app.moneytracker.activity.filterChoices
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** The Activity tab's holders (shared by both layouts): month and filter, the list, calendar, months and bulk edits. */
class ActivityModelsTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    private fun activity(scope: CoroutineScope) = ActivityModel(
        repo.settingsStore, repo.categoryStore, repo.accountStore, repo.tagStore, repo.captureStore, repo.today, scope,
    )

    private fun list(a: ActivityModel, scope: CoroutineScope) =
        EntryListModel(a.state, repo.entryFeed(), repo.repeatStore, repo.currencyStore, repo.settingsStore, scope)

    private fun add(type: DraftType, day: String, account: String, amount: Long, category: String? = null, to: String? = null, note: String = "") =
        repo.txnStore.create(TxnDraft(type, LocalDate.parse(day), account, toAccountId = to, categoryId = category, amount = amount, note = note))

    @Test fun opensOnThisMonthAndStepsByMonthOrYear() = runTest {
        val a = activity(backgroundScope)
        assertEquals(YearMonth(2026, 3), a.state.value.period)
        a.stepMonths(-1)
        assertEquals(YearMonth(2026, 2), a.state.first { it.period == YearMonth(2026, 2) }.period)
        a.stepMonths(12)
        assertEquals(DateRange(LocalDate.parse("2027-02-01"), LocalDate.parse("2027-02-28")), a.state.first { it.period.year == 2027 }.range)
    }

    @Test fun searchRequestSetsFilterKindAndMonthAndNamesTheFilter() = runTest {
        val a = activity(backgroundScope)
        val tag = repo.tagStore.tagNamed("Goa trip")
        val cash = repo.accountStore.accountNow("acc_cash")!!.name
        a.apply(ActivityFilterRequest(TxnFilter(accountIds = setOf("acc_cash"), tagIds = setOf(tag)), ActKind.OUT, 202601))
        val s = a.state.first { it.filter.isActive && it.filterNames().size == 2 }
        assertEquals(YearMonth(2026, 1), s.period)
        assertEquals(ActKind.OUT, s.kind)
        assertEquals(listOf(cash, "#Goa trip"), s.filterNames())
        a.clearFilter()
        assertFalse(a.state.first { !it.filter.isActive }.filter.isActive)
    }

    @Test fun classicFilterBarLeavesOutDeletedCategories() = runTest {
        val a = activity(backgroundScope)
        val food = repo.categoryStore.categoriesNow().first { it.id == "cat_ex_0" }
        a.setFilter(TxnFilter(categoryIds = setOf("cat_ex_0")))
        assertEquals(listOf(food.name), a.state.first { it.filterNames().isNotEmpty() }.filterNames())
        repo.categoryStore.deleteCategory("cat_ex_0")
        val s = a.state.first { it.filterNames(includeDeletedCategories = false).isEmpty() }
        assertEquals(listOf(food.name), s.filterNames())
    }

    @Test fun listShowsEachTransferOnceAndTheQuickFilterNarrowsIt() = runTest {
        add(DraftType.EXPENSE, "2026-03-03", "acc_cash", 2_500, "cat_ex_0")
        add(DraftType.INCOME, "2026-03-05", "acc_bank", 100_000, "cat_in_0")
        add(DraftType.TRANSFER, "2026-03-05", "acc_bank", 10_000, to = "acc_cash")
        repo.txnStore.adjustBalance("acc_bank", 0)    // an adjustment only shows on its account's page
        val a = activity(backgroundScope)
        val l = list(a, backgroundScope)
        val all = l.state.first { it.visible.size == 3 }
        assertEquals(setOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.TRANSFER_OUT), all.visible.map { it.kind }.toSet())
        assertTrue(all.entries.none { it.kind.isAdjustment })
        assertEquals(100_000, all.totals.income)
        assertEquals(2_500, all.totals.expense)
        assertEquals(100_000, all.dayTotals[LocalDate.parse("2026-03-05")]?.income)
        assertEquals(listOf(LocalDate.parse("2026-03-05"), LocalDate.parse("2026-03-03")), all.days.keys.toList())

        a.setKind(ActKind.OUT)
        assertEquals(listOf(TxnKind.EXPENSE), l.state.first { it.query.kind == ActKind.OUT }.visible.map { it.kind })
        a.setKind(ActKind.MOVED)
        assertEquals(listOf(TxnKind.TRANSFER_OUT), l.state.first { it.query.kind == ActKind.MOVED }.visible.map { it.kind })
    }

    @Test fun filterByAccountAlsoRebuildsTheTotals() = runTest {
        add(DraftType.EXPENSE, "2026-03-03", "acc_cash", 2_500, "cat_ex_0")
        add(DraftType.EXPENSE, "2026-03-04", "acc_bank", 7_000, "cat_ex_0")
        val a = activity(backgroundScope)
        val l = list(a, backgroundScope)
        assertEquals(9_500, l.state.first { it.visible.size == 2 }.totals.expense)
        a.setFilter(TxnFilter(accountIds = setOf("acc_cash")))
        val narrowed = l.state.first { it.query.filter.isActive && it.visible.size == 1 }
        assertEquals(2_500, narrowed.totals.expense)
    }

    @Test fun carryOverAndRepeatsStillToCome() = runTest {
        add(DraftType.INCOME, "2026-02-10", "acc_bank", 40_000, "cat_in_0")
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-03-10"), "acc_bank", categoryId = "cat_ex_2",
            amount = 50_000, note = "Rent", repeat = RepeatType.MONTHLY))
        repo.settingsStore.putSetting(Prefs.CARRY_OVER, true)
        val a = activity(backgroundScope)
        val l = list(a, backgroundScope)
        assertEquals(40_000L, l.state.first { it.carryOver != null }.carryOver)
        a.stepMonths(1)
        val april = l.state.first { it.query.range.start.monthNumber == 4 && it.upcoming != null }
        val up = assertNotNull(april.upcoming)
        assertEquals(listOf("Rent"), up.names)
        assertEquals(LocalDate.parse("2026-04-10"), up.first)
        assertEquals(-50_000, up.sum)
        assertEquals(40_000L - 50_000, april.carryOver)
    }

    @Test fun calendarGridStartsOnTheWeekStartAndListsEachDay() = runTest {
        val march = DateRange(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"))
        assertEquals(DateRange(LocalDate.parse("2026-02-23"), LocalDate.parse("2026-04-05")), calendarGrid(march, DayOfWeek.MONDAY))
        add(DraftType.EXPENSE, "2026-03-03", "acc_cash", 2_500, "cat_ex_0")
        add(DraftType.EXPENSE, "2026-03-03", "acc_cash", 500, "cat_ex_0")
        add(DraftType.TRANSFER, "2026-03-03", "acc_bank", 1_000, to = "acc_cash")
        add(DraftType.EXPENSE, "2026-04-02", "acc_cash", 900, "cat_ex_0")    // next month, still on the six-week grid
        val day = LocalDate.parse("2026-03-03")
        val cal = CalendarModel(activity(backgroundScope).state, repo.entryFeed(), backgroundScope).state.first { it.entriesOn(day).isNotEmpty() }
        assertEquals(3_000, cal.dayTotals[day]?.expense)
        assertEquals(3_000, cal.totals.expense)
        assertEquals(900, cal.dayTotals[LocalDate.parse("2026-04-02")]?.expense)
        assertEquals(3, cal.entriesOn(day).size)     // the transfer once
        assertTrue(cal.entriesOn(LocalDate.parse("2026-03-04")).isEmpty())
    }

    @Test fun monthsRollUpTheYearAndTheTotalCardMatchesTheReadModel() = runTest {
        add(DraftType.EXPENSE, "2026-01-03", "acc_cash", 2_500, "cat_ex_0")
        add(DraftType.EXPENSE, "2026-03-03", "acc_card", 4_000, "cat_ex_0")
        val m = MonthsModel(activity(backgroundScope).state, repo.entryFeed(), repo.views, repo.today, backgroundScope)
        val year = m.year.first { it.totals.expense == 6_500L }
        assertEquals(listOf(3, 2, 1), year.months.map { it.month.month })
        assertEquals(4_000, year.months.first().totals.expense)
        val month = m.month.first { it.totals.expense == 4_000L }
        assertEquals(4_000, month.cardExpense)
        assertEquals(repo.views.monthNow(YearMonth(2026, 3)).otherExpense, month.otherExpense)
    }

    @Test fun filterChoicesKeepDeletedCategoriesAndHiddenAccountsWithEntries() = runTest {
        add(DraftType.EXPENSE, "2026-03-03", "acc_cash", 2_500, "cat_ex_0")
        repo.accountStore.setAccountHidden("acc_cash", true)
        repo.categoryStore.deleteCategory("cat_ex_0")
        val march = DateRange(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"))
        val choices = filterChoices(
            repo.txnStore.txnsNow(march), repo.categoryStore.categoriesNow(), repo.categoryStore.categoryByIdNow(),
            repo.accountStore.accountsNow(), repo.accountStore.accountNames.first(),
        )
        assertEquals(2_500L, choices.expense.first { it.id == "cat_ex_0" }.amount)
        assertEquals(-2_500L, choices.accounts.first { it.id == "acc_cash" }.amount)
        assertTrue(choices.forTab(0).none { it.id == "cat_ex_0" })
    }

    @Test fun bulkCategoryChangeSkipsTransfersAndUndoPutsItBack() = runTest {
        val e = add(DraftType.EXPENSE, "2026-03-03", "acc_cash", 2_500, "cat_ex_0")
        val t = add(DraftType.TRANSFER, "2026-03-03", "acc_bank", 1_000, to = "acc_cash")
        val bulk = BulkEditModel(repo.txnStore, Dispatchers.Unconfined, this)
        var result: BulkResult? = null
        bulk.setCategory(listOf(repo.txnStore.txn(e)!!, repo.txnStore.txn(t)!!), "cat_ex_1") { result = it }
        advanceUntilIdle()
        val r = assertNotNull(result)
        assertTrue(r.partial)
        assertEquals(1, r.changed)
        assertEquals("cat_ex_1", repo.txnStore.txn(e)?.categoryId)
        r.undo()
        advanceUntilIdle()
        assertEquals("cat_ex_0", repo.txnStore.txn(e)?.categoryId)
    }

    @Test fun bulkDateChangeUndoesPerEntryAndDeleteRemovesAll() = runTest {
        val a = add(DraftType.EXPENSE, "2026-03-03", "acc_cash", 2_500, "cat_ex_0")
        val b = add(DraftType.EXPENSE, "2026-03-07", "acc_cash", 500, "cat_ex_0")
        val bulk = BulkEditModel(repo.txnStore, Dispatchers.Unconfined, this)
        var result: BulkResult? = null
        bulk.setDate(listOf(repo.txnStore.txn(a)!!, repo.txnStore.txn(b)!!), today) { result = it }
        advanceUntilIdle()
        assertFalse(assertNotNull(result).partial)
        assertEquals(today, repo.txnStore.txn(b)?.date)
        result!!.undo()
        advanceUntilIdle()
        assertEquals(LocalDate.parse("2026-03-03"), repo.txnStore.txn(a)?.date)
        assertEquals(LocalDate.parse("2026-03-07"), repo.txnStore.txn(b)?.date)
        var deleted = false
        bulk.delete(listOf(repo.txnStore.txn(a)!!, repo.txnStore.txn(b)!!)) { deleted = true }
        advanceUntilIdle()
        assertTrue(deleted)
        assertNull(repo.txnStore.liveTxn(a))
        assertNull(repo.txnStore.liveTxn(b))
    }
}
