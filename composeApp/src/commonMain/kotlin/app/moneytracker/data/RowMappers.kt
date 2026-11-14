package app.moneytracker.data

import app.moneytracker.db.Account as AccountRow
import app.moneytracker.db.AccountGroup as GroupRow
import app.moneytracker.db.Capture as CaptureRow
import app.moneytracker.db.Category as CategoryRow
import app.moneytracker.db.Currency as CurrencyRow
import app.moneytracker.db.RecurringRule as RuleRow
import app.moneytracker.db.Txn as TxnRow
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Currency
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import kotlinx.datetime.LocalDate

// Stored rows → domain objects, shared by the stores that read the same tables.

internal fun Boolean.toLong() = if (this) 1L else 0L

internal fun GroupRow.toDomain() = AccountGroup(id, name, AccountType.valueOf(type), sortOrder.toInt())
internal fun AccountRow.toDomain() = Account(id, groupId, name, includeInTotals != 0L, transferAsExpense != 0L,
    cardSettlementDay?.toInt(), cardPaymentAccountId, sortOrder.toInt(), currency, cardLimit, hidden != 0L,
    memo, cardPaymentDay?.toInt(), cardAutoPay != 0L, usageTarget, usageTargetType.toInt(), smsSender, smsMatch)
internal fun CategoryRow.toDomain() = Category(id, name, CategoryKind.valueOf(kind), parentId, sortOrder.toInt(), defaultAccountId)
internal fun TxnRow.toDomain() = Txn(id, TxnKind.valueOf(kind), LocalDate.fromEpochDays(date.toInt()), createdAt, accountId,
    toAccountId, categoryId, amount, note, memo, transferGroupId, installmentGroupId, installmentLabel, recurringRuleId,
    currency, originalAmount, accountAmount, lat, lng, timeOfDay?.toInt(), origin, rate)
internal fun RuleRow.toDomain() = RecurringRule(id, TxnKind.valueOf(kind), accountId, toAccountId, categoryId, amount, note,
    RepeatType.valueOf(repeat), LocalDate.fromEpochDays(startDate.toInt()), LocalDate.fromEpochDays(nextDate.toInt()),
    endDate?.let { LocalDate.fromEpochDays(it.toInt()) }, currency, memo)
internal fun CurrencyRow.toDomain() = Currency(code, symbol, decimals.toInt(), rate, sortOrder.toInt())
internal fun CaptureRow.toDomain() = Capture(id, receivedAt, source, sender, text, amount, kind?.let { TxnKind.valueOf(it) }, merchant,
    accountId, CaptureStatus.valueOf(status), txnId)
