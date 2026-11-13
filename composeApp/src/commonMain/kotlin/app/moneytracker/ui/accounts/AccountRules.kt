package app.moneytracker.ui.accounts

import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.Currency
import app.moneytracker.domain.Fx
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.ui.tr
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// Account rules both layouts draw from: card bills, the balance a row shows, totals in the main currency.

/** What a credit card owes now: last statement plus anything older. */
internal val AccountLine.billDue: Long get() = card?.let { it.statementDue + it.overdue } ?: 0L

/** Everything charged to a card and not paid yet, including the open cycle and later instalments. */
internal val AccountLine.owed: Long get() = card?.let { it.totalOwed + it.upcoming } ?: 0L

/** The balance a row shows: a debit card its own rows, any other account what it holds after linked debit-card spends. */
internal val AccountLine.shownBalance: Long get() = if (group.type.isDebitCard) balance else effectiveBalance

/** Sum of [value] over the lines counted in totals, each converted to the main currency. */
internal fun List<AccountLine>.mainTotal(currencies: Map<String, Currency>, decimals: Int, value: (AccountLine) -> Long): Long =
    filter { it.account.includeInTotals }.sumOf { Fx.toMain(value(it), it.account.currency?.let { c -> currencies[c] }, decimals) }

/** The day the last closed statement has to be paid, from the card's payment day; null when no payment day is set. */
internal fun billDueDate(a: Account, today: LocalDate): LocalDate? {
    val payDay = a.dueDay
    val closed = Periods.cardCycle(today, a.statementDay).start.minus(DatePeriod(days = 1))
    var ym = YearMonth.of(closed)
    repeat(3) {
        val d = dayIn(ym, payDay)
        if (d > closed) return d
        ym = ym.plus(1)
    }
    return null
}

/** [day] of the month [ym], moved back to the month's last day when the month is shorter. */
internal fun dayIn(ym: YearMonth, day: Int): LocalDate {
    val first = LocalDate(ym.year, ym.month, 1)
    val last = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).dayOfMonth
    return LocalDate(ym.year, ym.month, minOf(day, last))
}

/** The account type in the user's language. */
internal val AccountType.displayName: String get() = when (this) {
    AccountType.CREDIT_CARD -> tr("Credit card")
    AccountType.DEBIT_CARD -> tr("Debit card")
    AccountType.OVERDRAFT -> tr("Overdraft")
    AccountType.PREPAID -> tr("Top-up / prepaid")
    else -> tr(name.lowercase().replaceFirstChar { it.uppercase() })
}
