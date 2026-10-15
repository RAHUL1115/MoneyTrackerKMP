package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyManagerBackup
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.RowSource
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.totals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JDBC-backed [RowSource] for tests (the app uses Android's SQLiteDatabase). */
class JdbcRowSource(private val c: Connection) : RowSource {
    override fun tables() = c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { r ->
        buildSet { while (r.next()) add(r.getString(1)) }
    }
    override fun rows(table: String) = c.createStatement().executeQuery("SELECT * FROM \"$table\"").use { r ->
        val n = r.metaData.columnCount
        buildList { while (r.next()) add((1..n).associate { r.metaData.getColumnName(it) to r.getString(it) }) }
    }
}

class MoneyManagerImportTest {
    private fun repo(): MoneyRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        return MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-10-13") }).also { it.seedIfEmpty() }
    }

    /** A tiny Money Manager v19 database with the cases that matter. */
    private fun fixture(): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        val st = c.createStatement()
        listOf(
            "CREATE TABLE ASSETGROUP (DEVICE_ID INTEGER PRIMARY KEY, uid TEXT, IS_DEL INTEGER, USETIME INTEGER, ACC_GROUP_NAME VARCHAR, TYPE INTEGER, ORDERSEQ INTEGER)",
            "CREATE TABLE ASSETS (ID INTEGER PRIMARY KEY, CARD_DAY_FIN varchar, NIC_NAME varchar, ORDERSEQ integer, ZDATA varchar, ZDATA2 varchar, IS_TRANS_EXPENSE INTEGER, uid TEXT, currencyUid TEXT, cardAssetUid TEXT, groupUid TEXT, ZDATA1 varchar, CARD_DAY_PAY varchar, IS_CARD_AUTO_PAY INTEGER, CARD_USAGE_HURDLE_TYPE integer, CARD_USAGE_HURDLE_AMOUNT real)",
            "CREATE TABLE ZCATEGORY (ID INTEGER PRIMARY KEY, C_IS_DEL integer, uid TEXT, NAME varchar, ORDERSEQ integer, TYPE integer, STATUS integer, pUid TEXT)",
            "CREATE TABLE INOUTCOME (AID INTEGER PRIMARY KEY, uid TEXT, assetUid TEXT, CARDDIVIDMONTH VARCHAR, ctgUid TEXT, toAssetUid TEXT, ZCONTENT VARCHAR, ZDATE VARCHAR, WDATE VARCHAR, DO_TYPE VARCHAR, ZMONEY VARCHAR, txUidTrans TEXT, ZDATA VARCHAR, IN_ZMONEY VARCHAR, cardDivideUid TEXT, CARD_DIVIDE_MONTH_STR VARCHAR, txUidFee TEXT, IS_DEL INTEGER, UTIME INTEGER, currencyUid TEXT, AMOUNT_ACCOUNT REAL, lat TEXT, lng TEXT)",
            "CREATE TABLE CURRENCY (ID INTEGER PRIMARY KEY, uid varchar, NAME varchar, ISO varchar, MAIN_ISO varchar, IS_DEL integer, ORDER_SEQ integer, RATE real, SYMBOL varchar, IS_MAIN_CURRENCY integer, DECIMAL_POINT integer)",
            "CREATE TABLE REPEATTRANSACTION (DEVICE_ID INTEGER PRIMARY KEY, uid TEXT, IS_DEL INTEGER, END_DATE INTEGER, NEXT_DATE INTEGER, DO_TYPE INTEGER, REPEAT_TYPE INTEGER, assetUid TEXT, toAssetUid TEXT, ctgUid TEXT, currencyUid TEXT, AMOUNT_SUB REAL, MEMO VARCHAR, PAYEE VARCHAR)",
            "CREATE TABLE BUDGET (ID integer primary key, uid TEXT, targetUid TEXT, DO_TYPE integer, PERIOD_TYPE integer, IS_TOTAL integer, IS_DEL integer, TRANSFER_TYPE integer)",
            "CREATE TABLE BUDGET_AMOUNT (ID integer primary key, uid text, budgetUid TEXT, IS_DEL integer, AMOUNT real, BUDGET_PERIOD integer)",
            "CREATE TABLE TAG (id integer primary key, uid text, name text, orderSeq integer, isDel integer)",
            "CREATE TABLE TX_TAG (id integer primary key, uid text, txUid text, tagUid text, isDel integer)",
            "CREATE TABLE ZETC (Z_PK integer primary key, uid TEXT, ZDATATYPE integer, ZDATA varchar, dataTypeKey varchar, isDel integer)",
            "INSERT INTO CURRENCY VALUES (1,'INR_INR','Rupee','INR','INR',0,0,1.0,'₹',1,2),(2,'INR_USD','Dollar','USD','INR',0,1,80.0,'$',0,2)",
            "INSERT INTO ASSETGROUP VALUES (1,'1',0,0,'Cash',11,0),(2,'2',0,0,'Card',2,1),(3,'3',1,0,'Old',1,2),(4,'4',0,0,'Savings',4,3)",
            // cash, card (cycle day 20, paid from bank), old deleted account in a deleted group, hidden USD account, savings (transfer = expense)
            "INSERT INTO ASSETS VALUES (1,'1','Wallet',0,'0','0',0,'a1','INR_INR','','1','pocket money',NULL,NULL,NULL,NULL),(2,'20','HDFC Card',1,'0','0',0,'a2','INR_INR','a5','2','',5,1,2,15000)," +
                "(3,'1','Closed bank',2,'1','1',0,'a3','INR_INR','','3','',NULL,NULL,NULL,NULL),(4,'1','US account',3,'3','0',0,'a4','INR_USD','','4','',NULL,NULL,NULL,NULL),(5,'1','Bank',4,'0','0',0,'a5','INR_INR','','4','',4,1,NULL,NULL)",
            "INSERT INTO ZCATEGORY VALUES (1,0,'c1','Food',0,1,0,'0'),(2,0,'c2','Lunch',0,1,2,'c1'),(3,1,'c3','Old stuff',1,1,0,'0'),(4,0,'c4','Salary',0,0,0,'0')",
            "INSERT INTO INOUTCOME VALUES " +
                "(1,'t1','a1','0','c2','','Pizza','1790847000000','2026-10-01','1','250.50','','extra','250.50','','','',0,0,'INR_INR',250.5,'',''),"+
                "(2,'t2','a5','0','c4','','Pay','1791000000001','2026-10-01','0','50000','','','50000','','','',NULL,0,'INR_INR',50000,'',''),"+
                "(3,'t3','a5','0','','a1','','1791000000002','2026-10-02','3','1000','tr1','','1000','','','fee1',0,0,'INR_INR',1000,'',''),"+
                "(4,'t4','a1','0','','a5','','1791000000002','2026-10-02','4','1000','tr1','','1000','','','',0,0,'INR_INR',1000,'',''),"+
                "(5,'t5','a5','0','c1','','Transfer fee','1791000000003','2026-10-02','1','5','','','5','','','fee1',0,0,'INR_INR',5,'',''),"+
                "(6,'t6','a3','0','c3','','Old purchase','1600000000000','2020-09-13','1','99','','','99','','','',0,0,'INR_INR',99,'',''),"+
                "(7,'t7','a4','0','','','','1791000000004','2026-10-03','7','8000','','','100','','','',0,0,'INR_USD',100,'',''),"+
                "(8,'t8','a1','0','-4','','','1791000000005','2026-10-03','8','49.50','','','49.50','','','',0,0,'INR_INR',49.5,'',''),"+
                "(9,'t9','a1','0','c2','','Deleted one','1791000000006','2026-10-04','1','10','','','10','','','',1,0,'INR_INR',10,'','')",
            "INSERT INTO REPEATTRANSACTION VALUES (1,'r1',0,0,1793000000000,1,1,'a5','','c1','INR_INR',1200,'','Netflix'),(2,'r2',0,0,1793000000000,1,4,'a5','','c1','INR_INR',1,'Quarterly-ish','Every4m'),(3,'r3',0,0,1793000000000,1,99,'a5','','c1','INR_INR',1,'','Odd')",
            "INSERT INTO BUDGET VALUES (1,'b1','',1,6,1,0,0),(2,'b2','c1',1,6,0,1,0)",
            "INSERT INTO BUDGET_AMOUNT VALUES (1,'ba1','b1',0,30000,0),(2,'ba2','b2',0,999,0)",
            "INSERT INTO TAG VALUES (1,'g1','Trip',0,0),(2,'system_prepayment','Prepay',1,0)",
            "INSERT INTO TX_TAG VALUES (1,'x1','t1','g1',0)",
            "INSERT INTO ZETC VALUES (1,'e1',-23459,'25','start_day',0),(2,'e2',10002,'1','week_start_day',0),(3,'e3',-5457,'1','carry_over',0)," +
                "(4,'e4',10004,'2','time_display_order',0),(5,'e5',23274,'0','use_auto_complete',0),(6,'e6',10017,'2','input_form_order',0),(7,'e7',10011,'1','show_tx_memo_on_day_list',0),(8,'e8',-8457,'1530','',0)",
        ).forEach { st.execute(it) }
        return c
    }

    @Test fun convertsMoneyManagerBackup() = runTest {
        val data = MoneyManagerBackup.read(JdbcRowSource(fixture()), TimeZone.UTC)
        val repo = repo()
        val r = repo.replaceWithMoneyManager(data)

        assertEquals(8, r.transactions)                      // 9 rows, the deleted one skipped
        assertEquals(4, r.accounts); assertEquals(1, r.deletedAccounts)
        assertEquals(2, r.recurring)                          // unknown repeat type 99 is skipped
        assertTrue(r.skipped.any { "recurring r3" in it })
        val rules = repo.rules.first().associateBy { it.id }
        assertEquals(app.moneytracker.domain.RepeatType.FOUR_MONTHLY, rules.getValue("r2").repeat)
        assertEquals("Every4m", rules.getValue("r2").note); assertEquals("Quarterly-ish", rules.getValue("r2").memo)

        val all = repo.allTxns().associateBy { it.id }
        assertEquals(25_050L, all.getValue("t1").amount)
        assertEquals("extra", all.getValue("t1").memo)
        assertEquals(9 * 60 + 30, all.getValue("t1").timeOfDay)          // from ZDATE (09:30 UTC)
        assertEquals(null, all.getValue("t2").timeOfDay)                 // ZDATE falls on another day than WDATE
        assertEquals("tr1", all.getValue("t3").transferGroupId); assertEquals("tr1", all.getValue("t4").transferGroupId)
        assertEquals("tr1", all.getValue("t5").transferGroupId)        // fee follows its transfer
        assertEquals(TxnKind.ADJUST_UP, all.getValue("t7").kind)
        assertEquals("USD", all.getValue("t7").currency); assertEquals(10_000L, all.getValue("t7").originalAmount)
        assertEquals(10_000L, all.getValue("t7").accountAmount); assertEquals(800_000L, all.getValue("t7").amount)
        assertEquals(app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY, all.getValue("t8").categoryId)   // "-4" = Modified Bal.
        assertEquals("c3", all.getValue("t6").categoryId)               // deleted category kept for its name...
        assertTrue(repo.categories.first().none { it.id == "c3" })      // ...but not offered in pickers
        assertEquals("Old stuff", repo.categoryById.first()["c3"]?.name)

        // Balances in each account's own currency: wallet 1000 - 250.50 - 49.50; bank 50000 - 1000 - 5.
        val bal = repo.balances.first()
        assertEquals(70_000L, bal["a1"]); assertEquals(4_899_500L, bal["a5"]); assertEquals(10_000L, bal["a4"])
        // Reports: income 50000 (adjustments and transfers excluded), expense 250.50 + 5.
        val t = totals(repo.reportRows(DateRange(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))).first())
        assertEquals(5_000_000L, t.income); assertEquals(25_550L, t.expense)

        val accounts = repo.accounts.first().associateBy { it.id }
        assertEquals(20, accounts.getValue("a2").cardSettlementDay); assertEquals("a5", accounts.getValue("a2").cardPaymentAccountId)
        assertEquals("USD", accounts.getValue("a4").currency)
        assertEquals("pocket money", accounts.getValue("a1").memo)
        accounts.getValue("a2").let { assertEquals(5, it.cardPaymentDay); assertTrue(it.cardAutoPay); assertEquals(1_500_000L, it.usageTarget); assertEquals(2, it.usageTargetType) }
        assertEquals(null, accounts.getValue("a5").cardPaymentDay)        // not a credit card
        assertTrue(accounts.getValue("a4").hidden); assertTrue(accounts.values.filter { it.id != "a4" }.none { it.hidden })   // ZDATA '3' = hidden, not deleted
        assertEquals(listOf("a3"), repo.deletedAccounts.first().map { it.id })
        assertEquals("Closed bank", repo.accountNames.first()["a3"])     // history still shows its name

        assertEquals("25", repo.settingNow(MoneyRepository.Keys.MONTH_START_DAY))
        assertEquals("MONDAY", repo.settingNow(MoneyRepository.Keys.WEEK_START))
        assertEquals("INPUT", repo.settingNow(MoneyRepository.Keys.TIME_INPUT)); assertEquals("DESC", repo.settingNow(MoneyRepository.Keys.TIME_SORT))
        assertEquals("0", repo.settingNow(MoneyRepository.Keys.AUTOCOMPLETE)); assertEquals("ACCOUNT", repo.settingNow(MoneyRepository.Keys.INPUT_ORDER))
        assertEquals("1", repo.settingNow(MoneyRepository.Keys.SHOW_DESCRIPTION))
        assertEquals("1530", data.passcodePin)
        assertEquals("1", repo.settingNow(MoneyRepository.Keys.CARRY_OVER))
        assertEquals(listOf("Trip"), repo.txnTagNames.first()["t1"])
        assertEquals(listOf(30_000_00L), repo.budgetEntries(app.moneytracker.domain.YearMonth(2026, 10)).first().map { it.amount })
        // Money Manager had sub-categories off, so suggestions use the main category.
        assertEquals("0", repo.settingNow(MoneyRepository.Keys.USE_SUBCATEGORIES))
        assertEquals("c1", repo.suggest(listOf("pizza")).categoryId)       // learned "Lunch", suggested as "Food"
    }

    /**
     * Local check against a real backup (never committed): set MM_BACKUP=/path/to/file.mmbak.
     * Compares every account's balance with Money Manager's own formula and prints only counts.
     */
    @Test fun realBackupBalancesMatch() = runTest {
        val path = System.getenv("MM_BACKUP") ?: return@runTest
        val conn = DriverManager.getConnection("jdbc:sqlite:" + File(path).absolutePath)
        val data = MoneyManagerBackup.read(JdbcRowSource(conn))
        val repo = repo()
        val r = repo.replaceWithMoneyManager(data)
        println("REAL BACKUP: $r")
        val expected = conn.createStatement().executeQuery("""
            SELECT I.assetUid,
              TOTAL(CASE WHEN DO_TYPE IN ('0','4','7') THEN (CASE WHEN A.currencyUid = C.uid THEN CAST(ZMONEY AS REAL) ELSE AMOUNT_ACCOUNT END) ELSE 0 END) -
              TOTAL(CASE WHEN DO_TYPE IN ('1','3','8') THEN (CASE WHEN A.currencyUid = C.uid THEN CAST(ZMONEY AS REAL) ELSE AMOUNT_ACCOUNT END) ELSE 0 END)
            FROM INOUTCOME I JOIN ASSETS A ON A.uid = I.assetUid, CURRENCY C
            WHERE (I.IS_DEL IS NULL OR I.IS_DEL != 1) AND C.IS_MAIN_CURRENCY = 1 AND I.WDATE <= '2026-10-13'
            GROUP BY I.assetUid""").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), Math.round(rs.getDouble(2) * 100)) } }
        val ours = repo.balances.first()
        val mismatches = expected.filter { (id, v) -> (ours[id] ?: 0L) != v }
        println("ACCOUNTS COMPARED: ${expected.size}, MISMATCHES: ${mismatches.size}")
        mismatches.keys.take(5).forEach { println("  mismatch account $it: expected ${expected[it]} got ${ours[it]}") }
        assertEquals(0, mismatches.size)
    }
}
