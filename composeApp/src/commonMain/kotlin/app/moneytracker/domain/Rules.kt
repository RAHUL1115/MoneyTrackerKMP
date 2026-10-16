package app.moneytracker.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// ───────────────────────── dates & periods

fun daysInMonth(year: Int, month: Int): Int {
    val first = LocalDate(year, month, 1)
    return (first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))).dayOfMonth
}

fun dayInMonth(ym: YearMonth, day: Int): LocalDate =
    LocalDate(ym.year, ym.month, day.coerceIn(1, daysInMonth(ym.year, ym.month)))

data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(d: LocalDate) = d >= start && d <= endInclusive
}

/**
 * Budget/report month with a custom start day (salary-day accounting).
 * When startDay < 20 the period labelled M starts in M ("1st–31st", "5th–4th");
 * when startDay ≥ 20 it starts in the previous month (25 Feb – 24 Mar is "March").
 */
object Periods {
    /** Weekend handling for a period start that falls on Sat/Sun (e.g. payday moved by the bank). */
    enum class WeekendShift { NONE, BEFORE, AFTER }

    fun month(label: YearMonth, startDay: Int, shift: WeekendShift = WeekendShift.NONE): DateRange {
        if (startDay <= 1 && shift == WeekendShift.NONE) return DateRange(dayInMonth(label, 1), dayInMonth(label, 31))
        val startMonth = if (startDay < 20) label else label.plus(-1)
        val start = shifted(dayInMonth(startMonth, startDay), shift)
        val end = shifted(dayInMonth(startMonth.plus(1), startDay), shift).minus(DatePeriod(days = 1))
        return DateRange(start, end)
    }

    /** Moves a Saturday/Sunday to the previous Friday ([WeekendShift.BEFORE]) or next Monday ([WeekendShift.AFTER]). */
    fun shifted(d: LocalDate, shift: WeekendShift): LocalDate = when {
        shift == WeekendShift.NONE -> d
        d.dayOfWeek == DayOfWeek.SATURDAY -> if (shift == WeekendShift.BEFORE) d.minus(DatePeriod(days = 1)) else d.plus(DatePeriod(days = 2))
        d.dayOfWeek == DayOfWeek.SUNDAY -> if (shift == WeekendShift.BEFORE) d.minus(DatePeriod(days = 2)) else d.plus(DatePeriod(days = 1))
        else -> d
    }

    fun labelFor(date: LocalDate, startDay: Int, shift: WeekendShift = WeekendShift.NONE): YearMonth {
        val ym = YearMonth.of(date)
        for (candidate in listOf(ym, ym.plus(1), ym.plus(-1))) {
            if (date in month(candidate, startDay, shift)) return candidate
        }
        return ym
    }

    /** Weeks starting on [weekStart], clipped to [range]. */
    fun weeks(range: DateRange, weekStart: DayOfWeek = DayOfWeek.SUNDAY): List<DateRange> {
        val weekEnd = DayOfWeek.entries[(weekStart.ordinal + 6) % 7]
        val out = mutableListOf<DateRange>()
        var s = range.start
        while (s <= range.endInclusive) {
            var e = s
            while (e.dayOfWeek != weekEnd && e < range.endInclusive) e = e.plus(DatePeriod(days = 1))
            out += DateRange(s, e)
            s = e.plus(DatePeriod(days = 1))
        }
        return out
    }

    /** Credit-card billing cycle [day D, day D-1 of next month] containing [date]. */
    fun cardCycle(date: LocalDate, settlementDay: Int): DateRange {
        val ym = YearMonth.of(date)
        val thisStart = dayInMonth(ym, settlementDay)
        val startMonth = if (date >= thisStart) ym else ym.plus(-1)
        val start = dayInMonth(startMonth, settlementDay)
        return DateRange(start, dayInMonth(startMonth.plus(1), settlementDay).minus(DatePeriod(days = 1)))
    }
}

// ───────────────────────── recurrence

