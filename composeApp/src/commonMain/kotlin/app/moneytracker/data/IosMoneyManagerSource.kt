package app.moneytracker.data

import kotlin.math.floor

/**
 * Presents an iPhone Money Manager backup (Core Data SQLite: ZASSET, ZINOUTCOME, …) as the Android `.mmbak`
 * tables [MoneyManagerBackup.read] understands, so one importer handles both platforms.
 * Only the columns the importer reads are produced.
 *
 * The iOS database version (ZETC type 273) decides the layout: 2192+ link rows by uid columns, 2000–2191 by
 * numeric ZAIDs; 2190+ have a ZCURRENCY table. Files from 1999 and older keep currencies and recurring
 * entries inside text fields and are not supported ([supported] = false).
 */
class IosMoneyManagerSource(private val ios: RowSource) : RowSource {
    private val iosTables = ios.tables()
    private val cache = mutableMapOf<String, List<Map<String, String?>>>()
    private fun src(t: String) = cache.getOrPut(t) { if (t in iosTables) ios.rows(t) else emptyList() }

    private fun Map<String, String?>.has(col: String) = keys.any { it.equals(col, true) }
    private fun Map<String, String?>.s(col: String): String? = this[col] ?: entries.firstOrNull { it.key.equals(col, true) }?.value
    private fun Map<String, String?>.num(col: String) = s(col)?.trim()?.toDoubleOrNull()
    private fun Map<String, String?>.int(col: String) = num(col)?.toInt()
    private fun Map<String, String?>.long(col: String) = num(col)?.toLong()
    /** Numeric id as text, 0 = none. */
    private fun Map<String, String?>.aid(col: String) = long(col)?.takeIf { it != 0L }?.toString()
    private fun Map<String, String?>.either(a: String, b: String) = if (has(a)) s(a) else s(b)

    /** iOS database version: ZETC type 273, else guessed from the tables present. */
    val version: Int by lazy {
        src("ZETC").filter { it.int("ZDATATYPE") == 273 }.maxOfOrNull { it.int("ZDATA") ?: 0 }?.takeIf { it > 0 }
            ?: when { "ZCURRENCY" in iosTables -> 2190; "ZBUDGET" in iosTables -> 2189; else -> 0 }
    }
    val supported get() = version > 1999
    private val uids get() = version >= 2192

    override fun tables() = buildSet {
        addAll(listOf("ASSETS", "ASSETGROUP", "ZCATEGORY", "INOUTCOME", "ZETC"))
        if (version >= 2190) add("CURRENCY")
        if ("ZREPEATTRANSACTION" in iosTables) add("REPEATTRANSACTION")
        if ("ZBUDGET" in iosTables) { add("BUDGET"); add("BUDGET_AMOUNT") }
        if (uids) { add("TAG"); add("TX_TAG") }
        if (version >= 2191) add("MEMO")
        if ("ZPHOTO" in iosTables) add("PHOTO")
    }

    override fun rows(table: String): List<Map<String, String?>> = when (table) {
        "CURRENCY" -> currencies()
        "ASSETGROUP" -> groups()
        "ASSETS" -> assets()
        "ZCATEGORY" -> categories()
        "INOUTCOME" -> txns()
        "REPEATTRANSACTION" -> repeats()
        "BUDGET" -> budgetRows.first
        "BUDGET_AMOUNT" -> budgetRows.second
        "TAG" -> src("ZTAG").map { mapOf("uid" to it.s("ZUID"), "name" to it.s("ZNAME"), "isDel" to it.s("ZISDEL")) }
        "TX_TAG" -> src("ZTXTAG").map { mapOf("txUid" to it.s("ZTXUID"), "tagUid" to it.s("ZTAGUID"), "isDel" to it.s("ZISDEL")) }
        "MEMO" -> src("ZMEMO").map {
            mapOf("uid" to it.s("ZUID"), "title" to it.s("ZTITLE"), "content" to it.s("ZCONTENT"), "pinned" to it.s("ZPINNED"),
                "memoTime" to it.s("ZMEMOTIME"), "utime" to it.s("ZUTIME"), "isDel" to it.s("ZISDEL"))
        }
        "PHOTO" -> src("ZPHOTO").map { mapOf("IS_DEL" to it.s("ZISDEL")) }
        "ZETC" -> etc()
        else -> emptyList()
    }

    // ───────────────────────── currencies

