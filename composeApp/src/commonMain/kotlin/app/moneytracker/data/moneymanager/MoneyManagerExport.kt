package app.moneytracker.data.moneymanager

import app.moneytracker.data.Prefs
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.isTransferFee
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

/** Write access to the SQLite file being built. Android implements it with SQLiteDatabase, JVM tests with JDBC. */
interface RowSink {
    fun exec(sql: String)
    fun insert(table: String, values: Map<String, Any?>)
}

/**
 * Writes a Money Manager (Android) `.mmbak` backup: the reverse of [MoneyManagerBackup.read], from the same [MoneyManagerBackup.Data].
 * Money Manager restores such a file by opening it as its database, so the layout is exactly [MoneyManagerSchema] and the
 * values use its conventions (amounts as text in major units, ids as uids, a refund as an expense with a negative amount).
 * Not carried: photos (not part of .mmbak), bookmarks, SMS history, the passcode.
 */
object MoneyManagerExport {
    fun write(sink: RowSink, d: MoneyManagerBackup.Data, tz: TimeZone, now: Long) {
        MoneyManagerSchema.statements.forEach(sink::exec)
        sink.exec("PRAGMA user_version = ${MoneyManagerSchema.VERSION}")
        sink.insert("android_metadata", mapOf("locale" to "en_US"))
        Writer(sink, d, tz, now).writeAll()
    }

    /** Writes every table's rows for one export; [now] is the modification time stamped on each row. */
    private class Writer(private val sink: RowSink, private val d: MoneyManagerBackup.Data, private val tz: TimeZone, private val now: Long) {
        private val main = d.mainCode
        private val decimalsOf = d.currencies.associate { it.code to it.decimals }
        private val synced = mapOf("isSynced" to 0)

        private fun insert(table: String, values: Map<String, Any?>) = sink.insert(table, synced + values)
        private fun currencyUid(code: String?) = "${main}_${code ?: main}"
        private fun dec(code: String?) = code?.let { decimalsOf[it] } ?: d.mainDecimals
        private fun real(minor: Long, decimals: Int) = minor / CurrencyFormat.pow10(decimals).toDouble()
        private fun text(minor: Long, decimals: Int) = real(minor, decimals).toString()
        // Dates are stored as epoch ms; noon keeps the day stable whatever the reader's time zone is.
        private fun noon(date: LocalDate) = LocalDateTime(date, LocalTime(12, 0)).toInstant(tz).toEpochMilliseconds()

        fun writeAll() {
            currencies()
            d.groups.forEach(::group)
            d.accounts.forEach(::account)
            d.categories.forEach(::category)
            txns()
            d.rules.forEach(::rule)
            budgets()
            d.tags.forEach { g -> insert("TAG", mapOf("uid" to g.id, "name" to g.name, "orderSeq" to g.sortOrder, "isDel" to 0, "utime" to now)) }
            d.txnTags.forEach { (txn, tag) ->
                insert("TX_TAG", mapOf("uid" to "${txn}_$tag", "txUid" to txn, "tagUid" to tag, "orderSeq" to 0, "isDel" to 0, "utime" to now))
            }
            d.memos.forEachIndexed(::memo)
            MoneyManagerSettings.rows(d.settings).forEachIndexed { i, (type, key, value) ->
                insert("ZETC", mapOf("uid" to "etc_${type}_$i", "E_UTIME" to now, "ZDATATYPE" to type, "ZDATA" to value, "dataTypeKey" to key, "isDel" to 0))
            }
        }

        /** The main currency, then the others ("INR_USD" = USD in an INR book). */
        private fun currencies() {
            insert("CURRENCY", mapOf(
                "uid" to currencyUid(null), "NAME" to main, "ISO" to main, "MAIN_ISO" to main, "IS_DEL" to 0, "ORDER_SEQ" to 100, "RATE" to 1.0,
                "SYMBOL" to d.mainSymbol, "INSERT_TYPE" to "I", "SYMBOL_POSITION" to if (d.settings[Prefs.SYMBOL_AFTER.key] == "1") "S" else "P",
                "IS_MAIN_CURRENCY" to 1, "IS_SHOW" to 1, "MODIFY_DATE" to now, "DECIMAL_POINT" to d.mainDecimals,
            ))
            d.currencies.forEachIndexed { i, c ->
                insert("CURRENCY", mapOf(
                    "uid" to currencyUid(c.code), "NAME" to c.code, "ISO" to c.code, "MAIN_ISO" to main, "IS_DEL" to 0, "ORDER_SEQ" to 101 + i,
                    "RATE" to c.rate, "SYMBOL" to c.symbol, "INSERT_TYPE" to "I", "SYMBOL_POSITION" to "P", "IS_MAIN_CURRENCY" to 0, "IS_SHOW" to 1,
                    "MODIFY_DATE" to now, "DECIMAL_POINT" to c.decimals,
                ))
            }
        }