object Recurrence {
    /** Dates of [rule] not added yet that fall in [range] (the upcoming "Repeat" row). */
    fun upcoming(rule: RecurringRule, range: DateRange): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var d = rule.nextDate
        var guard = 0
        while (d <= range.endInclusive && (rule.endDate == null || d <= rule.endDate) && guard++ < 400) {
            if (d >= range.start) out += d
            d = next(d, rule.repeat)
        }
        return out
    }

    fun next(date: LocalDate, type: RepeatType): LocalDate = when (type) {
        RepeatType.DAILY -> date.plus(DatePeriod(days = 1))
        RepeatType.WEEKDAYS -> {
            var d = date.plus(DatePeriod(days = 1))
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plus(DatePeriod(days = 1))
            d
        }
        RepeatType.WEEKLY -> date.plus(DatePeriod(days = 7))
        RepeatType.BIWEEKLY -> date.plus(DatePeriod(days = 14))
        RepeatType.FOUR_WEEKLY -> date.plus(DatePeriod(days = 28))
        RepeatType.MONTHLY -> date.plus(DatePeriod(months = 1))
        RepeatType.BIMONTHLY -> date.plus(DatePeriod(months = 2))
        RepeatType.QUARTERLY -> date.plus(DatePeriod(months = 3))
        RepeatType.FOUR_MONTHLY -> date.plus(DatePeriod(months = 4))
        RepeatType.HALF_YEARLY -> date.plus(DatePeriod(months = 6))
        RepeatType.YEARLY -> date.plus(DatePeriod(years = 1))
        RepeatType.END_OF_MONTH -> {
            val nextMonth = YearMonth.of(date).plus(1)
            dayInMonth(nextMonth, 31)
        }
        RepeatType.WEEKENDS -> {
            var d = date.plus(DatePeriod(days = 1))
            while (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) d = d.plus(DatePeriod(days = 1))
            d
        }
    }

    /** Monthly rules anchored on the start day so "31st" doesn't drift to the 28th after February. */
    private fun monthStep(type: RepeatType) = when (type) {
        RepeatType.MONTHLY -> 1; RepeatType.BIMONTHLY -> 2; RepeatType.QUARTERLY -> 3; RepeatType.FOUR_MONTHLY -> 4
        RepeatType.HALF_YEARLY -> 6; RepeatType.YEARLY -> 12
        else -> 0
    }

    private fun isMonthBased(type: RepeatType) = monthStep(type) > 0 || type == RepeatType.END_OF_MONTH

    /** n-th occurrence of a month-based rule, computed from the anchor so day-of-month never drifts. */
    fun nthOccurrence(start: LocalDate, type: RepeatType, n: Int): LocalDate {
        val months = monthStep(type)
        if (months > 0) return dayInMonth(YearMonth.of(start).plus(months * n), start.dayOfMonth)
        if (type == RepeatType.END_OF_MONTH) return dayInMonth(YearMonth.of(start).plus(n), 31)
        var d = start
        repeat(n) { d = next(d, type) }
        return d
    }

    /**
     * Dates due for [rule] up to and including [today], capped at [limit].
     * Returns the due dates and the rule's new nextDate (null when the rule has ended).
     */
    fun due(rule: RecurringRule, today: LocalDate, limit: Int = 500): Pair<List<LocalDate>, LocalDate?> {
        val monthBased = isMonthBased(rule.repeat)
        var n = 0
        if (monthBased) while (nthOccurrence(rule.startDate, rule.repeat, n) < rule.nextDate) n++
        var d = if (monthBased) nthOccurrence(rule.startDate, rule.repeat, n) else rule.nextDate
        val out = mutableListOf<LocalDate>()
        while (d <= today && out.size < limit) {
            if (rule.endDate != null && d > rule.endDate) return out to null
            out += d
            d = if (monthBased) nthOccurrence(rule.startDate, rule.repeat, ++n) else next(d, rule.repeat)
        }
        val next = if (rule.endDate != null && d > rule.endDate) null else d
        return out to next
    }
}

// ───────────────────────── installments

/** Splits [total] into [count] parts; the first part absorbs the remainder. */
fun splitInstallments(total: Long, count: Int): List<Long> {
    require(count >= 1)
    val share = total / count
    val rem = total - share * count
    return List(count) { i -> if (i == 0) share + rem else share }
}

// ───────────────────────── income / expense classification

data class ReportRow(
    val kind: TxnKind,
    val amount: Long,
    val categoryId: String?,
    val srcTransferExpense: Boolean,
    val dstTransferExpense: Boolean,
    val date: LocalDate? = null,
    val srcIsCard: Boolean = false,
    val dstIsCard: Boolean = false,
    /** Setting "card expenses count when the bill is paid" (instead of at purchase). */
    val cardAtPayment: Boolean = false,
    /** The entry itself, for per-account / per-tag breakdowns and the top-entries list. */
    val txnId: String = "",
    val accountId: String = "",
    val note: String = "",
)

enum class Flow { INCOME, EXPENSE, NONE }

