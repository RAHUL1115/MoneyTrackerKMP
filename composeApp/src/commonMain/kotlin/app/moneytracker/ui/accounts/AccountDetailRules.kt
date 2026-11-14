package app.moneytracker.ui.accounts

import app.moneytracker.core.tr
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Periods
import app.moneytracker.domain.Txn
import app.moneytracker.domain.YearMonth
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

// The figures an account's detail screen shows, worked out from its history; the same in both layouts.

/** Money in, money out and the balance at the end of one month or year. [month] is set for months, null for years. */
class PeriodSum(val label: String, val range: DateRange, val deposit: Long, val withdrawal: Long, val balance: Long, val month: YearMonth?)

/** The twelve months of [ym]'s year, or (when [yearly]) every year from the first entry up to this one. */
fun periodSums(history: List<Txn>, ym: YearMonth, yearly: Boolean, startDay: Int, today: LocalDate): List<PeriodSum> {
    val periods: List<Pair<String, DateRange>> = if (yearly) {
        val first = history.firstOrNull()?.date?.year ?: today.year
        (first..maxOf(first, today.year)).map { y ->
            y.toString() to DateRange(Periods.month(YearMonth(y, 1), startDay).start, Periods.month(YearMonth(y, 12), startDay).endInclusive)
        }
    } else {
        (1..12).map { m -> monthShort(m) to Periods.month(YearMonth(ym.year, m), startDay) }
    }
    return periods.mapIndexed { i, (label, r) ->
        val inRange = history.filter { it.date in r }
        PeriodSum(
            label, r, inRange.depositTotal(), inRange.withdrawalTotal(),
            history.filter { it.date <= r.endInclusive }.sumOf { it.signedAccountValue },
            if (yearly) null else YearMonth(ym.year, i + 1),
        )
    }
}

/** Money that raised the account's balance. */
fun List<Txn>.depositTotal(): Long = filter { it.kind.raisesBalance }.sumOf { it.accountValue }

/** Money that lowered the account's balance. */
fun List<Txn>.withdrawalTotal(): Long = filter { it.kind.lowersBalance }.sumOf { it.accountValue }

/** One billing cycle of a card: what was charged, and what of it is still unpaid (payments settle the oldest charges first). */
class Cycle(val range: DateRange, val charged: Long, val unpaid: Long)

/** The last [count] billing cycles of a card, the open one first. */
fun cycles(history: List<Txn>, settlementDay: Int, today: LocalDate, count: Int): List<Cycle> {
    val ranges = generateSequence(Periods.cardCycle(today, settlementDay)) { Periods.cardCycle(it.start.minus(DatePeriod(days = 1)), settlementDay) }
        .take(count).toList().reversed()
    val charges = history.filter { it.kind.lowersBalance }
    var payments = history.depositTotal()
    payments -= minOf(payments, charges.filter { it.date < ranges.first().start }.sumOf { it.accountValue })
    return ranges.map { r ->
        val charged = charges.filter { it.date in r }.sumOf { it.accountValue }
        val paid = minOf(charged, payments)
        payments -= paid
        Cycle(r, charged, charged - paid)
    }.reversed()
}

/** Accounts a card bill can be paid from: visible, not a credit card, the card's own payment account first. */
fun paySources(card: Account, accounts: List<Account>, groups: List<AccountGroup>): List<Account> {
    val cardGroups = groups.filter { it.type.isCreditCard }.map { it.id }.toSet()
    return accounts.filter { it.id != card.id && !it.hidden && it.groupId !in cardGroups }
        .sortedBy { if (it.id == card.cardPaymentAccountId) 0 else 1 }
}

/** A card payment as a transfer from [from] into the card, so it isn't counted as spending. */
fun cardPayment(card: Account, from: String?, date: LocalDate, amount: Long): TxnDraft =
    TxnDraft(DraftType.TRANSFER, date, from.orEmpty(), toAccountId = card.id, amount = amount, note = tr("Card payment"), currency = card.currency)

/** A typed balance: a leading sign from [minusSigns] makes it negative. Null when it isn't a number. */
fun parseBalance(text: String, fmt: CurrencyFormat, minusSigns: List<String> = listOf("-", "−")): Long? {
    val trimmed = text.trim()
    val negative = minusSigns.any { trimmed.startsWith(it) }
    val digits = minusSigns.fold(trimmed) { s, sign -> s.removePrefix(sign) }
    return fmt.parse(digits)?.let { if (negative) -it else it }
}

/**
 * What the new layout's Adjust sheet works out from the typed figure [typed]. Cards are adjusted by what is owed
 * (a positive number), every other account by its balance.
 */
class BalanceChange(line: AccountLine, val typed: Long?) {
    val isCard: Boolean = line.card != null
    /** The current figure as the user thinks of it. */
    val shown: Long = if (isCard) -line.balance else line.balance
    /** The balance to store. */
    val target: Long? = typed?.let { if (isCard) -it else it }
    val diff: Long = target?.let { it - line.balance } ?: 0L
    val increase: Boolean get() = diff > 0
    val canContinue: Boolean get() = target != null && diff != 0L
}
