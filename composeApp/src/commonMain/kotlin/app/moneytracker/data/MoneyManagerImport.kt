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

    fun read(src: RowSource, tz: TimeZone = TimeZone.currentSystemDefault()): Data = Reader(src, tz).read()

    /**
     * One pass over a backup's tables. [skipped] collects what couldn't be carried over, in reading order:
     * entries, then recurring rules, then photos.
     */
    private class Reader(private val src: RowSource, private val tz: TimeZone) {
        private val tables = src.tables()
        private val skipped = mutableListOf<String>()
        private fun rows(t: String) = if (t in tables) src.rows(t) else emptyList()

        // Currencies: "INR_USD" style uids = <main ISO>_<ISO>.
        private val curRows = rows("CURRENCY").filterNot { it.isDeleted("IS_DEL") }
        private val main = curRows.firstOrNull { it.int("IS_MAIN_CURRENCY") == 1 }
        private val mainCode = main?.str("ISO")?.ifEmpty { null } ?: "INR"
        private val mainDecimals = main?.int("DECIMAL_POINT") ?: 2
        private val mainSymbol = main?.str("SYMBOL")?.ifEmpty { null } ?: "₹"
        private val currencies = curRows.filter { it.int("IS_MAIN_CURRENCY") != 1 && it.str("ISO").isNotEmpty() && it.str("ISO") != mainCode }
            .map { Currency(it.str("ISO"), it.str("SYMBOL").ifEmpty { it.str("ISO") }, it.int("DECIMAL_POINT") ?: 2, it.s("RATE")?.toDoubleOrNull() ?: 1.0) }
            .distinctBy { it.code }
        private val decimalsOf = currencies.associate { it.code to it.decimals }
        private fun codeOf(currencyUid: String?): String? = currencyUid?.substringAfter('_', currencyUid)?.trim()
            ?.takeUnless { it.isEmpty() || it == mainCode || it !in decimalsOf }
        private fun decimals(code: String?) = code?.let { decimalsOf[it] } ?: mainDecimals

        private val accounts = rows("ASSETS").map(::account).filter { it.id.isNotEmpty() }
        private val accountById = accounts.associateBy { it.id }
        private val categoryRows = rows("ZCATEGORY")
        private val categoryIds = categoryRows.map { it.str("uid") }.toSet()

        fun read(): Data {
            val groups = groups()
            val txns = withLoneLegsPlain(txns())
            val categories = categories()
            val rules = rules(categories.map { it.id }.toSet())
            val budgets = budgets(categories.filterNot { it.deleted }.map { it.id }.toSet())
            val tags = tags()
            val memos = memos()
            // Settings worth carrying over (ZETC key → value; lists are one row per entry).
            val etcRows = rows("ZETC").filterNot { it.isDeleted("isDel") }
            val etc = etcRows.associate { it.str("dataTypeKey").ifEmpty { it.str("ZDATATYPE") } to it.str("ZDATA") }
            val settings = MoneyManagerSettings.read(etc, etcRows.map { it.str("ZDATATYPE") to it.str("ZDATA") }, main?.str("SYMBOL_POSITION"))
            if (rows("PHOTO").any { !it.isDeleted("IS_DEL") }) skipped += "receipt photos are not inside .mmbak files and were not imported"
            return Data(mainCode, mainSymbol, mainDecimals, currencies, groups, cardSettingsOnCards(groups), categories, txns, rules, budgets,
                tags, txnTags(tags, txns), memos, settings, skipped,
                passcodePin = etc["-8457"]?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) })
        }

        // Accounts: ZDATA '0' = normal; '1' = deleted (trash), '2' = purged → kept as deleted so history keeps names; '3' = hidden.
        private fun account(a: Map<String, String?>): Account {
            val status = a.str("ZDATA")
            return Account(
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
        }

        /** Keeps any group that holds accounts, even if the group itself was deleted. */
        private fun groups(): List<Group> {
            val usedGroups = accounts.map { it.groupId }.toSet()
            return rows("ASSETGROUP").map { g ->
                Group(g.str("uid"), g.str("ACC_GROUP_NAME").ifEmpty { "Accounts" }, groupType(g.int("TYPE")), g.int("ORDERSEQ") ?: 0,
                    deleted = g.isDeleted("IS_DEL") && g.str("uid") !in usedGroups)
            }.filter { it.id.isNotEmpty() }
        }

        /** Card settlement day only matters for credit cards. */
        private fun cardSettingsOnCards(groups: List<Group>): List<Account> {
            val groupTypeById = groups.associate { it.id to it.type }
            return accounts.map {
                if (groupTypeById[it.groupId] == AccountType.CREDIT_CARD) it
                else it.copy(cardSettlementDay = null, cardPaymentDay = null, cardAutoPay = false, usageTarget = null)
            }
        }

        private fun txns(): List<Txn> {
            val txRows = rows("INOUTCOME").filterNot { it.isDeleted("IS_DEL") }
            val feeToTransfer = txRows.filter { it.int("DO_TYPE") == 3 && idOrNull(it.s("txUidFee")) != null && idOrNull(it.s("txUidTrans")) != null }
                .associate { it.str("txUidFee") to it.str("txUidTrans") }
            val ruleUids = rows("REPEATTRANSACTION").filterNot { it.isDeleted("IS_DEL") }.map { it.str("uid") }.filter { it.isNotEmpty() }
            return txRows.mapNotNull { t ->
                fun skip(reason: String): Txn? {
                    skipped += "transaction ${t.str("uid")}: $reason"
                    return null
                }
                val k = kind(t.int("DO_TYPE")) ?: return@mapNotNull skip("unknown type ${t.str("DO_TYPE")}")
                if (t.str("assetUid") !in accountById) return@mapNotNull skip("unknown account")
                val date = entryDate(t) ?: return@mapNotNull skip("no date")
                val amountMain = minor(t.s("ZMONEY"), mainDecimals, signed = k == TxnKind.EXPENSE) ?: return@mapNotNull skip("no amount")
                txn(t, k, date, amountMain, feeToTransfer, ruleUids)
            }
        }

        private fun entryDate(t: Map<String, String?>): LocalDate? =
            LocalDate.Formats.ISO.parseOrNull(t.str("WDATE").take(10)) ?: t.s("ZDATE")?.toLongOrNull()?.let { dayOf(it, tz) }

        private fun txn(
            t: Map<String, String?>, k: TxnKind, date: LocalDate, amountMain: Long, feeToTransfer: Map<String, String>, ruleUids: List<String>,
        ): Txn {
            val account = t.str("assetUid")
            val code = codeOf(t.s("currencyUid"))
            val accountCode = accountById[account]?.currency
            val signed = k == TxnKind.EXPENSE
            val feeGroup = if (k == TxnKind.EXPENSE) idOrNull(t.s("txUidFee"))?.let { feeToTransfer[it] } else null
            val installment = idOrNull(t.s("cardDivideUid"))
            return Txn(
                id = t.str("uid").ifEmpty { newId() }, kind = k, date = date,
                createdAt = t.s("ZDATE")?.toLongOrNull() ?: t.s("UTIME")?.toLongOrNull() ?: 0L,
                accountId = account,
                toAccountId = if (k.isTransfer) idOrNull(t.s("toAssetUid"))?.takeIf { it in accountById } else null,
                categoryId = categoryOf(t, k),
                amountMain = amountMain,
                currency = code,
                originalAmount = code?.let { minor(t.s("IN_ZMONEY"), decimals(it), signed) },
                accountAmount = accountCode?.let { minor(t.s("AMOUNT_ACCOUNT"), decimals(it), signed) },
                note = t.str("ZCONTENT"), memo = t.str("ZDATA"),
                transferGroupId = if (k.isTransfer) idOrNull(t.s("txUidTrans")) else feeGroup,
                installmentGroupId = installment, installmentLabel = installment?.let { t.str("CARD_DIVIDE_MONTH_STR").ifEmpty { null } },
                lat = t.s("lat")?.toDoubleOrNull()?.takeIf { it != 0.0 }, lng = t.s("lng")?.toDoubleOrNull()?.takeIf { it != 0.0 },
                timeOfDay = timeOfDay(t, date),
                origin = t.str("SMS_ORIGIN").trim(),
                recurringRuleId = t.str("uid").let { uid -> ruleUids.firstOrNull { uid.startsWith(it + "_") } },
            )
        }

        /** "-4" is Money Manager's built-in "Modified Bal." (balance changes, with or without income/expense). */
        private fun categoryOf(t: Map<String, String?>, k: TxnKind): String? = when {
            t.s("ctgUid")?.trim() == "-4" && !k.isTransfer -> app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
            k.isIncomeOrExpense -> idOrNull(t.s("ctgUid"))?.takeIf { it in categoryIds }
            else -> null
        }

        /** Time of day: from WDATE when it carries one ("yyyy-MM-dd HH:mm"), else from ZDATE (epoch ms) on the same day. */
        private fun timeOfDay(t: Map<String, String?>, date: LocalDate): Int? =
            t.str("WDATE").takeIf { it.length >= 16 && it[13] == ':' }
                ?.let { w -> w.substring(11, 13).toIntOrNull()?.let { h -> w.substring(14, 16).toIntOrNull()?.let { m -> h * 60 + m } } }
                ?: t.s("ZDATE")?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz) }
                    ?.takeIf { it.date == date }?.let { it.hour * 60 + it.minute }

        /** A transfer needs both legs; a lone leg would double-count, so keep it as plain in/out. */
        private fun withLoneLegsPlain(txns: List<Txn>): List<Txn> {
            val legs = txns.filter { it.kind.isTransfer }.groupBy { it.transferGroupId }
            fun isPaired(t: Txn) = t.transferGroupId != null && legs[t.transferGroupId]?.size == 2 && t.toAccountId != null
            return txns.map { t ->
                if (t.kind.isTransfer && !isPaired(t)) {
                    t.copy(kind = if (t.kind == TxnKind.TRANSFER_OUT) TxnKind.EXPENSE else TxnKind.INCOME, toAccountId = null, transferGroupId = null)
                } else t
            }
        }

        /** Deleted categories stay deleted (out of the pickers) but are kept, so past entries still show their names. */
        private fun categories(): List<Category> {
            val parentOf = categoryRows.associate { it.str("uid") to (if (it.int("STATUS") == 2) idOrNull(it.s("pUid")) else null) }
            return categoryRows.map { c ->
                val id = c.str("uid")
                Category(id, c.str("NAME").ifEmpty { "Category" }, c.int("TYPE") == 0, parentOf[id]?.takeIf { it in categoryIds },
                    c.int("ORDERSEQ") ?: 0, deleted = c.isDeleted("C_IS_DEL"))
            }.filter { it.id.isNotEmpty() }
        }

        private fun rules(allCatIds: Set<String>): List<Rule> =
            rows("REPEATTRANSACTION").filterNot { it.isDeleted("IS_DEL") }.mapNotNull { r ->
                fun unsupported(): Rule? {
                    skipped += "recurring ${r.str("uid")}: unsupported (type ${r.str("REPEAT_TYPE")})"
                    return null
                }
                val k = kind(r.int("DO_TYPE"))?.takeIf { it == TxnKind.INCOME || it == TxnKind.EXPENSE || it == TxnKind.TRANSFER_OUT }
                    ?: return@mapNotNull unsupported()
                val type = repeatType(r.int("REPEAT_TYPE")) ?: return@mapNotNull unsupported()
                val next = r.s("NEXT_DATE")?.toLongOrNull()?.takeIf { it > 0 }?.let { dayOf(it, tz) } ?: return@mapNotNull unsupported()
                if (r.str("assetUid") !in accountById) return@mapNotNull unsupported()
                rule(r, k, type, next, allCatIds)
            }.filter { it.kind != TxnKind.TRANSFER_OUT || it.toAccountId != null }

        private fun rule(r: Map<String, String?>, k: TxnKind, type: RepeatType, next: LocalDate, allCatIds: Set<String>): Rule {
            val code = codeOf(r.s("currencyUid"))
            return Rule(
                r.str("uid").ifEmpty { newId() }, k, r.str("assetUid"),
                if (k == TxnKind.TRANSFER_OUT) idOrNull(r.s("toAssetUid"))?.takeIf { it in accountById } else null,
                idOrNull(r.s("ctgUid"))?.takeIf { it in allCatIds && k != TxnKind.TRANSFER_OUT },
                minor(r.s("AMOUNT_SUB"), decimals(code), signed = k == TxnKind.EXPENSE) ?: 0, r.str("PAYEE").trim(), type, next,
                r.s("END_DATE")?.toLongOrNull()?.takeIf { it > 0 }?.let { dayOf(it, tz) }, code,
                memo = r.str("MEMO").trim(),   // generated entries get PAYEE as the note and MEMO as the description
            )
        }

        /** Monthly expense budgets only. */
        private fun budgets(catIds: Set<String>): List<Budget> {
            val budgetRows = rows("BUDGET").filter { !it.isDeleted("IS_DEL") && it.int("DO_TYPE") == 1 && (it.int("PERIOD_TYPE") ?: 6) == 6 }
            val budgetTarget = budgetRows.associate { b -> b.str("uid") to (if (b.int("IS_TOTAL") == 1) "" else b.str("targetUid")) }
            return rows("BUDGET_AMOUNT").filter { !it.isDeleted("IS_DEL") && it.str("budgetUid") in budgetTarget }.mapNotNull { a ->
                val target = budgetTarget.getValue(a.str("budgetUid"))
                if (target.isNotEmpty() && target !in catIds) return@mapNotNull null
                Budget(target, a.int("BUDGET_PERIOD") ?: 0, minor(a.s("AMOUNT"), mainDecimals) ?: return@mapNotNull null)
            }
        }

        private fun tags(): List<Tag> = rows("TAG").filterNot { it.isDeleted("isDel") }
            .filterNot { it.str("uid").startsWith("system_") }
            .map { Tag(it.str("uid"), it.str("name"), it.int("orderSeq") ?: 0) }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }

        private fun txnTags(tags: List<Tag>, txns: List<Txn>): List<Pair<String, String>> {
            val tagIds = tags.map { it.id }.toSet()
            val txnIds = txns.map { it.id }.toSet()
            return rows("TX_TAG").filterNot { it.isDeleted("isDel") }.map { it.str("txUid") to it.str("tagUid") }
                .filter { (t, g) -> t in txnIds && g in tagIds }
        }

        private fun memos(): List<Memo> = rows("MEMO").filterNot { it.isDeleted("isDel") }.map {
            Memo(it.str("uid").ifEmpty { newId() }, it.str("title"), it.str("content"), it.int("pinned") == 1,
                it.s("memoTime")?.toDoubleOrNull()?.toLong() ?: it.s("utime")?.toDoubleOrNull()?.toLong() ?: 0L)
        }
    }
}