        private fun group(g: MoneyManagerBackup.Group) = insert("ASSETGROUP", mapOf(
            "uid" to g.id, "IS_DEL" to if (g.deleted) 1 else 0, "USETIME" to now, "ACC_GROUP_NAME" to g.name,
            "TYPE" to groupCode(g.type), "ORDERSEQ" to g.sortOrder,
        ))

        private fun account(a: MoneyManagerBackup.Account) = insert("ASSETS", mapOf(
            "uid" to a.id, "groupUid" to a.groupId, "NIC_NAME" to a.name, "ORDERSEQ" to a.sortOrder,
            // 0 = normal, 1 = deleted, 3 = hidden; ZDATA2 1 = left out of totals.
            "ZDATA" to when { a.deleted -> "1"; a.hidden -> "3"; else -> "0" }, "ZDATA1" to a.memo, "ZDATA2" to if (a.includeInTotals) "0" else "1",
            "IS_TRANS_EXPENSE" to if (a.transferAsExpense) 1 else 0,
            "CARD_DAY_FIN" to (a.cardSettlementDay ?: 1).toString(), "CARD_DAY_PAY" to (a.cardPaymentDay ?: 1).toString(),
            "IS_CARD_AUTO_PAY" to if (a.cardAutoPay) 1 else 0, "cardAssetUid" to a.cardPaymentAccountId.orEmpty(),
            "CARD_USAGE_HURDLE_TYPE" to if (a.usageTarget == null) 0 else a.usageTargetType,
            "CARD_USAGE_HURDLE_AMOUNT" to real(a.usageTarget ?: 0, dec(a.currency)),
            "SMS_TEL" to a.smsSender, "SMS_STRING" to a.smsMatch, "A_UTIME" to now, "currencyUid" to currencyUid(a.currency),
        ))

        private fun category(c: MoneyManagerBackup.Category) = insert("ZCATEGORY", mapOf(
            "uid" to c.id, "NAME" to c.name, "TYPE" to if (c.income) 0 else 1, "ORDERSEQ" to c.sortOrder,
            // STATUS 2 = sub-category of pUid; active rows leave C_IS_DEL empty, as Money Manager does.
            "STATUS" to if (c.parentId != null) 2 else 0, "pUid" to (c.parentId ?: "0"),
            "C_IS_DEL" to if (c.deleted) 1 else null, "C_UTIME" to now, "syncVersion" to 0,
        ))

        /** Entries. A transfer's fee row and its out-leg share a txUidFee key. */
        private fun txns() {
            val feeKey = d.txns.filter { isTransferFee(it.kind, it.transferGroupId) }.mapNotNull { it.transferGroupId }.associateWith { "fee_$it" }
            val repeatOfRule = d.rules.associate { it.id to repeatCode(it.repeat) }
            val accountCurrency = d.accounts.associate { it.id to it.currency }
            d.txns.forEach { t ->
                val sharesFee = isTransferFee(t.kind, t.transferGroupId) || t.kind == TxnKind.TRANSFER_OUT
                insert("INOUTCOME", mapOf(
                    "uid" to t.id, "assetUid" to t.accountId, "toAssetUid" to t.toAccountId.orEmpty(),
                    "ctgUid" to when (t.categoryId) { null -> ""; MODIFIED_BALANCE_CATEGORY -> "-4"; else -> t.categoryId },
                    "ZCONTENT" to t.note, "ZDATA" to t.memo, "ZDATE" to entryTime(t).toString(), "WDATE" to t.date.toString(),
                    "DO_TYPE" to kindCode(t.kind).toString(),
                    "ZMONEY" to text(t.amountMain, d.mainDecimals),
                    "IN_ZMONEY" to text(t.originalAmount ?: t.amountMain, dec(t.currency)), "currencyUid" to currencyUid(t.currency),
                    "AMOUNT_ACCOUNT" to (t.accountAmount?.let { real(it, dec(accountCurrency[t.accountId])) } ?: real(t.amountMain, d.mainDecimals)),
                    "txUidTrans" to if (t.kind.isTransfer) t.transferGroupId.orEmpty() else "",
                    "txUidFee" to (if (sharesFee) feeKey[t.transferGroupId] else null).orEmpty(),
                    "cardDivideUid" to t.installmentGroupId.orEmpty(), "CARD_DIVIDE_MONTH_STR" to t.installmentLabel.orEmpty(),
                    // The repeat type of the rule that made the entry; Money Manager shows it as "(Every Month)".
                    "CARDDIVIDMONTH" to (t.recurringRuleId?.let { repeatOfRule[it] } ?: 0).toString(),
                    "SMS_ORIGIN" to t.origin, "MARK" to 0, "IS_DEL" to 0, "UTIME" to now,
                    "lat" to t.lat?.toString(), "lng" to t.lng?.toString(), "ASSET_NIC" to "", "CATEGORY_NAME" to "",
                ))
            }
        }