    /** The last shown, undeleted main row is the main currency. */
    private val mainRow by lazy {
        src("ZCURRENCY").lastOrNull { it.int("ZISMAINCURRENCY") == 1 && (it.int("ZISDEL") ?: 0) == 0 && it.int("ZISSHOW") == 1 }
    }
    private val mainIso by lazy { mainRow?.s("ZISO")?.trim().orEmpty() }
    /** Android currency uids are "<main ISO>_<ISO>" (the importer reads the code from them); iOS uids may be random. */
    private fun androidUid(iso: String?) = "${mainIso}_${iso?.trim().orEmpty()}"
    private val currencyByUid by lazy { src("ZCURRENCY").filter { it.s("ZUID") != null }.associate { it.s("ZUID")!! to androidUid(it.s("ZISO")) } }
    private val currencyByAid by lazy { src("ZCURRENCY").mapNotNull { c -> c.long("ZAID")?.let { it to androidUid(c.s("ZISO")) } }.toMap() }
    private val mainUid get() = androidUid(mainIso)

    /** Currency of a row: ZCURRENCYUID (2192+) or ZCURRENCYID, where 0 = main (MigrationIPhone.a). Null = main/unknown. */
    private fun currencyOf(r: Map<String, String?>): String? = when {
        version < 2190 -> null
        uids -> r.s("ZCURRENCYUID")?.let { currencyByUid[it] }
        else -> r.long("ZCURRENCYID")?.let { if (it == 0L) mainUid else currencyByAid[it] } ?: mainUid
    }

    private fun currencies() = src("ZCURRENCY").map { c ->
        mapOf("uid" to androidUid(c.s("ZISO")), "ISO" to c.s("ZISO"), "SYMBOL" to c.s("ZSYMBOL"), "DECIMAL_POINT" to c.s("ZPOINT"),
            "RATE" to c.s("ZRATE"), "IS_DEL" to c.s("ZISDEL"), "IS_MAIN_CURRENCY" to if (c === mainRow) "1" else "0",
            "SYMBOL_POSITION" to c.s("ZSYMBOLPOSITION"))
    }

    // ───────────────────────── accounts and categories

    // Before 2192 a missing group id (0) means the default group 11.
    private fun groups() = src("ZASSETGROUP").map { g ->
        mapOf("uid" to if (uids) g.s("ZUID") else (g.long("ZAID")?.takeIf { it != 0L } ?: 11L).toString(),
            "ACC_GROUP_NAME" to g.s("ZASSETGROUPNAME"), "TYPE" to g.s("ZSTATUS"),   // iOS keeps the group type in ZSTATUS
            "ORDERSEQ" to g.s("ZORDER"), "IS_DEL" to g.s("ZISDEL"))
    }

    private fun assets() = src("ZASSET").map { a ->
        mapOf(
            "uid" to if (uids) a.s("ZUID") else a.long("ZAID")?.toString(),
            "groupUid" to if (uids) a.s("ZGROUPUID") else (a.long("ZGROUP_ID")?.takeIf { it != 0L } ?: 11L).toString(),
            "NIC_NAME" to a.s("ZNICNAME"), "ORDERSEQ" to a.s("ZORDER"),
            "CARD_DAY_FIN" to a.s("ZCARD_DAYFIN"),
            "CARD_DAY_PAY" to (a.s("ZCARD_DAYPAY")?.replace("일", "") ?: "0"),   // Korean builds stored "5일"
            "IS_TRANS_EXPENSE" to (a.int("ZTRANSFEREXPENSE") ?: 0).toString(),
            "CARD_USAGE_HURDLE_TYPE" to a.s("ZCARD_USAGE_HURDLE_TYPE").takeIf { version >= 2190 },
            "CARD_USAGE_HURDLE_AMOUNT" to a.s("ZCARD_USAGE_HURDLE_AMOUNT").takeIf { version >= 2190 },
            "cardAssetUid" to if (uids) a.s("ZCARDASSETUID") else a.long("ZCARD_ACCOUNT_ID")?.toString(),
            // Status: same codes as Android's ZDATA (1 deleted, 3 hidden). ISREFLECT 1 = left out of totals.
            "ZDATA" to if (uids) a.int("ZISDEL")?.toString() else (a.int("ZZDATA") ?: 0).toString(),
            "ZDATA2" to if (uids) (if (a.int("ZISREFLECT") == 1) "1" else "0") else (a.s("ZZDATA2") ?: "0"),
            "IS_CARD_AUTO_PAY" to a.either("ZISCARDAUTOPAY", "ZCARD_PAYEDMONEY"),
            "ZDATA1" to a.either("ZMEMO", "ZZDATA1"),
            "currencyUid" to currencyOf(a),
        )
    }

    private fun categories() = src("ZCATEGORY").map { c ->
        mapOf(
            "uid" to if (uids) c.s("ZUID") else c.long("ZAID")?.toString(),
            "pUid" to if (uids) c.s("ZPUID") else c.long("ZZDATA")?.toString(),
            "TYPE" to c.either("ZDOTYPE", "ZTYPE"), "NAME" to c.s("ZNAME"), "ORDERSEQ" to c.s("ZORDER"),
            "STATUS" to c.int("ZSTATUS")?.let { if (it == 1000) 0 else it }?.toString(),   // 1000 = main category on iOS; 2 = sub
            "C_IS_DEL" to c.s("ZISDEL"),
        )
    }

