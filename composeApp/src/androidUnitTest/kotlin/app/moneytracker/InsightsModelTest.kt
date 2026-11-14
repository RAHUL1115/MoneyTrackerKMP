package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.YearMonth
import app.moneytracker.insights.BreakdownLink
import app.moneytracker.insights.InsightsChoice
import app.moneytracker.insights.InsightsGroup
import app.moneytracker.insights.InsightsModel
import app.moneytracker.insights.InsightsSide
import app.moneytracker.insights.InsightsSpan
import app.moneytracker.insights.NO_TAG_KEY
import app.moneytracker.insights.TRANSFERS_KEY
import app.moneytracker.insights.UNCATEGORIZED_KEY
import app.moneytracker.insights.classic.statsTrendOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate

/** Insights (new layout) and Stats (classic): the period choice and the figures both layouts draw. */
class InsightsModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private val march = YearMonth(2026, 3)
    private lateinit var repo: MoneyRepository
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun model(start: InsightsChoice? = null) =
        InsightsModel(repo.reportStore, repo.categoryStore, repo.accountStore, repo.tagStore, repo.settingsStore, repo.views, repo.today, scope, start)

    private fun spend(amount: Long, date: LocalDate = today, category: String? = "cat_ex_0", account: String = "acc_cash", tags: List<String> = emptyList()) =
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, date, account, categoryId = category, amount = amount, tagIds = tags))

    private fun earn(amount: Long, date: LocalDate = today) =
        repo.txnStore.create(TxnDraft(DraftType.INCOME, date, "acc_bank", categoryId = "cat_in_0", amount = amount))

    private fun day(text: String) = LocalDate.parse(text)

    @Test fun startsOnThisMonthsSpendingByCategory() {
        spend(10_000); spend(4_000, category = "cat_ex_0_1"); spend(2_000, category = "cat_ex_1"); spend(500, category = null)
        val s = model().state.value
        assertEquals(InsightsSpan.MONTH, s.choice.span)
        assertEquals(DateRange(day("2026-03-01"), day("2026-03-31")), s.range)
        assertEquals(16_500, s.total)
        // A sub-category counts under its main category; biggest first.
        assertEquals(listOf("cat_ex_0", "cat_ex_1", UNCATEGORIZED_KEY), s.parts.map { it.key })
        assertEquals(14_000, s.parts.first().amount)
        assertEquals(BreakdownLink.CATEGORY_ENTRIES, s.parts.first().link)
        assertNull(s.parts.last().link)
    }

    @Test fun refundsComeOffTheirCategoryAndAreShownSeparately() {
        spend(10_000)
        spend(-3_000)
        val part = model().state.value.parts.single()
        assertEquals(7_000, part.amount)
        assertEquals(3_000, part.refunded)
    }

    @Test fun comparesWithThePeriodBefore() {
        spend(8_000, date = day("2026-02-10"))
        spend(10_000)
        val s = model().state.value
        assertEquals(8_000, s.previousTotal)
        assertEquals(8_000, s.parts.single().previous)
    }

    @Test fun dailyAverageAndProjectionUseTheDaysGoneSoFar() {
        spend(30_000)
        val s = model().state.value
        assertEquals(15, s.elapsedDays)
        assertEquals(2_000, s.dailyAverage)
        assertEquals(62_000, s.projected)                                    // 30,000 over 15 of 31 days
        val feb = model().apply { step(-1) }.state.value
        assertEquals(28, feb.elapsedDays)
        assertNull(feb.projected)                                            // the period is over
    }

    @Test fun netSideAndSavedPercent() {
        earn(100_000); spend(25_000)
        val m = model()
        m.pickSide(InsightsSide.NET)
        assertEquals(75_000, m.state.value.total)
        assertEquals(75, m.state.value.savedPercent)
        m.pickSide(InsightsSide.INCOME)
        assertEquals(100_000, m.state.value.total)
        assertEquals(listOf("cat_in_0"), m.state.value.parts.map { it.key })
    }

    @Test fun groupsByAccountAndByTagWithAnEntryUnderEachOfItsTags() {
        val trip = repo.tagStore.tagNamed("Trip")
        val work = repo.tagStore.tagNamed("Work")
        spend(5_000, tags = listOf(trip)); spend(3_000, tags = listOf(trip, work)); spend(1_000, account = "acc_bank")
        val m = model()
        m.pickGroup(InsightsGroup.ACCOUNT)
        assertEquals(listOf("acc_cash" to 8_000L, "acc_bank" to 1_000L), m.state.value.parts.map { it.key to it.amount })
        assertEquals(BreakdownLink.ACCOUNT, m.state.value.parts.first().link)
        m.pickGroup(InsightsGroup.TAG)
        assertEquals(listOf("Trip" to 8_000L, "Work" to 3_000L, NO_TAG_KEY to 1_000L), m.state.value.parts.map { it.key to it.amount })
    }

    @Test fun moneyMovedToSavingsHasItsOwnPart() {
        repo.txnStore.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_savings", amount = 20_000))
        // The seeded savings account counts money moved into it as spending.
        assertEquals(listOf(TRANSFERS_KEY to 20_000L), model().state.value.parts.map { it.key to it.amount })
    }

    @Test fun topEntriesAreTheFiveBiggest() {
        listOf(100L, 700, 300, 900, 500, 200).forEach { spend(it) }
        assertEquals(listOf(900L, 700, 500, 300, 200), model().state.value.top.map { it.amount })
    }

    @Test fun stepsByTheShownSpan() {
        val m = model()
        m.step(-1)
        assertEquals(YearMonth(2026, 2), m.state.value.choice.month)
        m.pickSpan(InsightsSpan.YEAR); m.step(1)
        assertEquals(DateRange(day("2027-01-01"), day("2027-12-31")), m.state.value.range)
        m.pickSpan(InsightsSpan.WEEK)
        val week = m.state.value.range
        m.step(-1)
        assertEquals(week.start.toEpochDays() - 7, m.state.value.range.start.toEpochDays())
    }

    @Test fun customPeriodStepsByItsOwnLengthAndOrdersItsDates() {
        val m = model()
        m.pickSpan(InsightsSpan.CUSTOM)
        m.pickStart(day("2026-03-20")); m.pickEnd(day("2026-03-11"))
        assertEquals(DateRange(day("2026-03-11"), day("2026-03-20")), m.state.value.range)
        m.step(1)
        assertEquals(DateRange(day("2026-03-21"), day("2026-03-30")), m.state.value.range)
    }

    @Test fun trendShowsSixMonthsAndATappedBarOpensItsMonth() {
        spend(4_000, date = day("2025-12-05")); spend(6_000)
        val m = model()
        val s = m.state.value
        assertEquals(6, s.slots.size)
        assertEquals(listOf(0L, 0, 4_000, 0, 0, 6_000), s.slotValues)
        assertEquals(5, s.slotHighlight)
        m.openSlot(2)
        assertEquals(YearMonth(2025, 12), m.state.value.choice.month)
        assertEquals(4_000, m.state.value.total)
    }

    @Test fun aTappedWeekBarOpensThatWeek() {
        val m = model()
        m.pickSpan(InsightsSpan.WEEK)
        val before = m.state.value.range
        m.openSlot(4)
        assertEquals(before.start.toEpochDays() - 7, m.state.value.range.start.toEpochDays())
        assertEquals(InsightsSpan.WEEK, m.state.value.choice.span)
    }

    @Test fun classicTrendCountsOnlyItsOwnDaysForALongCustomPeriod() {
        spend(1_000, date = day("2026-01-05")); spend(2_000, date = day("2026-01-20")); spend(4_000, date = day("2026-03-02"))
        val m = model()
        m.pickSpan(InsightsSpan.CUSTOM)
        m.pickStart(day("2026-01-10")); m.pickEnd(day("2026-03-15"))
        val trend = statsTrendOf(m.state.value)
        // Months January to March, with January only from the 10th (the new layout's bars cover the whole month).
        assertContentEquals(listOf(2_000L, 0, 4_000), trend.values)
        assertEquals(2, trend.highlight)
    }

    @Test fun classicTrendForAYearIsByMonthAndOpensAMonth() {
        spend(3_000, date = day("2026-01-15"))
        val m = model()
        m.pickSpan(InsightsSpan.YEAR)
        val trend = statsTrendOf(m.state.value)
        assertEquals(12, trend.values.size)
        assertEquals(3_000, trend.values[0])
        assertEquals(2, trend.highlight)
        assertEquals(YearMonth(2026, 1), trend.open?.invoke(0))
    }

    @Test fun budgetCardFollowsTheShownMonth() {
        repo.reportStore.setBudget(TOTAL_BUDGET, YearMonth(2026, 2), 50_000)
        val m = model()
        assertEquals(march, m.state.value.budgetMonth)
        assertEquals(50_000, m.state.value.budget.total?.budget)            // budgets carry forward
        m.pickSpan(InsightsSpan.YEAR); m.step(-1)
        assertEquals(march, m.state.value.budgetMonth)                       // other spans show this month's budget
    }

    @Test fun followsNewEntries() {
        val m = model()
        assertEquals(0, m.state.value.total)
        spend(1_234)
        assertEquals(1_234, m.state.value.total)
    }

    @Test fun choiceSurvivesAsPlainNumbers() {
        val c = InsightsChoice(InsightsSpan.CUSTOM, InsightsSide.NET, InsightsGroup.TAG, 202603, 20_000, 20_100, 20_150)
        assertEquals(c, InsightsChoice.fromInts(c.toInts()))
        assertEquals(c, model(c).state.value.choice)
    }
}
