package app.moneytracker.data

import app.moneytracker.domain.AccountType
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Read-only access to another app's SQLite file: table names and rows as column→text maps.
 * Android implements it with SQLiteDatabase, JVM tests with JDBC.
 */
interface RowSource {
    fun tables(): Set<String>
    fun rows(table: String): List<Map<String, String?>>
}

/**
 * Converts a Money Manager (Realbyte, Android, schema v19 and close relatives) `.mmbak` backup into
 * this app's model. Only the backup's own data is used; column lookups are tolerant of missing columns
 * so slightly older backups still load.
 */
object MoneyManagerBackup {
    /** Android Money Manager backups have these tables; iOS ones use Core Data names (ZASSET…). */
    fun isMoneyManager(tables: Set<String>) = tables.containsAll(listOf("INOUTCOME", "ASSETS", "ZCATEGORY"))
    fun isIosMoneyManager(tables: Set<String>) = "ZASSET" in tables && "ZINOUTCOME" in tables

    data class Group(val id: String, val name: String, val type: AccountType, val sortOrder: Int, val deleted: Boolean)
    data class Account(
        val id: String, val groupId: String, val name: String, val includeInTotals: Boolean, val transferAsExpense: Boolean,
        val cardSettlementDay: Int?, val cardPaymentAccountId: String?, val sortOrder: Int, val currency: String?, val deleted: Boolean,
        val hidden: Boolean = false, val memo: String = "", val cardPaymentDay: Int? = null, val cardAutoPay: Boolean = false,
        val usageTarget: Long? = null, val usageTargetType: Int = 1,
        val smsSender: String = "", val smsMatch: String = "",
    )
    data class Category(val id: String, val name: String, val income: Boolean, val parentId: String?, val sortOrder: Int, val deleted: Boolean)
    data class Txn(
        val id: String, val kind: TxnKind, val date: LocalDate, val createdAt: Long, val accountId: String, val toAccountId: String?,
        val categoryId: String?, val amountMain: Long, val currency: String?, val originalAmount: Long?, val accountAmount: Long?,
        val note: String, val memo: String, val transferGroupId: String?, val installmentGroupId: String?, val installmentLabel: String?,
        val lat: Double?, val lng: Double?, val timeOfDay: Int? = null, val origin: String = "",
        /** The repeating rule that created the entry (Money Manager names such entries "<rule uid>_…"). */
        val recurringRuleId: String? = null,
    )
    data class Rule(
        val id: String, val kind: TxnKind, val accountId: String, val toAccountId: String?, val categoryId: String?, val amount: Long,
        val note: String, val repeat: RepeatType, val nextDate: LocalDate, val endDate: LocalDate?, val currency: String?,
        val memo: String = "",
    )
    data class Budget(val categoryId: String, val period: Int, val amount: Long)
    data class Currency(val code: String, val symbol: String, val decimals: Int, val rate: Double)
    data class Tag(val id: String, val name: String, val sortOrder: Int)
    data class Memo(val id: String, val title: String, val content: String, val pinned: Boolean, val time: Long)

    data class Data(
        val mainCode: String, val mainSymbol: String, val mainDecimals: Int,
        val currencies: List<Currency>, val groups: List<Group>, val accounts: List<Account>, val categories: List<Category>,
        val txns: List<Txn>, val rules: List<Rule>, val budgets: List<Budget>, val tags: List<Tag>, val txnTags: List<Pair<String, String>>,
        val memos: List<Memo>, val settings: Map<String, String>, val skipped: List<String>,
        /** Money Manager's 4-digit passcode (stored there in plain text); the app hashes it before saving. */
        val passcodePin: String? = null,
    )

    // ───────────────────────── reading

    private fun Map<String, String?>.s(col: String): String? = this[col] ?: entries.firstOrNull { it.key.equals(col, true) }?.value
    private fun Map<String, String?>.str(col: String) = s(col)?.trim().orEmpty()
    private fun Map<String, String?>.int(col: String) = s(col)?.trim()?.toDoubleOrNull()?.toInt()
    private fun Map<String, String?>.isDeleted(col: String) = int(col) == 1
    private fun idOrNull(v: String?) = v?.trim()?.takeUnless { it.isEmpty() || it == "0" || it == "-1" || it == "-2" || it == "-4" }