    // ───────────────────────── entries

    private fun txns() = src("ZINOUTCOME").mapNotNull { t ->
        // Card-installment month 1903911 marks old-style recurring templates, which are not entries.
        if (t.either("ZCARDDIVIDEMONTH", "ZCARDDIVIDMONTH")?.trim()?.toDoubleOrNull()?.toInt() == 1903911) return@mapNotNull null
        // Core Data dates are seconds since 2001-01-01 UTC; kept as whole seconds in epoch millis.
        val date = t.num("ZDATE")?.let { (floor(it).toLong() + 978_307_200L) * 1000 } ?: return@mapNotNull null
        val doType = t.int("ZDO_TYPE")
        val transfer = doType == 3 || doType == 4
        val uid = if (uids) t.s("ZUID") else t.long("ZAID")?.toString()
        val currency = currencyOf(t)
        // ZAMOUNT is in the main currency; an entry in the main currency may only have ZAMOUNTSUB filled.
        val sub = t.num("ZAMOUNTSUB") ?: 0.0
        val amount = (t.num("ZAMOUNT") ?: 0.0).let { if (it == 0.0 && sub != 0.0 && currency == mainUid) sub else it }
        mapOf(
            "uid" to uid,
            "assetUid" to if (uids) t.s("ZASSETUID") else t.long("ZASSET_ID")?.toString(),
            "toAssetUid" to if (!transfer) null else if (uids) t.s("ZTOASSETUID") else t.aid("ZCATEGORY_ID"),   // legacy transfers keep the other account in ZCATEGORY_ID
            "ctgUid" to if (transfer) null else if (uids) t.s("ZCATEGORYUID") else t.aid("ZCATEGORYID"),
            // Legacy legs point at each other via ZOPPOSITEAID; they are paired by the sorted
            // (ZAID, ZOPPOSITEAID) pair so two different transfers can't collide.
            "txUidTrans" to if (uids) t.s("ZTXUIDTRANS") else if (!transfer) null
                else listOfNotNull(t.long("ZAID"), t.long("ZOPPOSITEAID")).sorted().joinToString("_"),
            "txUidFee" to (if (uids) t.s("ZTXUIDFEE") else t.s("ZFEE_ID"))?.takeUnless { it.trim() == "0" },
            "cardDivideUid" to if (uids) t.s("ZCARDDIVIDEUID") else t.s("ZCARDDIVIDID"),
            "CARD_DIVIDE_MONTH_STR" to t.either("ZCARDDIVIDEMONTHSTR", "ZCARDDIVIDMONTHSTR"),
            // No WDATE: the importer then takes the day and time from ZDATE in the device time zone.
            "ZDATE" to date.toString(), "UTIME" to t.s("ZUTIME"),
            "DO_TYPE" to doType?.toString(), "ZCONTENT" to t.s("ZCONTENT"), "ZDATA" to t.either("ZMEMO", "ZZDATA"),
            "currencyUid" to currency, "ZMONEY" to amount.toString(), "IN_ZMONEY" to sub.toString(),
            "AMOUNT_ACCOUNT" to t.s("ZAMOUNTACCOUNT"), "IS_DEL" to t.s("ZISDEL"),
        )
    }

    private fun repeats() = src("ZREPEATTRANSACTION").filter { (it.int("ZREPEATTYPE") ?: 0) in 1..17 }.map { r ->
        // Next date: epoch millis from 2194, epoch seconds before. The end date is stored as-is;
        // values small enough to be seconds are scaled so they are not read as 1970.
        val next = r.num("ZNEXTDATE")?.let { if (version >= 2194) it.toLong() else (it * 1000).toLong() }
        val end = r.num("ZENDDATE")?.toLong()?.let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
        mapOf(
            "uid" to if (uids) r.s("ZUID") else r.long("ZAID")?.toString(),
            "IS_DEL" to r.s("ZISDEL"), "REPEAT_TYPE" to r.s("ZREPEATTYPE"),
            "NEXT_DATE" to next?.toString(), "END_DATE" to end?.toString(),
            "DO_TYPE" to (if (uids) r.s("ZDOTYPE") else r.s("ZDO_TYPE")),
            "assetUid" to if (uids) r.s("ZASSETUID") else r.aid("ZACCOUNT_ID"),
            "toAssetUid" to if (uids) r.s("ZTOASSETUID") else r.aid("ZTO_ACCOUNT_ID"),
            "ctgUid" to if (uids) r.s("ZCATEGORYUID") else r.aid("ZCATEGORYID"),
            "currencyUid" to currencyOf(r),
            "AMOUNT_SUB" to (if (uids) r.s("ZAMOUNTSUB") else r.s("ZAMOUNT_SUB")),
            "MEMO" to r.s("ZMEMO"), "PAYEE" to r.s("ZPAYEE"),
        )
    }

