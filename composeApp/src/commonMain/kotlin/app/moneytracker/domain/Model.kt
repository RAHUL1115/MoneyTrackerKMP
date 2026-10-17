package app.moneytracker.domain

import kotlinx.datetime.LocalDate

enum class AccountType(val isLiability: Boolean, val isCreditCard: Boolean = false, val isDebitCard: Boolean = false) {
    CASH(false), BANK(false), SAVINGS(false), INVESTMENT(false), PREPAID(false), INSURANCE(false), OTHER(false),
    CREDIT_CARD(true, isCreditCard = true), DEBIT_CARD(false, isDebitCard = true), LOAN(true), OVERDRAFT(true),
}

enum class TxnKind(val sign: Int) {
    INCOME(+1), EXPENSE(-1), TRANSFER_OUT(-1), TRANSFER_IN(+1), ADJUST_UP(+1), ADJUST_DOWN(-1);

    val isTransfer get() = this == TRANSFER_OUT || this == TRANSFER_IN
}

/**
 * A refund is stored as an expense with a negative amount (money back into the account, spending in its
 * category goes down), the same way Money Manager stores one, so every expense total nets it out.
 */
fun isRefund(kind: TxnKind?, amount: Long?) = kind == TxnKind.EXPENSE && amount != null && amount < 0

enum class CategoryKind { INCOME, EXPENSE }

enum class RepeatType(val label: String) {
    DAILY("Every day"), WEEKDAYS("Weekdays"), WEEKLY("Every week"), BIWEEKLY("Every 2 weeks"),
    FOUR_WEEKLY("Every 4 weeks"), MONTHLY("Every month"), BIMONTHLY("Every 2 months"),
    QUARTERLY("Every 3 months"), FOUR_MONTHLY("Every 4 months"), HALF_YEARLY("Every 6 months"), YEARLY("Every year"), END_OF_MONTH("End of every month"),
    WEEKENDS("Weekends"),
}

data class AccountGroup(val id: String, val name: String, val type: AccountType, val sortOrder: Int)

data class Account(
    val id: String,
    val groupId: String,
    val name: String,
    val includeInTotals: Boolean = true,
    val transferAsExpense: Boolean = false,
    val cardSettlementDay: Int? = null,
    val cardPaymentAccountId: String? = null,
    val sortOrder: Int = 0,
    /** ISO code; null = main currency. */
    val currency: String? = null,
    /** Credit cards: monthly spending limit in minor units. */
    val cardLimit: Long? = null,
    /** Hidden from lists and pickers; balance and history are kept. */
    val hidden: Boolean = false,
    /** Free-form note about the account (card PIN hints, branch, …). */
    val memo: String = "",
    /** Credit cards: day of the month the bill is paid. */
    val cardPaymentDay: Int? = null,
    /** Credit cards: the bill is paid automatically. */
    val cardAutoPay: Boolean = false,
    /** Credit cards: monthly spending target (minor units), e.g. to keep a card's benefits. */
    val usageTarget: Long? = null,
    /** 1 = target is checked against last month's spending, 2 = against the 3-month average. */
    val usageTargetType: Int = 1,
    /** Detected messages: from this sender (substring of the number / sender ID)… */
    val smsSender: String = "",
    /** …or containing this text (card or account digits) are assigned to this account. */
    val smsMatch: String = "",
)

data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val parentId: String?,
    val sortOrder: Int = 0,
    /** Account pre-selected when this category is picked; sub-categories fall back to their parent's. */
    val defaultAccountId: String? = null,
)

/**
 * Built-in category for balance changes ("Balance adjustment"). Not a stored row: adjustments
 * and income/expense entries made from "Adjust balance" carry this id.
 */
const val MODIFIED_BALANCE_CATEGORY = "cat_modified_balance"

/** Built-in card tags (ids match .mmbak backups, so imports keep them). */
const val TAG_PREPAYMENT = "system_prepayment"          // charge is due at once, not with the next statement
const val TAG_USAGE_EXCEPTION = "system_card_usage_exception"   // charge doesn't count toward the card's usage target
const val MODIFIED_BALANCE_NAME = "Balance adjustment"

/** The category's own default account, else its main category's. */
fun defaultAccountFor(categoryId: String?, byId: Map<String, Category>): String? {
    val c = categoryId?.let { byId[it] } ?: return null
    return c.defaultAccountId ?: c.parentId?.let { byId[it]?.defaultAccountId }
}

data class Txn(
    val id: String,
    val kind: TxnKind,
    val date: LocalDate,
    val createdAt: Long,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val amount: Long,
    val note: String = "",
    val memo: String = "",
    val transferGroupId: String? = null,
    val installmentGroupId: String? = null,
    val installmentLabel: String? = null,
    val recurringRuleId: String? = null,
    /** Currency the amount was entered in; null = main currency. */
    val currency: String? = null,
    /** Amount in [currency] (minor units of that currency); null = same as [amount]. */
    val originalAmount: Long? = null,
    /** Amount in the account's currency; null = same as [amount]. Used for balances. */
    val accountAmount: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** Minutes after midnight; null = no time recorded. */
    val timeOfDay: Int? = null,
    /** The SMS text this entry was made from. */
    val origin: String = "",
) {
    /** In main currency — used for reports. */
    val signedAmount: Long get() = amount * kind.sign
    /** In the account's own currency — used for balances. */
    val accountValue: Long get() = accountAmount ?: amount
    val signedAccountValue: Long get() = accountValue * kind.sign
    /** What the user typed, in the currency they typed it in. */
    val enteredAmount: Long get() = originalAmount ?: amount
    val isRefund: Boolean get() = isRefund(kind, amount)
}

data class RecurringRule(
    val id: String,
    val kind: TxnKind,
    val accountId: String,
    val toAccountId: String?,
    val categoryId: String?,
    val amount: Long,
    val note: String,
    val repeat: RepeatType,
    val startDate: LocalDate,
    val nextDate: LocalDate,
    val endDate: LocalDate?,
    val currency: String? = null,
    /** Description copied onto each generated entry. */
    val memo: String = "",
)

/** A non-main currency. [rate] = main-currency units per 1 unit (e.g. 1 USD = 83.2 INR → 83.2). */
data class Currency(val code: String, val symbol: String, val decimals: Int, val rate: Double, val sortOrder: Int = 0) {
    val format get() = CurrencyFormat(symbol, decimals)
}

data class Tag(val id: String, val name: String, val sortOrder: Int = 0)

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val color: Int = 0,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Photo(val id: String, val txnId: String, val fileName: String, val createdAt: Long)

enum class CaptureStatus { PENDING, SAVED, DISMISSED }

data class Capture(
    val id: String,
    val receivedAt: Long,
    val source: String,
    val sender: String,
    val text: String,
    val amount: Long?,
    val kind: TxnKind?,
    val merchant: String,
    val accountId: String?,
    val status: CaptureStatus,
    val txnId: String? = null,
)

/** Year-month value used for period labels and budget keys (yyyymm). */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    init { require(month in 1..12) }
    val key: Int get() = year * 100 + month
    fun plus(months: Int): YearMonth {
        val idx = year * 12 + (month - 1) + months
        return YearMonth(idx.floorDiv(12), idx.mod(12) + 1)
    }
    override fun compareTo(other: YearMonth) = key.compareTo(other.key)
    companion object {
        fun of(date: LocalDate) = YearMonth(date.year, date.monthNumber)
        fun fromKey(key: Int) = YearMonth(key / 100, key % 100)
    }
}