    /** [signed] keeps a minus: Money Manager writes a refund as an expense with a negative amount, and so do we. */
    private fun minor(text: String?, decimals: Int, signed: Boolean = false): Long? =
        text?.trim()?.replace(",", "")?.toDoubleOrNull()?.let { kotlin.math.round((if (signed) it else kotlin.math.abs(it)) * CurrencyFormat.pow10(decimals)).toLong() }

    private fun dayOf(millis: Long, tz: TimeZone) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(tz).date

    /** Money Manager group TYPE → our account type. 0 = user-made group. */
    private fun groupType(t: Int?) = when (t) {
        1 -> AccountType.BANK; 2 -> AccountType.CREDIT_CARD; 3 -> AccountType.DEBIT_CARD; 4 -> AccountType.SAVINGS
        5 -> AccountType.LOAN; 6 -> AccountType.PREPAID; 8 -> AccountType.INVESTMENT; 9 -> AccountType.OVERDRAFT
        10 -> AccountType.INSURANCE; 11 -> AccountType.CASH
        else -> AccountType.OTHER
    }

    /** Money Manager REPEAT_TYPE → ours (null = no equivalent, e.g. every 4 months). */
    private fun repeatType(t: Int?) = when (t) {
        1 -> RepeatType.MONTHLY; 2 -> RepeatType.BIMONTHLY; 3 -> RepeatType.QUARTERLY; 4 -> RepeatType.FOUR_MONTHLY; 16 -> RepeatType.HALF_YEARLY
        17 -> RepeatType.YEARLY; 5 -> RepeatType.DAILY; 6 -> RepeatType.WEEKLY; 7 -> RepeatType.BIWEEKLY
        15 -> RepeatType.FOUR_WEEKLY; 8 -> RepeatType.END_OF_MONTH; 9 -> RepeatType.WEEKDAYS; 10 -> RepeatType.WEEKENDS
        else -> null
    }

    private fun kind(doType: Int?) = when (doType) {
        0 -> TxnKind.INCOME; 1 -> TxnKind.EXPENSE; 3 -> TxnKind.TRANSFER_OUT; 4 -> TxnKind.TRANSFER_IN
        7 -> TxnKind.ADJUST_UP; 8 -> TxnKind.ADJUST_DOWN
        else -> null
    }

