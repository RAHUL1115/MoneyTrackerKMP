package app.moneytracker.data

import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
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

        val main = d.mainCode
        val decimalsOf = d.currencies.associate { it.code to it.decimals }
        fun currencyUid(code: String?) = "${main}_${code ?: main}"
        fun dec(code: String?) = code?.let { decimalsOf[it] } ?: d.mainDecimals
        fun real(minor: Long, decimals: Int) = minor / CurrencyFormat.pow10(decimals).toDouble()
        fun text(minor: Long, decimals: Int) = real(minor, decimals).toString()
        // Dates are stored as epoch ms; noon keeps the day stable whatever the reader's time zone is.
        fun noon(date: LocalDate) = LocalDateTime(date, LocalTime(12, 0)).toInstant(tz).toEpochMilliseconds()
        val synced = mapOf("isSynced" to 0)

        // Currencies: the main one, then the others ("INR_USD" = USD in an INR book).
        sink.insert("CURRENCY", synced + mapOf(
            "uid" to currencyUid(null), "NAME" to main, "ISO" to main, "MAIN_ISO" to main, "IS_DEL" to 0, "ORDER_SEQ" to 100, "RATE" to 1.0,
            "SYMBOL" to d.mainSymbol, "INSERT_TYPE" to "I", "SYMBOL_POSITION" to if (d.settings[Keys.SYMBOL_AFTER] == "1") "S" else "P",
            "IS_MAIN_CURRENCY" to 1, "IS_SHOW" to 1, "MODIFY_DATE" to now, "DECIMAL_POINT" to d.mainDecimals,
        ))
        d.currencies.forEachIndexed { i, c ->
            sink.insert("CURRENCY", synced + mapOf(
                "uid" to currencyUid(c.code), "NAME" to c.code, "ISO" to c.code, "MAIN_ISO" to main, "IS_DEL" to 0, "ORDER_SEQ" to 101 + i,
                "RATE" to c.rate, "SYMBOL" to c.symbol, "INSERT_TYPE" to "I", "SYMBOL_POSITION" to "P", "IS_MAIN_CURRENCY" to 0, "IS_SHOW" to 1,
                "MODIFY_DATE" to now, "DECIMAL_POINT" to c.decimals,
            ))
        }

        d.groups.forEach { g ->
            sink.insert("ASSETGROUP", synced + mapOf(
                "uid" to g.id, "IS_DEL" to if (g.deleted) 1 else 0, "USETIME" to now, "ACC_GROUP_NAME" to g.name,
                "TYPE" to groupCode(g.type), "ORDERSEQ" to g.sortOrder,
            ))
        }
        d.accounts.forEach { a ->
            sink.insert("ASSETS", synced + mapOf(
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
        }
        d.categories.forEach { c ->
            sink.insert("ZCATEGORY", synced + mapOf(
                "uid" to c.id, "NAME" to c.name, "TYPE" to if (c.income) 0 else 1, "ORDERSEQ" to c.sortOrder,
                // STATUS 2 = sub-category of pUid; active rows leave C_IS_DEL empty, as Money Manager does.
                "STATUS" to if (c.parentId != null) 2 else 0, "pUid" to (c.parentId ?: "0"),
                "C_IS_DEL" to if (c.deleted) 1 else null, "C_UTIME" to now, "syncVersion" to 0,
            ))
        }

        // Entries. A transfer's fee row and its out-leg share a txUidFee key.
        val feeKey = d.txns.filter { it.kind == TxnKind.EXPENSE && it.transferGroupId != null }.associate { it.transferGroupId!! to "fee_${it.transferGroupId}" }
        val repeatOfRule = d.rules.associate { it.id to repeatCode(it.repeat) }
        val accountCurrency = d.accounts.associate { it.id to it.currency }
        d.txns.forEach { t ->
            val isFee = t.kind == TxnKind.EXPENSE && t.transferGroupId != null
            // The time of day lives in ZDATE; without one, when the entry was made.
            val at = t.timeOfDay?.let { LocalDateTime(t.date, LocalTime(it / 60, it % 60)).toInstant(tz).toEpochMilliseconds() }
                ?: t.createdAt.takeIf { it > 0 } ?: t.date.atStartOfDayIn(tz).toEpochMilliseconds()
            sink.insert("INOUTCOME", synced + mapOf(
                "uid" to t.id, "assetUid" to t.accountId, "toAssetUid" to t.toAccountId.orEmpty(),
                "ctgUid" to when (t.categoryId) { null -> ""; MODIFIED_BALANCE_CATEGORY -> "-4"; else -> t.categoryId },
                "ZCONTENT" to t.note, "ZDATA" to t.memo, "ZDATE" to at.toString(), "WDATE" to t.date.toString(),
                "DO_TYPE" to kindCode(t.kind).toString(),
                "ZMONEY" to text(t.amountMain, d.mainDecimals),
                "IN_ZMONEY" to text(t.originalAmount ?: t.amountMain, dec(t.currency)), "currencyUid" to currencyUid(t.currency),
                "AMOUNT_ACCOUNT" to (t.accountAmount?.let { real(it, dec(accountCurrency[t.accountId])) }
                    ?: real(t.amountMain, d.mainDecimals)),
                "txUidTrans" to if (t.kind.isTransfer) t.transferGroupId.orEmpty() else "",
                "txUidFee" to when {
                    isFee -> feeKey[t.transferGroupId]
                    t.kind == TxnKind.TRANSFER_OUT -> feeKey[t.transferGroupId]
                    else -> null
                }.orEmpty(),
                "cardDivideUid" to t.installmentGroupId.orEmpty(), "CARD_DIVIDE_MONTH_STR" to t.installmentLabel.orEmpty(),
                // The repeat type of the rule that made the entry; Money Manager shows it as "(Every Month)".
                "CARDDIVIDMONTH" to (t.recurringRuleId?.let { repeatOfRule[it] } ?: 0).toString(),
                "SMS_ORIGIN" to t.origin, "MARK" to 0, "IS_DEL" to 0, "UTIME" to now,
                "lat" to t.lat?.toString(), "lng" to t.lng?.toString(), "ASSET_NIC" to "", "CATEGORY_NAME" to "",
            ))
        }

        d.rules.forEach { r ->
            sink.insert("REPEATTRANSACTION", synced + mapOf(
                "uid" to r.id, "IS_DEL" to 0, "USETIME" to now, "MARK" to 0, "DO_TYPE" to kindCode(r.kind), "REPEAT_TYPE" to repeatCode(r.repeat),
                "NEXT_DATE" to noon(r.nextDate), "END_DATE" to (r.endDate?.let(::noon) ?: 0L),
                "assetUid" to r.accountId, "toAssetUid" to r.toAccountId.orEmpty(), "ctgUid" to r.categoryId.orEmpty(),
                "currencyUid" to currencyUid(r.currency), "AMOUNT_SUB" to real(r.amount, dec(r.currency)), "PAYEE" to r.note, "MEMO" to r.memo,
            ))
        }

        // Budgets: one BUDGET per target ('' = total), one BUDGET_AMOUNT per month it changes.
        d.budgets.groupBy { it.categoryId }.entries.forEachIndexed { i, (target, amounts) ->
            val total = target.isEmpty()
            val budgetUid = "${target}_1_${if (total) 1 else 0}_0_6"
            sink.insert("BUDGET", synced + mapOf(
                "uid" to budgetUid, "targetUid" to target, "DO_TYPE" to 1, "PERIOD_TYPE" to 6, "IS_TOTAL" to if (total) 1 else 0,
                "IS_DEL" to 0, "TRANSFER_TYPE" to 0, "ORDER_SEQ" to 100 + i, "MODIFY_DATE" to now,
            ))
            amounts.forEach { b ->
                sink.insert("BUDGET_AMOUNT", synced + mapOf(
                    "uid" to "${budgetUid}_${b.period}", "budgetUid" to budgetUid, "IS_DEL" to 0, "AMOUNT" to real(b.amount, d.mainDecimals),
                    "BUDGET_PERIOD" to b.period, "MODIFY_DATE" to now,
                ))
            }
        }

        d.tags.forEach { g -> sink.insert("TAG", synced + mapOf("uid" to g.id, "name" to g.name, "orderSeq" to g.sortOrder, "isDel" to 0, "utime" to now)) }
        d.txnTags.forEach { (txn, tag) ->
            sink.insert("TX_TAG", synced + mapOf("uid" to "${txn}_$tag", "txUid" to txn, "tagUid" to tag, "orderSeq" to 0, "isDel" to 0, "utime" to now))
        }
        d.memos.forEachIndexed { i, m ->
            sink.insert("MEMO", synced + mapOf(
                "uid" to m.id, "title" to m.title, "content" to m.content, "memoTime" to m.time, "pinned" to if (m.pinned) 1 else 0,
                "pinnedTime" to if (m.pinned) m.time else 0, "orderSeq" to i, "color" to 0, "isDel" to 0, "utime" to now,
            ))
        }

        etcRows(d.settings).forEachIndexed { i, (type, key, value) ->
            sink.insert("ZETC", synced + mapOf("uid" to "etc_${type}_$i", "E_UTIME" to now, "ZDATATYPE" to type, "ZDATA" to value,
                "dataTypeKey" to key, "isDel" to 0))
        }
    }

    /** Our settings as Money Manager ZETC rows (type, key, value); the inverse of what [MoneyManagerBackup.read] carries over. */
    private fun etcRows(s: Map<String, String>): List<Triple<Int, String, String>> = buildList {
        fun add(type: Int, key: String, value: String?) { if (value != null) add(Triple(type, key, value)) }
        fun flag(setting: String, default: String) = when (s[setting]) { "1" -> "1"; "0" -> "0"; else -> default }
        add(-23459, "start_day", s[Keys.MONTH_START_DAY] ?: "1")
        // Written even when unset: without it Money Manager falls back to swapped income/expense colours.
        add(-6179, "money_color", if (s[Keys.COLOR_SET] == "B") "1" else "0")
        add(-5457, "carry_over", flag(Keys.CARRY_OVER, "0"))
        add(-30009, "card_accounting_type", flag(Keys.CARD_AT_PAYMENT, "0"))
        add(19810, "use_sub_category", flag(Keys.USE_SUBCATEGORIES, "0"))
        add(10002, "week_start_day", when (s[Keys.WEEK_START]) { "SUNDAY" -> "0"; "MONDAY" -> "1"; "SATURDAY" -> "6"; else -> null })
        add(10008, "month_start_weekend_type", when (s[Keys.WEEKEND_SHIFT]) { "BEFORE" -> "1"; "AFTER" -> "2"; else -> "0" })
        add(10003, "main_tab_first_view", if (s[Keys.START_SCREEN] == "CALENDAR") "1" else "0")
        add(10014, "main_tab_scroll_type", when (s[Keys.SWIPE]) { "TAB" -> "0"; "DATE" -> "1"; "NONE" -> "2"; else -> null })
        val asc = s[Keys.TIME_SORT] == "ASC"
        add(10004, "time_display_order", when (s[Keys.TIME_INPUT]) {
            "LIST" -> if (asc) "3" else "1"; "INPUT" -> if (asc) "4" else "2"; "NONE" -> if (asc) "0" else "5"; else -> null
        })
        add(10011, "show_tx_memo_on_day_list", s[Keys.SHOW_DESCRIPTION]?.takeIf { it == "0" || it == "1" })
        add(23274, "use_auto_complete", s[Keys.AUTOCOMPLETE]?.takeIf { it == "0" || it == "1" })
        add(10016, "memo_btn_on_daily", s[Keys.NOTE_BUTTON]?.takeIf { it == "0" || it == "1" })
        add(10017, "input_form_order", when (s[Keys.INPUT_ORDER]) { "AMOUNT" -> "1"; "ACCOUNT" -> "2"; else -> null })
        add(10009, "repeat_reflect_type", when (s[Keys.REPEAT_TIMING]) { "ON_DATE" -> "0"; "MONTH_START" -> "1"; else -> null })
        add(10006, "sms_auto_save_to_tx", s[Keys.SMS_AUTO_SAVE]?.takeIf { it == "0" || it == "1" })
        add(1078, "sms_show_noti", s[Keys.SMS_ALERT]?.takeIf { it == "0" || it == "1" })
        add(-12649, "sms_show_org_on_tx", s[Keys.SMS_SHOW_ORIGINAL]?.takeIf { it in setOf("0", "1", "2") })
        add(1077, "sms_search_day", s[Keys.SMS_IMPORT_DAYS])
        add(10000, "use_quick_add_noti", flag(Keys.QUICK_ADD_NOTIFICATION, "0"))
        // List settings: one row per line.
        fun lines(setting: String) = s[setting].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines(Keys.SMS_BLOCKED_SENDERS).forEach { add(4838, "sms_reject_call_number", it) }
        lines(Keys.SMS_BLOCKED_PHRASES).forEach { add(4839, "sms_reject_str", it) }
        lines(Keys.SMS_CONTENT_FILTER).forEach { add(100, "sms_content_auto_del", it) }
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