/**
 * Only INCOME, EXPENSE and TRANSFER_OUT rows matter (TRANSFER_IN is the mirror, adjustments are not cash flow).
 * A transfer into a "transfer counts as expense" account (savings, investments) is an expense;
 * a transfer back out of one into a normal account is income.
 */
fun classify(row: ReportRow): Flow = when (row.kind) {
    TxnKind.INCOME -> Flow.INCOME
    // In "count at payment" mode, card purchases are not expenses yet; the bill payment is.
    TxnKind.EXPENSE -> if (row.cardAtPayment && row.srcIsCard) Flow.NONE else Flow.EXPENSE
    TxnKind.TRANSFER_OUT -> when {
        row.cardAtPayment && row.dstIsCard && !row.srcIsCard -> Flow.EXPENSE
        !row.srcTransferExpense && row.dstTransferExpense -> Flow.EXPENSE
        row.srcTransferExpense && !row.dstTransferExpense -> Flow.INCOME
        else -> Flow.NONE
    }
    else -> Flow.NONE
}

data class Totals(val income: Long, val expense: Long) {
    val net get() = income - expense
}

fun totals(rows: List<ReportRow>): Totals {
    var inc = 0L; var exp = 0L
    for (r in rows) when (classify(r)) {
        Flow.INCOME -> inc += r.amount
        Flow.EXPENSE -> exp += r.amount
        Flow.NONE -> Unit
    }
    return Totals(inc, exp)
}

// ───────────────────────── credit card statement

data class CardStatus(
    /** Charges from the last closed cycle not yet covered by payments. */
    val statementDue: Long,
    /** Charges in the current (open) cycle not covered by payments. */
    val currentCycle: Long,
    /** Charges from cycles before the last one still unpaid. */
    val overdue: Long,
    /** Future-dated charges (later installments) not yet billed. */
    val upcoming: Long = 0,
    /** Gross charges dated in the current cycle (for spending limits). */
    val cycleSpend: Long = 0,
) {
    val totalOwed get() = statementDue + currentCycle + overdue
}

/**
 * FIFO allocation: every payment/credit on the card settles the oldest charges first.
 * Charges = EXPENSE, TRANSFER_OUT, ADJUST_DOWN. Payments = INCOME, TRANSFER_IN, ADJUST_UP.
 */
fun cardStatus(txns: List<Txn>, settlementDay: Int, today: LocalDate, prepaid: Set<String> = emptySet()): CardStatus {
    val current = Periods.cardCycle(today, settlementDay)
    val previous = Periods.cardCycle(current.start.minus(DatePeriod(days = 1)), settlementDay)
    var payments = txns.filter { it.kind.sign > 0 }.sumOf { it.accountValue }
    val charges = txns.filter { it.kind.sign < 0 }

    fun take(amount: Long): Long {          // returns the unpaid part
        val paid = minOf(amount, payments)
        payments -= paid
        return amount - paid
    }
    // Prepayment-tagged charges are due at once: before the current cycle they join the older charges,
    // from it onward they're settled right after them and show as overdue until paid.
    val (tagged, regular) = charges.partition { it.id in prepaid }
    val older = take(regular.filter { it.date < previous.start }.sumOf { it.accountValue } +
        tagged.filter { it.date < current.start }.sumOf { it.accountValue })
    val unpaidPrepaid = take(tagged.filter { it.date >= current.start }.sumOf { it.accountValue })
    val statement = take(regular.filter { it.date in previous }.sumOf { it.accountValue })
    val cycleSpend = charges.filter { it.date in current && it.kind == TxnKind.EXPENSE }.sumOf { it.accountValue }
    val currentCharges = take(regular.filter { it.date in current }.sumOf { it.accountValue })
    val upcoming = take(regular.filter { it.date > current.endInclusive }.sumOf { it.accountValue })
    return CardStatus(statement, currentCharges, older + unpaidPrepaid, upcoming, cycleSpend)
}

/** Card spending for the "Monthly card usage" target. */
data class CardUsage(val thisCycle: Long, val measured: Long)

/**
 * Spending (purchases and transfers out) in the current billing cycle, and the figure the target is checked against:
 * the previous cycle ([averageOf3] false) or the average of the previous three.
 */
