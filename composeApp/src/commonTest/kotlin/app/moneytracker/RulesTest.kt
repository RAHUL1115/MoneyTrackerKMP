package app.moneytracker

import app.moneytracker.domain.BudgetEntry
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Periods
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.cardStatus
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.domain.splitInstallments
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RulesTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun monthPeriodWithDefaultStartDay() {
        val p = Periods.month(YearMonth(2026, 2), 1)
        assertEquals(d("2026-02-01"), p.start); assertEquals(d("2026-02-28"), p.endInclusive)
    }

    @Test fun monthPeriodEarlyStartDayBelongsToSameMonth() {
        val p = Periods.month(YearMonth(2026, 3), 5)
        assertEquals(d("2026-03-05"), p.start); assertEquals(d("2026-04-04"), p.endInclusive)
    }

    @Test fun monthPeriodLateStartDayBelongsToNextMonthLabel() {
        val p = Periods.month(YearMonth(2026, 3), 25)
        assertEquals(d("2026-02-25"), p.start); assertEquals(d("2026-03-24"), p.endInclusive)
        assertEquals(YearMonth(2026, 3), Periods.labelFor(d("2026-02-27"), 25))
    }

    @Test fun startDay31ClampsToShortMonths() {
        val p = Periods.month(YearMonth(2026, 3), 31)     // Feb has 28 days
        assertEquals(d("2026-02-28"), p.start); assertEquals(d("2026-03-30"), p.endInclusive)
    }

    @Test fun monthlyRecurrenceDoesNotDriftAfterFebruary() {
        val rule = RecurringRule("r", TxnKind.EXPENSE, "a", null, null, 100, "", RepeatType.MONTHLY,
            d("2026-01-31"), d("2026-01-31"), null)
        val (dates, next) = Recurrence.due(rule, d("2026-04-30"))
        assertEquals(listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30")), dates)
        assertEquals(d("2026-05-31"), next)
    }

    @Test fun recurrenceResumesFromNextDateAndHonoursEndDate() {
        val rule = RecurringRule("r", TxnKind.EXPENSE, "a", null, null, 100, "", RepeatType.WEEKLY,
            d("2026-01-01"), d("2026-01-15"), endDate = d("2026-01-29"))
        val (dates, next) = Recurrence.due(rule, d("2026-03-01"))
        assertEquals(listOf(d("2026-01-15"), d("2026-01-22"), d("2026-01-29")), dates)
        assertNull(next)
    }

    @Test fun weekdaysSkipWeekend() {
        assertEquals(d("2026-10-12"), Recurrence.next(d("2026-10-09"), RepeatType.WEEKDAYS))   // Fri → Mon
    }

    @Test fun weekendsRepeatJumpsToSaturdayThenSunday() {
        assertEquals(d("2026-10-17"), Recurrence.next(d("2026-10-13"), RepeatType.WEEKENDS))   // Tue → Sat
        assertEquals(d("2026-10-18"), Recurrence.next(d("2026-10-17"), RepeatType.WEEKENDS))   // Sat → Sun
    }

    @Test fun installmentsRemainderGoesToFirst() {
        assertEquals(listOf(3334L, 3333L, 3333L), splitInstallments(10000, 3))
    }

    @Test fun transferIntoSavingsIsExpenseAndBackIsIncome() {
        assertEquals(Flow.EXPENSE, classify(ReportRow(TxnKind.TRANSFER_OUT, 1, null, false, true)))
        assertEquals(Flow.INCOME, classify(ReportRow(TxnKind.TRANSFER_OUT, 1, null, true, false)))
        assertEquals(Flow.NONE, classify(ReportRow(TxnKind.TRANSFER_OUT, 1, null, false, false)))
        assertEquals(Flow.NONE, classify(ReportRow(TxnKind.ADJUST_UP, 1, null, false, false)))
    }

    private fun t(kind: TxnKind, date: String, amount: Long) = Txn(date + kind + amount, kind, d(date), 0, "card", amount = amount)

    @Test fun cardPaymentsSettleOldestChargesFirst() {
        // Cycle starts on the 10th. Today 2026-03-15 → current [03-10, 04-09], previous [02-10, 03-09].
        val txns = listOf(
            t(TxnKind.EXPENSE, "2026-01-20", 300),   // older than the previous cycle
            t(TxnKind.EXPENSE, "2026-02-15", 500),   // previous cycle (statement)
            t(TxnKind.EXPENSE, "2026-03-12", 200),   // current cycle
            t(TxnKind.TRANSFER_IN, "2026-03-01", 400) // payment
        )
        val s = cardStatus(txns, 10, d("2026-03-15"))
        assertEquals(0, s.overdue)            // 300 paid
        assertEquals(400, s.statementDue)     // 500 - remaining 100
        assertEquals(200, s.currentCycle)
        assertEquals(600, s.totalOwed)
    }

    @Test fun futureInstallmentsAreUpcomingNotCurrentCycle() {
        val txns = listOf(t(TxnKind.EXPENSE, "2026-03-12", 300), t(TxnKind.EXPENSE, "2026-04-12", 300), t(TxnKind.EXPENSE, "2026-05-12", 300))
        val s = cardStatus(txns, 10, d("2026-03-15"))
        assertEquals(300, s.currentCycle)
        assertEquals(600, s.upcoming)
    }

    @Test fun budgetIsStepFunctionAndTotalIsSynthesised() {
        val entries = listOf(BudgetEntry("food", 202601, 1000), BudgetEntry("food", 202604, 1500), BudgetEntry("fun", 202603, 0))
        assertEquals(mapOf("food" to 1000L), effectiveBudgets(entries, YearMonth(2026, 3)))
        assertEquals(mapOf("food" to 1500L), effectiveBudgets(entries, YearMonth(2026, 6)))
        val lines = budgetLines(mapOf("food" to 1000L), mapOf("food" to 250L, "other" to 999L))
        assertEquals(TOTAL_BUDGET, lines.first().categoryId)
        assertEquals(250, lines.first().spent)     // synthetic total only counts budgeted categories
        assertEquals(750, lines[1].remaining)
    }

    @Test fun currencyParseAndFormat() {
        val c = CurrencyFormat("$", 2)
        assertEquals(123450L, c.parse("1,234.5"))
        assertNull(c.parse("1.234"))
        assertEquals("$ 1,234.50", c.format(123450))
        assertEquals("-$ 0.05", c.format(-5))
        assertEquals("12.3", c.toInput(1230))
        assertEquals("¥ 1,500", CurrencyFormat("¥", 0).format(1500))
        assertEquals("1,234.50", c.format(123450, withSymbol = false))
    }
}