    fun read(src: RowSource, tz: TimeZone = TimeZone.currentSystemDefault()): Data {
        val tables = src.tables()
        fun rows(t: String) = if (t in tables) src.rows(t) else emptyList()
        val skipped = mutableListOf<String>()

        // Currencies: "INR_USD" style uids = <main ISO>_<ISO>.
        val curRows = rows("CURRENCY").filterNot { it.isDeleted("IS_DEL") }
        val main = curRows.firstOrNull { it.int("IS_MAIN_CURRENCY") == 1 }
        val mainCode = main?.str("ISO")?.ifEmpty { null } ?: "INR"
        val mainDecimals = main?.int("DECIMAL_POINT") ?: 2
        val mainSymbol = main?.str("SYMBOL")?.ifEmpty { null } ?: "₹"
        val currencies = curRows.filter { it.int("IS_MAIN_CURRENCY") != 1 && it.str("ISO").isNotEmpty() && it.str("ISO") != mainCode }
            .map { Currency(it.str("ISO"), it.str("SYMBOL").ifEmpty { it.str("ISO") }, it.int("DECIMAL_POINT") ?: 2, it.s("RATE")?.toDoubleOrNull() ?: 1.0) }
            .distinctBy { it.code }
        val decimalsOf = currencies.associate { it.code to it.decimals }
        fun codeOf(currencyUid: String?): String? = currencyUid?.substringAfter('_', currencyUid)?.trim()
            ?.takeUnless { it.isEmpty() || it == mainCode || it !in decimalsOf }
        fun decimals(code: String?) = code?.let { decimalsOf[it] } ?: mainDecimals

        // Accounts: ZDATA '0' = normal; '1' = deleted (trash), '2' = purged → kept as deleted so history keeps names; '3' = hidden.
        val accountRows = rows("ASSETS")
        val accounts = accountRows.map { a ->
            val status = a.str("ZDATA")
            Account(
                id = a.str("uid"), groupId = a.str("groupUid"), name = a.str("NIC_NAME").ifEmpty { "Account" },
                includeInTotals = a.str("ZDATA2") != "1", transferAsExpense = a.int("IS_TRANS_EXPENSE") == 1,
                cardSettlementDay = a.int("CARD_DAY_FIN")?.takeIf { it in 1..31 }, cardPaymentAccountId = idOrNull(a.s("cardAssetUid")),
                sortOrder = a.int("ORDERSEQ") ?: 0, currency = codeOf(a.s("currencyUid")),
                deleted = status == "1" || status == "2", hidden = status == "3",
                memo = a.str("ZDATA1").trim(), cardPaymentDay = a.int("CARD_DAY_PAY")?.takeIf { it in 1..31 },
                cardAutoPay = a.int("IS_CARD_AUTO_PAY") == 1,
                usageTarget = minor(a.s("CARD_USAGE_HURDLE_AMOUNT"), decimals(codeOf(a.s("currencyUid"))))?.takeIf { it > 0 },
                usageTargetType = if (a.int("CARD_USAGE_HURDLE_TYPE") == 2) 2 else 1,
                smsSender = a.str("SMS_TEL").trim(), smsMatch = a.str("SMS_STRING").trim(),
            )
        }.filter { it.id.isNotEmpty() }
        val accountById = accounts.associateBy { it.id }

        // Groups: keep any group that holds accounts, even if the group itself was deleted.
        val usedGroups = accounts.map { it.groupId }.toSet()
        val groups = rows("ASSETGROUP").map { g ->
            Group(g.str("uid"), g.str("ACC_GROUP_NAME").ifEmpty { "Accounts" }, groupType(g.int("TYPE")), g.int("ORDERSEQ") ?: 0,
                deleted = g.isDeleted("IS_DEL") && g.str("uid") !in usedGroups)
        }.filter { it.id.isNotEmpty() }
        // Card settlement day only matters for credit cards.
        val groupTypeById = groups.associate { it.id to it.type }
        val fixedAccounts = accounts.map { if (groupTypeById[it.groupId] == AccountType.CREDIT_CARD) it else it.copy(cardSettlementDay = null, cardPaymentDay = null, cardAutoPay = false, usageTarget = null) }

        // Transactions.
        val txRows = rows("INOUTCOME").filterNot { it.isDeleted("IS_DEL") }
        val feeToTransfer = txRows.filter { it.int("DO_TYPE") == 3 && idOrNull(it.s("txUidFee")) != null && idOrNull(it.s("txUidTrans")) != null }
            .associate { it.str("txUidFee") to it.str("txUidTrans") }
        val categoryRows = rows("ZCATEGORY")
        val categoryIds = categoryRows.map { it.str("uid") }.toSet()
        val ruleUids = rows("REPEATTRANSACTION").filterNot { it.isDeleted("IS_DEL") }.map { it.str("uid") }.filter { it.isNotEmpty() }
        val txns = txRows.mapNotNull { t ->
            val k = kind(t.int("DO_TYPE")) ?: run { skipped += "transaction ${t.str("uid")}: unknown type ${t.str("DO_TYPE")}"; return@mapNotNull null }
            val account = t.str("assetUid")
            if (account !in accountById) { skipped += "transaction ${t.str("uid")}: unknown account"; return@mapNotNull null }
            val date = runCatching { LocalDate.parse(t.str("WDATE").take(10)) }.getOrNull()
                ?: t.s("ZDATE")?.toLongOrNull()?.let { dayOf(it, tz) }
                ?: run { skipped += "transaction ${t.str("uid")}: no date"; return@mapNotNull null }
            val code = codeOf(t.s("currencyUid"))
            val accountCode = accountById[account]?.currency
            val signed = k == TxnKind.EXPENSE
            val amountMain = minor(t.s("ZMONEY"), mainDecimals, signed) ?: run { skipped += "transaction ${t.str("uid")}: no amount"; return@mapNotNull null }
            val transferId = idOrNull(t.s("txUidTrans"))
            val feeGroup = if (k == TxnKind.EXPENSE) idOrNull(t.s("txUidFee"))?.let { feeToTransfer[it] } else null
            val installment = idOrNull(t.s("cardDivideUid"))
            Txn(
                id = t.str("uid").ifEmpty { newId() }, kind = k, date = date,
                createdAt = t.s("ZDATE")?.toLongOrNull() ?: t.s("UTIME")?.toLongOrNull() ?: 0L,
                accountId = account,
                toAccountId = if (k.isTransfer) idOrNull(t.s("toAssetUid"))?.takeIf { it in accountById } else null,
                // "-4" is Money Manager's built-in "Modified Bal." (balance changes, with or without income/expense).
                categoryId = when {
                    t.s("ctgUid")?.trim() == "-4" && !k.isTransfer -> app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
                    k == TxnKind.INCOME || k == TxnKind.EXPENSE -> idOrNull(t.s("ctgUid"))?.takeIf { it in categoryIds }
                    else -> null
                },
                amountMain = amountMain,
                currency = code,
                originalAmount = code?.let { minor(t.s("IN_ZMONEY"), decimals(it), signed) },
                accountAmount = accountCode?.let { minor(t.s("AMOUNT_ACCOUNT"), decimals(it), signed) },
                note = t.str("ZCONTENT"), memo = t.str("ZDATA"),
                transferGroupId = if (k.isTransfer) transferId else feeGroup,
                installmentGroupId = installment, installmentLabel = installment?.let { t.str("CARD_DIVIDE_MONTH_STR").ifEmpty { null } },
                lat = t.s("lat")?.toDoubleOrNull()?.takeIf { it != 0.0 }, lng = t.s("lng")?.toDoubleOrNull()?.takeIf { it != 0.0 },
                // Time of day: from WDATE when it carries one ("yyyy-MM-dd HH:mm"), else from ZDATE (epoch ms) on the same day.
                timeOfDay = t.str("WDATE").takeIf { it.length >= 16 && it[13] == ':' }
                    ?.let { w -> w.substring(11, 13).toIntOrNull()?.let { h -> w.substring(14, 16).toIntOrNull()?.let { m -> h * 60 + m } } }
                    ?: t.s("ZDATE")?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) }
                        ?.takeIf { it.date == date }?.let { it.hour * 60 + it.minute },
                origin = t.str("SMS_ORIGIN").trim(),
                recurringRuleId = t.str("uid").let { uid -> ruleUids.firstOrNull { uid.startsWith(it + "_") } },
            )
        }
        // A transfer needs both legs; a lone leg would double-count, so keep it as plain in/out.
        val legs = txns.filter { it.kind.isTransfer }.groupBy { it.transferGroupId }
        val cleanTxns = txns.map { t ->
            if (t.kind.isTransfer && (t.transferGroupId == null || legs[t.transferGroupId]?.size != 2 || t.toAccountId == null)) {
                t.copy(kind = if (t.kind == TxnKind.TRANSFER_OUT) TxnKind.EXPENSE else TxnKind.INCOME, toAccountId = null, transferGroupId = null)
            } else t
        }

        // Categories: deleted ones stay deleted (out of the pickers) but are kept, so past entries still show their names.
        val parentOf = categoryRows.associate { it.str("uid") to (if (it.int("STATUS") == 2) idOrNull(it.s("pUid")) else null) }
        val categories = categoryRows.map { c ->
            val id = c.str("uid")
            Category(id, c.str("NAME").ifEmpty { "Category" }, c.int("TYPE") == 0, parentOf[id]?.takeIf { it in categoryIds },
                c.int("ORDERSEQ") ?: 0, deleted = c.isDeleted("C_IS_DEL"))
        }.filter { it.id.isNotEmpty() }
        val catIds = categories.filterNot { it.deleted }.map { it.id }.toSet()
        val allCatIds = categories.map { it.id }.toSet()

        // Recurring rules.
        val rules = rows("REPEATTRANSACTION").filterNot { it.isDeleted("IS_DEL") }.mapNotNull { r ->
            val k = kind(r.int("DO_TYPE"))?.takeIf { it == TxnKind.INCOME || it == TxnKind.EXPENSE || it == TxnKind.TRANSFER_OUT }
            val type = repeatType(r.int("REPEAT_TYPE"))
            val next = r.s("NEXT_DATE")?.toLongOrNull()?.takeIf { it > 0 }?.let { dayOf(it, tz) }
            val account = r.str("assetUid")
            if (k == null || type == null || next == null || account !in accountById) {
                skipped += "recurring ${r.str("uid")}: unsupported (type ${r.str("REPEAT_TYPE")})"; return@mapNotNull null
            }
            val code = codeOf(r.s("currencyUid"))
            Rule(
                r.str("uid").ifEmpty { newId() }, k, account,
                if (k == TxnKind.TRANSFER_OUT) idOrNull(r.s("toAssetUid"))?.takeIf { it in accountById } else null,
                idOrNull(r.s("ctgUid"))?.takeIf { it in allCatIds && k != TxnKind.TRANSFER_OUT },
                minor(r.s("AMOUNT_SUB"), decimals(code), signed = k == TxnKind.EXPENSE) ?: 0, r.str("PAYEE").trim(), type, next,
                r.s("END_DATE")?.toLongOrNull()?.takeIf { it > 0 }?.let { dayOf(it, tz) }, code,
                memo = r.str("MEMO").trim(),   // generated entries get PAYEE as the note and MEMO as the description
            )
        }.filter { it.kind != TxnKind.TRANSFER_OUT || it.toAccountId != null }

        // Budgets (monthly expense budgets only).
        val budgetRows = rows("BUDGET").filter { !it.isDeleted("IS_DEL") && it.int("DO_TYPE") == 1 && (it.int("PERIOD_TYPE") ?: 6) == 6 }
        val budgetTarget = budgetRows.associate { b -> b.str("uid") to (if (b.int("IS_TOTAL") == 1) "" else b.str("targetUid")) }
        val budgets = rows("BUDGET_AMOUNT").filter { !it.isDeleted("IS_DEL") && it.str("budgetUid") in budgetTarget }.mapNotNull { a ->
            val target = budgetTarget.getValue(a.str("budgetUid"))
            if (target.isNotEmpty() && target !in catIds) return@mapNotNull null
            Budget(target, a.int("BUDGET_PERIOD") ?: 0, minor(a.s("AMOUNT"), mainDecimals) ?: return@mapNotNull null)
        }

        val tags = rows("TAG").filterNot { it.isDeleted("isDel") }
            .filterNot { it.str("uid").startsWith("system_") }
            .map { Tag(it.str("uid"), it.str("name"), it.int("orderSeq") ?: 0) }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
        val tagIds = tags.map { it.id }.toSet()
        val txnIds = cleanTxns.map { it.id }.toSet()
        val txnTags = rows("TX_TAG").filterNot { it.isDeleted("isDel") }.map { it.str("txUid") to it.str("tagUid") }
            .filter { (t, g) -> t in txnIds && g in tagIds }

        val memos = rows("MEMO").filterNot { it.isDeleted("isDel") }.map {
            Memo(it.str("uid").ifEmpty { newId() }, it.str("title"), it.str("content"), it.int("pinned") == 1,
                it.s("memoTime")?.toDoubleOrNull()?.toLong() ?: it.s("utime")?.toDoubleOrNull()?.toLong() ?: 0L)
        }

        // Settings worth carrying over (ZETC key → value).
        val etc = rows("ZETC").filterNot { it.isDeleted("isDel") }.associate { it.str("dataTypeKey").ifEmpty { it.str("ZDATATYPE") } to it.str("ZDATA") }
        val settings = buildMap {
            etc["start_day"]?.toIntOrNull()?.takeIf { it in 1..31 }?.let { put(MoneyRepository.Keys.MONTH_START_DAY, it.toString()) }
            when (etc["month_start_weekend_type"]) { "1" -> put(MoneyRepository.Keys.WEEKEND_SHIFT, "BEFORE"); "2" -> put(MoneyRepository.Keys.WEEKEND_SHIFT, "AFTER") }
            when (etc["week_start_day"]) { "0" -> put(MoneyRepository.Keys.WEEK_START, "SUNDAY"); "1" -> put(MoneyRepository.Keys.WEEK_START, "MONDAY"); "6" -> put(MoneyRepository.Keys.WEEK_START, "SATURDAY") }
            if (etc["carry_over"] == "1") put(MoneyRepository.Keys.CARRY_OVER, "1")
            if (etc["card_accounting_type"] == "1") put(MoneyRepository.Keys.CARD_AT_PAYMENT, "1")
            // Money Manager's own "Subcategory" switch (off unless the user enabled it there).
            put(MoneyRepository.Keys.USE_SUBCATEGORIES, if (etc["use_sub_category"] == "1") "1" else "0")
            // Configuration screen choices.
            etc["main_tab_first_view"]?.toIntOrNull()?.let { put(MoneyRepository.Keys.START_SCREEN, if (it > 0) "CALENDAR" else "DAILY") }
            when (etc["main_tab_scroll_type"]) { "0" -> put(MoneyRepository.Keys.SWIPE, "TAB"); "1" -> put(MoneyRepository.Keys.SWIPE, "DATE"); "2" -> put(MoneyRepository.Keys.SWIPE, "NONE") }
            when (etc["money_color"]) { "0" -> put(MoneyRepository.Keys.COLOR_SET, "A"); "1" -> put(MoneyRepository.Keys.COLOR_SET, "B") }
            // Time: 5/0 = not asked (desc/asc), 1/3 = asked and shown in lists, 2/4 = asked only.
            etc["time_display_order"]?.let { t ->
                when (t) { "1", "3" -> "LIST"; "2", "4" -> "INPUT"; "0", "5" -> "NONE"; else -> null }?.let { put(MoneyRepository.Keys.TIME_INPUT, it) }
                when (t) { "0", "3", "4" -> "ASC"; "1", "2", "5" -> "DESC"; else -> null }?.let { put(MoneyRepository.Keys.TIME_SORT, it) }
            }
            when (etc["show_tx_memo_on_day_list"]) { "0" -> put(MoneyRepository.Keys.SHOW_DESCRIPTION, "0"); "1" -> put(MoneyRepository.Keys.SHOW_DESCRIPTION, "1") }
            when (etc["use_auto_complete"]) { "0" -> put(MoneyRepository.Keys.AUTOCOMPLETE, "0"); "1" -> put(MoneyRepository.Keys.AUTOCOMPLETE, "1") }
            when (etc["memo_btn_on_daily"]) { "0" -> put(MoneyRepository.Keys.NOTE_BUTTON, "0"); "1" -> put(MoneyRepository.Keys.NOTE_BUTTON, "1") }
            when (etc["input_form_order"]) { "1" -> put(MoneyRepository.Keys.INPUT_ORDER, "AMOUNT"); "2" -> put(MoneyRepository.Keys.INPUT_ORDER, "ACCOUNT") }
            when (etc["repeat_reflect_type"]) { "0" -> put(MoneyRepository.Keys.REPEAT_TIMING, "ON_DATE"); "1" -> put(MoneyRepository.Keys.REPEAT_TIMING, "MONTH_START") }
            // Messages: lists are one ZETC row per entry; switches by name.
            val etcRows = rows("ZETC").filterNot { it.isDeleted("isDel") }
            fun etcList(type: String) = etcRows.filter { it.str("ZDATATYPE") == type }.map { it.str("ZDATA").trim() }.filter { it.isNotEmpty() }
                .distinct().joinToString("\n").takeIf { it.isNotEmpty() }
            etcList("4838")?.let { put(MoneyRepository.Keys.SMS_BLOCKED_SENDERS, it) }
            etcList("4839")?.let { put(MoneyRepository.Keys.SMS_BLOCKED_PHRASES, it) }
            etcList("100")?.let { put(MoneyRepository.Keys.SMS_CONTENT_FILTER, it) }
            when (etc["sms_auto_save_to_tx"]) { "0" -> put(MoneyRepository.Keys.SMS_AUTO_SAVE, "0"); "1" -> put(MoneyRepository.Keys.SMS_AUTO_SAVE, "1") }
            when (etc["sms_show_noti"]) { "0" -> put(MoneyRepository.Keys.SMS_ALERT, "0"); "1" -> put(MoneyRepository.Keys.SMS_ALERT, "1") }
            (etc["sms_show_org_on_tx"] ?: etc["-12649"])?.takeIf { it in setOf("0", "1", "2") }?.let { put(MoneyRepository.Keys.SMS_SHOW_ORIGINAL, it) }
            etc["sms_search_day"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put(MoneyRepository.Keys.SMS_IMPORT_DAYS, it.toString()) }
            when (main?.str("SYMBOL_POSITION")) { "P" -> put(MoneyRepository.Keys.SYMBOL_AFTER, "0"); "S" -> put(MoneyRepository.Keys.SYMBOL_AFTER, "1") }
        }

        if (rows("PHOTO").any { !it.isDeleted("IS_DEL") }) skipped += "receipt photos are not inside .mmbak files and were not imported"
        return Data(mainCode, mainSymbol, mainDecimals, currencies, groups, fixedAccounts, categories, cleanTxns, rules, budgets,
            tags, txnTags, memos, settings, skipped,
            passcodePin = etc["-8457"]?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) })
    }
}