fun cardUsage(txns: List<Txn>, settlementDay: Int, today: LocalDate, averageOf3: Boolean, excluded: Set<String> = emptySet()): CardUsage {
    val cycles = generateSequence(Periods.cardCycle(today, settlementDay)) { Periods.cardCycle(it.start.minus(DatePeriod(days = 1)), settlementDay) }
        .take(4).toList()
    fun spend(r: DateRange) = txns.filter { it.date in r && it.id !in excluded && (it.kind == TxnKind.EXPENSE || it.kind == TxnKind.TRANSFER_OUT) }
        .sumOf { it.accountValue }
    val measured = if (averageOf3) (1..3).sumOf { spend(cycles[it]) } / 3 else spend(cycles[1])
    return CardUsage(spend(cycles[0]), measured)
}

// ───────────────────────── budgets

data class BudgetEntry(val categoryId: String, val period: Int, val amount: Long)

/** Effective budget per category for [period]: the latest entry at or before it (a step function). */
fun effectiveBudgets(entries: List<BudgetEntry>, period: YearMonth): Map<String, Long> =
    entries.filter { it.period <= period.key }
        .groupBy { it.categoryId }
        .mapValues { (_, list) -> list.maxBy { it.period }.amount }
        .filterValues { it > 0 }

data class BudgetLine(val categoryId: String, val budget: Long, val spent: Long) {
    val remaining get() = budget - spent
    val ratio: Float get() = if (budget <= 0) 0f else spent.toFloat() / budget
}

const val TOTAL_BUDGET = ""

/** [spentByMainCategory] keys are main-category ids. Total budget (key "") compares against all expense. */
fun budgetLines(
    budgets: Map<String, Long>,
    spentByMainCategory: Map<String, Long>,
    /** Sub-category budgets also get lines but are left out of the synthesised total (their parent covers them). */
    spentBySubCategory: Map<String, Long> = emptyMap(),
    isSubCategory: (String) -> Boolean = { false },
): List<BudgetLine> {
    val lines = budgets.filterKeys { it != TOTAL_BUDGET }
        .map { (cat, amt) -> BudgetLine(cat, amt, (if (isSubCategory(cat)) spentBySubCategory[cat] else spentByMainCategory[cat]) ?: 0) }
        .sortedByDescending { it.ratio }
    val top = lines.filterNot { isSubCategory(it.categoryId) }
    val total = budgets[TOTAL_BUDGET]?.let { BudgetLine(TOTAL_BUDGET, it, spentByMainCategory.values.sum()) }
        ?: if (top.isNotEmpty()) BudgetLine(TOTAL_BUDGET, top.sumOf { it.budget }, top.sumOf { it.spent }) else null
    return listOfNotNull(total) + lines
}

// ───────────────────────── account summary

data class AccountLine(
    val account: Account,
    val group: AccountGroup,
    /** Own rows only. For a bank this excludes linked debit-card spending; see [effectiveBalance]. */
    val balance: Long,
    /** What the account really holds: bank balance minus linked debit-card spending. */
    val effectiveBalance: Long,
    val card: CardStatus? = null,
)

data class NetWorth(val assets: Long, val liabilities: Long) {
    val net get() = assets - liabilities
}

/**
 * Debit cards draw on their linked bank account, so their rows are folded into that account's
 * effective balance and excluded from totals themselves. Credit cards are liabilities.
 */
fun summarize(
    groups: List<AccountGroup>,
    accounts: List<Account>,
    balances: Map<String, Long>,
    cardStatusOf: (Account) -> CardStatus?,
    /** Converts an account-currency amount to the main currency (identity for single-currency setups). */
    toMain: (Account, Long) -> Long = { _, v -> v },
): Pair<List<AccountLine>, NetWorth> {
    val groupById = groups.associateBy { it.id }
    val debitByBank = accounts
        .filter { groupById[it.groupId]?.type?.isDebitCard == true && it.cardPaymentAccountId != null }
        .groupBy({ it.cardPaymentAccountId!! }, { balances[it.id] ?: 0L })
        .mapValues { it.value.sum() }

    val lines = accounts.mapNotNull { a ->
        val g = groupById[a.groupId] ?: return@mapNotNull null
        val own = balances[a.id] ?: 0L
        AccountLine(a, g, own, own + (debitByBank[a.id] ?: 0L), if (g.type.isCreditCard) cardStatusOf(a) else null)
    }
    var assets = 0L
    var liabilities = 0L
    for (l in lines) {
        if (!l.account.includeInTotals || l.group.type.isDebitCard) continue
        val b = toMain(l.account, l.effectiveBalance)
        if (b >= 0) assets += b else liabilities += -b
    }
    return lines to NetWorth(assets, liabilities)
}