        /** The time of day lives in ZDATE; without one, when the entry was made. */
        private fun entryTime(t: MoneyManagerBackup.Txn): Long =
            t.timeOfDay?.let { LocalDateTime(t.date, LocalTime(it / 60, it % 60)).toInstant(tz).toEpochMilliseconds() }
                ?: t.createdAt.takeIf { it > 0 } ?: t.date.atStartOfDayIn(tz).toEpochMilliseconds()

        private fun rule(r: MoneyManagerBackup.Rule) = insert("REPEATTRANSACTION", mapOf(
            "uid" to r.id, "IS_DEL" to 0, "USETIME" to now, "MARK" to 0, "DO_TYPE" to kindCode(r.kind), "REPEAT_TYPE" to repeatCode(r.repeat),
            "NEXT_DATE" to noon(r.nextDate), "END_DATE" to (r.endDate?.let(::noon) ?: 0L),
            "assetUid" to r.accountId, "toAssetUid" to r.toAccountId.orEmpty(), "ctgUid" to r.categoryId.orEmpty(),
            "currencyUid" to currencyUid(r.currency), "AMOUNT_SUB" to real(r.amount, dec(r.currency)), "PAYEE" to r.note, "MEMO" to r.memo,
        ))

        /** One BUDGET per target ('' = total), one BUDGET_AMOUNT per month it changes. */
        private fun budgets() {
            d.budgets.groupBy { it.categoryId }.entries.forEachIndexed { i, (target, amounts) ->
                val total = target.isEmpty()
                val budgetUid = "${target}_1_${if (total) 1 else 0}_0_6"
                insert("BUDGET", mapOf(
                    "uid" to budgetUid, "targetUid" to target, "DO_TYPE" to 1, "PERIOD_TYPE" to 6, "IS_TOTAL" to if (total) 1 else 0,
                    "IS_DEL" to 0, "TRANSFER_TYPE" to 0, "ORDER_SEQ" to 100 + i, "MODIFY_DATE" to now,
                ))
                amounts.forEach { b ->
                    insert("BUDGET_AMOUNT", mapOf(
                        "uid" to "${budgetUid}_${b.period}", "budgetUid" to budgetUid, "IS_DEL" to 0, "AMOUNT" to real(b.amount, d.mainDecimals),
                        "BUDGET_PERIOD" to b.period, "MODIFY_DATE" to now,
                    ))
                }
            }
        }

        private fun memo(i: Int, m: MoneyManagerBackup.Memo) = insert("MEMO", mapOf(
            "uid" to m.id, "title" to m.title, "content" to m.content, "memoTime" to m.time, "pinned" to if (m.pinned) 1 else 0,
            "pinnedTime" to if (m.pinned) m.time else 0, "orderSeq" to i, "color" to 0, "isDel" to 0, "utime" to now,
        ))
    }

    private fun kindCode(k: TxnKind) = when (k) {
        TxnKind.INCOME -> 0; TxnKind.EXPENSE -> 1; TxnKind.TRANSFER_OUT -> 3; TxnKind.TRANSFER_IN -> 4
        TxnKind.ADJUST_UP -> 7; TxnKind.ADJUST_DOWN -> 8
    }

    private fun groupCode(t: AccountType) = when (t) {
        AccountType.BANK -> 1; AccountType.CREDIT_CARD -> 2; AccountType.DEBIT_CARD -> 3; AccountType.SAVINGS -> 4; AccountType.LOAN -> 5
        AccountType.PREPAID -> 6; AccountType.INVESTMENT -> 8; AccountType.OVERDRAFT -> 9; AccountType.INSURANCE -> 10; AccountType.CASH -> 11
        AccountType.OTHER -> 0
    }

    private fun repeatCode(r: RepeatType) = when (r) {
        RepeatType.MONTHLY -> 1; RepeatType.BIMONTHLY -> 2; RepeatType.QUARTERLY -> 3; RepeatType.FOUR_MONTHLY -> 4; RepeatType.HALF_YEARLY -> 16
        RepeatType.YEARLY -> 17; RepeatType.DAILY -> 5; RepeatType.WEEKLY -> 6; RepeatType.BIWEEKLY -> 7; RepeatType.FOUR_WEEKLY -> 15
        RepeatType.END_OF_MONTH -> 8; RepeatType.WEEKDAYS -> 9; RepeatType.WEEKENDS -> 10
    }
}