    // ───────────────────────── budgets

    /**
     * Monthly expense budgets (ZTRANSTYPEINT 0, ZDOTYPESTR 1, ZPERIODTYPEINT 6), one per target.
     * Before 2192 the target is ZCONIDSTR ("bTO" = total expense, "bTI" = total income, skipped) and the amount
     * lives on the budget row itself (ZMONEYSTR for the default period 0 and for ZDAYSTR).
     */
    private val budgetRows by lazy {
        val budgets = mutableListOf<Map<String, String?>>()
        val amounts = mutableListOf<Map<String, String?>>()
        val byTarget = mutableMapOf<String, String>()
        src("ZBUDGET").filter { it.int("ZISDEL") != 1 }.forEach { b ->
            val conId = b.s("ZCONIDSTR")
            if (!uids && conId == "bTI") return@forEach
            if ((b.int("ZTRANSTYPEINT") ?: 0) != 0 || b.int("ZDOTYPESTR") != 1 || b.int("ZPERIODTYPEINT") != 6) return@forEach
            val target = (if (uids) b.s("ZTARGETUID") else conId).orEmpty()
            val total = if (uids) b.int("ZTOTALTYPEINT") ?: 0 else if (conId == "bTO") 1 else 0
            val existing = byTarget[target]
            val uid = existing ?: (if (uids) b.s("ZUID") else b.long("ZAID")?.toString()).orEmpty().also { byTarget[target] = it }
            if (existing == null) budgets += mapOf("uid" to uid, "targetUid" to target, "DO_TYPE" to "1", "PERIOD_TYPE" to "6",
                "IS_TOTAL" to total.toString(), "IS_DEL" to "0")
            if (!uids) {
                val amount = b.s("ZMONEYSTR")
                val period = b.int("ZDAYSTR") ?: 0
                if (existing == null && period != 0) amounts += mapOf("budgetUid" to uid, "BUDGET_PERIOD" to "0", "AMOUNT" to amount, "IS_DEL" to "0")
                amounts += mapOf("budgetUid" to uid, "BUDGET_PERIOD" to period.toString(), "AMOUNT" to amount, "IS_DEL" to "0")
            }
        }
        src("ZBUDGETAMOUNT").filter { it.int("ZISDEL") != 1 }.forEach { a ->
            amounts += mapOf("budgetUid" to (if (uids) a.s("ZBUDGETUID") else a.long("ZBUDGETID")?.toString()),
                "BUDGET_PERIOD" to a.s("ZBUDGETPERIOD"), "AMOUNT" to a.s("ZAMOUNT"), "IS_DEL" to a.s("ZISDEL"))
        }
        budgets.toList() to amounts.toList()
    }

    // ───────────────────────── settings

    private fun etc(): List<Map<String, String?>> {
        val rows = src("ZETC").mapNotNull { e ->
            val type = e.int("ZDATATYPE") ?: return@mapNotNull null
            // -8540 = exchange-rate history, -25159 = pre-2190 main currency symbol; 30121401/25278 are skipped.
            if (type == -8540 || type == -25159 || type == 30121401 || type == 25278) return@mapNotNull null
            val data = when (type) {
                -6179 -> (e.int("ZDATA") ?: 0).toString()
                -29898 -> "0"
                else -> e.s("ZDATA")
            }
            mapOf("ZDATATYPE" to type.toString(), "dataTypeKey" to (ETC_KEYS[type] ?: ""), "ZDATA" to data)
        }
        // Without a colour setting the default one is used.
        return if (rows.any { it["ZDATATYPE"] == "-6179" }) rows
        else rows + mapOf("ZDATATYPE" to "-6179", "dataTypeKey" to ETC_KEYS[-6179], "ZDATA" to "0")
    }

    private companion object {
        /** ZETC type → Android dataTypeKey; others keep only their number (e.g. -8457 passcode). */
        val ETC_KEYS = mapOf(
            -23459 to "start_day", -6179 to "money_color", -5457 to "carry_over", 19810 to "use_sub_category",
            10002 to "week_start_day", 10004 to "time_display_order", 10008 to "month_start_weekend_type",
            23274 to "use_auto_complete", 10003 to "main_tab_first_view", 10011 to "show_tx_memo_on_day_list",
            10009 to "repeat_reflect_type", -30009 to "card_accounting_type", 10014 to "main_tab_scroll_type",
            10017 to "input_form_order", 10016 to "memo_btn_on_daily", 2123 to "default_period_type",
        )
    }
}
