package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.IosMoneyManagerSource
import app.moneytracker.data.MoneyManagerBackup
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosMoneyManagerImportTest {
    private fun repo(): MoneyRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        return MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-10-13") }).also { it.seedIfEmpty() }
    }

    private fun db(vararg sql: String): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        c.createStatement().use { st -> sql.forEach { st.execute(it) } }
        return c
    }

    /** A current (v2194, uid-linked) iPhone Money Manager Core Data file. ZDATE = seconds since 2001-01-01. */
    private fun modern() = db(
        "CREATE TABLE ZETC (Z_PK INTEGER PRIMARY KEY, ZDATATYPE INTEGER, ZDATA VARCHAR, ZZDATA VARCHAR, ZZDATA1 VARCHAR, ZZDATA2 VARCHAR, ZUID VARCHAR)",
        "CREATE TABLE ZCURRENCY (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZUID VARCHAR, ZISDEL INTEGER, ZISMAINCURRENCY INTEGER, ZISSHOW INTEGER, ZORDERSEQ INTEGER, ZPOINT INTEGER, ZRATE FLOAT, ZINSERTTYPE VARCHAR, ZISO VARCHAR, ZMAINISO VARCHAR, ZMEMO VARCHAR, ZSYMBOL VARCHAR, ZSYMBOLPOSITION VARCHAR)",
        "CREATE TABLE ZASSETGROUP (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZUID VARCHAR, ZISDEL INTEGER, ZASSETGROUPNAME VARCHAR, ZSTATUS INTEGER, ZORDER INTEGER)",
        "CREATE TABLE ZASSET (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZUID VARCHAR, ZNICNAME VARCHAR, ZORDER INTEGER, ZCARD_DAYFIN VARCHAR, ZCARD_DAYPAY VARCHAR, ZTRANSFEREXPENSE VARCHAR, ZCARD_USAGE_HURDLE_TYPE INTEGER, ZCARD_USAGE_HURDLE_AMOUNT FLOAT, ZCARDASSETUID VARCHAR, ZGROUPUID VARCHAR, ZISREFLECT INTEGER, ZISDEL INTEGER, ZISCARDAUTOPAY INTEGER, ZMEMO VARCHAR, ZCURRENCYUID VARCHAR)",
        "CREATE TABLE ZCATEGORY (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZUID VARCHAR, ZPUID VARCHAR, ZDOTYPE INTEGER, ZNAME VARCHAR, ZORDER INTEGER, ZSTATUS INTEGER, ZISDEL INTEGER, ZUTIME INTEGER)",
        "CREATE TABLE ZINOUTCOME (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZUID VARCHAR, ZASSETUID VARCHAR, ZTOASSETUID VARCHAR, ZCATEGORYUID VARCHAR, ZCONTENT VARCHAR, ZMEMO VARCHAR, ZDATE TIMESTAMP, ZDO_TYPE VARCHAR, ZAMOUNT FLOAT, ZAMOUNTSUB FLOAT, ZAMOUNTACCOUNT FLOAT, ZCURRENCYUID VARCHAR, ZTXUIDTRANS VARCHAR, ZTXUIDFEE VARCHAR, ZCARDDIVIDEUID VARCHAR, ZCARDDIVIDEMONTH VARCHAR, ZCARDDIVIDEMONTHSTR VARCHAR, ZISDEL INTEGER, ZUTIME INTEGER, ZASSET_NIC VARCHAR, ZCATEGORY_NAME VARCHAR)",
        "CREATE TABLE ZREPEATTRANSACTION (Z_PK INTEGER PRIMARY KEY, ZUID VARCHAR, ZISDEL INTEGER, ZREPEATTYPE INTEGER, ZNEXTDATE INTEGER, ZENDDATE INTEGER, ZMEMO VARCHAR, ZMARK INTEGER, ZPAYEE VARCHAR, ZDOTYPE INTEGER, ZAMOUNTSUB FLOAT, ZASSETUID VARCHAR, ZTOASSETUID VARCHAR, ZCATEGORYUID VARCHAR, ZCURRENCYUID VARCHAR)",
        "CREATE TABLE ZBUDGET (Z_PK INTEGER PRIMARY KEY, ZUID VARCHAR, ZTARGETUID VARCHAR, ZTOTALTYPEINT INTEGER, ZTRANSTYPEINT INTEGER, ZDOTYPESTR VARCHAR, ZPERIODTYPEINT INTEGER, ZISDEL INTEGER, ZORDER INTEGER)",
        "CREATE TABLE ZBUDGETAMOUNT (Z_PK INTEGER PRIMARY KEY, ZUID VARCHAR, ZBUDGETUID VARCHAR, ZBUDGETPERIOD INTEGER, ZISDEL INTEGER, ZAMOUNT FLOAT, ZUTIME INTEGER)",
        "CREATE TABLE ZTAG (Z_PK INTEGER PRIMARY KEY, ZUID VARCHAR, ZNAME VARCHAR, ZISDEL INTEGER)",
        "CREATE TABLE ZTXTAG (Z_PK INTEGER PRIMARY KEY, ZUID VARCHAR, ZTXUID VARCHAR, ZTAGUID VARCHAR, ZISDEL INTEGER)",
        "INSERT INTO ZETC (ZDATATYPE, ZDATA, ZZDATA) VALUES (273,'2194',NULL),(-23459,'25',NULL),(10002,'1',NULL),(-8457,'1530',NULL),(19810,'1',NULL),(-8540,'80','USD')",
        // Main INR; USD was added by the user, so its iOS uid is random (no "INR_USD").
        "INSERT INTO ZCURRENCY VALUES (1,1,'C-MAIN',0,1,1,0,2,1.0,'I','INR','INR','','₹','P'),(2,2,'5B1E-USD',0,0,1,1,2,80.0,'U','USD','INR','','$','P')",
        "INSERT INTO ZASSETGROUP VALUES (1,1,'g1',0,'Cash',11,0),(2,2,'g2',0,'Card',2,1),(3,3,'g3',0,'Bank',1,2)",
        // wallet; card (closes 20th, paid "5일" from bank); deleted; hidden USD account; bank left out of totals
        "INSERT INTO ZASSET VALUES (1,1,'a1','Wallet',0,NULL,NULL,'0',NULL,NULL,NULL,'g1',0,0,0,'pocket money','C-MAIN')," +
            "(2,2,'a2','HDFC Card',1,'20','5일','0',2,15000,'a5','g2',0,0,1,'','C-MAIN')," +
            "(3,3,'a3','Closed bank',2,NULL,NULL,'0',NULL,NULL,NULL,'g3',0,1,0,'','C-MAIN')," +
            "(4,4,'a4','US account',3,NULL,NULL,'0',NULL,NULL,NULL,'g3',0,3,0,'','5B1E-USD')," +
            "(5,5,'a5','Bank',4,NULL,NULL,'1',NULL,NULL,NULL,'g3',1,0,0,'','C-MAIN')",
        // 1000 = main category, 2 = sub-category of ZPUID; ZDOTYPE 0 income / 1 expense.
        "INSERT INTO ZCATEGORY VALUES (1,1,'c1',NULL,1,'Food',0,1000,0,0),(2,2,'c2','c1',1,'Lunch',0,2,0,0),(3,3,'c3',NULL,1,'Old stuff',1,1000,1,0),(4,4,'c4',NULL,0,'Salary',0,1000,0,0)",
        "INSERT INTO ZINOUTCOME (ZUID,ZASSETUID,ZTOASSETUID,ZCATEGORYUID,ZCONTENT,ZMEMO,ZDATE,ZDO_TYPE,ZAMOUNT,ZAMOUNTSUB,ZAMOUNTACCOUNT,ZCURRENCYUID,ZTXUIDTRANS,ZTXUIDFEE,ZCARDDIVIDEMONTH,ZISDEL) VALUES " +
            "('t1','a1',NULL,'c2','Pizza','extra',812539800.25,'1',250.5,250.5,250.5,'C-MAIN',NULL,'0','0',0)," +
            "('t2','a5',NULL,'c4','Pay','',812548800,'0',50000,50000,50000,'C-MAIN',NULL,NULL,'0',0)," +
            "('t3','a5','a1',NULL,'','',812628000,'3',1000,1000,1000,'C-MAIN','tr1',NULL,'0',0)," +
            "('t4','a1','a5',NULL,'','',812628000,'4',1000,1000,1000,'C-MAIN','tr1',NULL,'0',0)," +
            "('t5','a4',NULL,'c1','Burger','',812714400,'1',8000,100,100,'5B1E-USD',NULL,NULL,'0',0)," +
            "('t6','a1',NULL,'c1','Deleted one','',812800800,'1',10,10,10,'C-MAIN',NULL,NULL,'0',1)," +
            "('t7','a1',NULL,'c1','Old repeat template','',812800800,'1',10,10,10,'C-MAIN',NULL,NULL,'1903911',0)," +
            "('t8','a1',NULL,'c1','Only sub amount','',812718000,'1',0,30,30,'C-MAIN',NULL,NULL,'0',0)",
        // Next date in epoch millis from v2194 on (2026-10-26).
        "INSERT INTO ZREPEATTRANSACTION VALUES (1,'r1',0,1,1793000000000,0,'subs',0,'Netflix',1,1200,'a5',NULL,'c1','C-MAIN'),(2,'r2',0,0,1793000000000,0,'',0,'No type',1,5,'a5',NULL,'c1','C-MAIN')",
        "INSERT INTO ZBUDGET VALUES (1,'b1','',1,0,'1',6,0,0),(2,'b2','c4',0,0,'0',6,0,1)",
        "INSERT INTO ZBUDGETAMOUNT VALUES (1,'ba1','b1',0,0,30000,0),(2,'ba2','b2',0,0,99,0)",
        "INSERT INTO ZTAG VALUES (1,'tag1','Trip',0)",
        "INSERT INTO ZTXTAG VALUES (1,'x1','t1','tag1',0)",
    )

    @Test fun convertsModernIphoneBackup() = runTest {
        val conn = modern()
        assertTrue(MoneyManagerBackup.isIosMoneyManager(JdbcRowSource(conn).tables()))
        val src = IosMoneyManagerSource(JdbcRowSource(conn))
        assertEquals(2194, src.version); assertTrue(src.supported)
        assertTrue(MoneyManagerBackup.isMoneyManager(src.tables()))
        val data = MoneyManagerBackup.read(src, TimeZone.UTC)
        val repo = repo()
        val r = repo.replaceWithMoneyManager(data)

        assertEquals(6, r.transactions)                      // deleted entry and old repeat template dropped
        assertEquals(4, r.accounts); assertEquals(1, r.deletedAccounts)
        assertEquals(1, r.recurring)                         // repeat type 0 is not a rule
        assertEquals("INR", data.mainCode); assertEquals(listOf("USD"), data.currencies.map { it.code })

        val all = repo.allTxns().associateBy { it.id }
        all.getValue("t1").let {
            assertEquals(LocalDate.parse("2026-10-01"), it.date); assertEquals(9 * 60 + 30, it.timeOfDay)
            assertEquals(25_050L, it.amount); assertEquals("c2", it.categoryId); assertEquals("Pizza", it.note); assertEquals("extra", it.memo)
            assertEquals(1_790_847_000_000L, it.createdAt)
        }
        assertEquals(TxnKind.INCOME, all.getValue("t2").kind)
        assertEquals(TxnKind.TRANSFER_OUT, all.getValue("t3").kind); assertEquals(TxnKind.TRANSFER_IN, all.getValue("t4").kind)
        assertEquals("tr1", all.getValue("t3").transferGroupId); assertEquals("a1", all.getValue("t3").toAccountId)
        assertEquals(LocalDate.parse("2026-10-02"), all.getValue("t4").date)
        all.getValue("t5").let {   // USD 100 on the USD account, 8000 in rupees
            assertEquals("USD", it.currency); assertEquals(10_000L, it.originalAmount); assertEquals(10_000L, it.accountAmount); assertEquals(800_000L, it.amount)
        }
        assertEquals(3_000L, all.getValue("t8").amount)       // ZAMOUNT 0 in main currency → ZAMOUNTSUB
        assertNull(all["t6"]); assertNull(all["t7"])

        val accounts = repo.accounts.first().associateBy { it.id }
        assertEquals("pocket money", accounts.getValue("a1").memo)
        accounts.getValue("a2").let {
            assertEquals(20, it.cardSettlementDay); assertEquals(5, it.cardPaymentDay); assertTrue(it.cardAutoPay)
            assertEquals("a5", it.cardPaymentAccountId); assertEquals(1_500_000L, it.usageTarget); assertEquals(2, it.usageTargetType)
        }
        assertTrue(accounts.getValue("a4").hidden); assertEquals("USD", accounts.getValue("a4").currency)
        assertFalse(accounts.getValue("a5").includeInTotals); assertTrue(accounts.getValue("a5").transferAsExpense)
        assertEquals(listOf("a3"), repo.deletedAccounts.first().map { it.id })

        val cats = repo.categories.first().associateBy { it.id }
        assertEquals("c1", cats.getValue("c2").parentId); assertNull(cats.getValue("c1").parentId)
        assertTrue("c3" !in cats); assertEquals("Old stuff", repo.categoryById.first()["c3"]?.name)

        val rule = repo.rules.first().single()
        assertEquals(RepeatType.MONTHLY, rule.repeat); assertEquals(LocalDate.parse("2026-10-26"), rule.nextDate)
        assertEquals(120_000L, rule.amount); assertEquals("Netflix", rule.note); assertEquals("subs", rule.memo); assertEquals("c1", rule.categoryId)

        assertEquals(listOf(30_000_00L), repo.budgetEntries(YearMonth(2026, 10)).first().map { it.amount })
        assertEquals(listOf("Trip"), repo.txnTagNames.first()["t1"])
        assertEquals("25", repo.settingNow(MoneyRepository.Keys.MONTH_START_DAY))
        assertEquals("MONDAY", repo.settingNow(MoneyRepository.Keys.WEEK_START))
        assertEquals("1", repo.settingNow(MoneyRepository.Keys.USE_SUBCATEGORIES))
        assertEquals("A", repo.settingNow(MoneyRepository.Keys.COLOR_SET))   // default colours written when missing
        assertEquals("1530", data.passcodePin)
    }

    /** v2191: rows linked by numeric ZAIDs, currencies by ZCURRENCYID, transfer legs by ZOPPOSITEAID. */
    @Test fun convertsZaidBasedIphoneBackup() = runTest {
        val conn = db(
            "CREATE TABLE ZETC (Z_PK INTEGER PRIMARY KEY, ZDATATYPE INTEGER, ZDATA VARCHAR, ZZDATA VARCHAR, ZZDATA1 VARCHAR, ZZDATA2 VARCHAR)",
            "CREATE TABLE ZCURRENCY (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZISDEL INTEGER, ZISMAINCURRENCY INTEGER, ZISSHOW INTEGER, ZORDERSEQ INTEGER, ZPOINT INTEGER, ZRATE FLOAT, ZINSERTTYPE VARCHAR, ZISO VARCHAR, ZMAINISO VARCHAR, ZMEMO VARCHAR, ZSYMBOL VARCHAR, ZSYMBOLPOSITION VARCHAR)",
            "CREATE TABLE ZASSETGROUP (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZISDEL INTEGER, ZASSETGROUPNAME VARCHAR, ZSTATUS INTEGER, ZORDER INTEGER)",
            "CREATE TABLE ZASSET (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZNICNAME VARCHAR, ZORDER INTEGER, ZCARD_DAYFIN VARCHAR, ZCARD_DAYPAY VARCHAR, ZTRANSFEREXPENSE VARCHAR, ZCARD_USAGE_HURDLE_TYPE INTEGER, ZCARD_USAGE_HURDLE_AMOUNT FLOAT, ZCARD_ACCOUNT_ID INTEGER, ZGROUP_ID INTEGER, ZZDATA VARCHAR, ZZDATA1 VARCHAR, ZZDATA2 VARCHAR, ZCARD_PAYEDMONEY INTEGER, ZCURRENCYID INTEGER)",
            "CREATE TABLE ZCATEGORY (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZZDATA VARCHAR, ZTYPE INTEGER, ZNAME VARCHAR, ZORDER INTEGER, ZSTATUS INTEGER, ZISDEL INTEGER)",
            "CREATE TABLE ZINOUTCOME (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZOPPOSITEAID INTEGER, ZASSET_ID INTEGER, ZCATEGORY_ID INTEGER, ZCATEGORYID VARCHAR, ZFEE_ID VARCHAR, ZCARDDIVIDID VARCHAR, ZCARDDIVIDMONTH VARCHAR, ZCONTENT VARCHAR, ZZDATA VARCHAR, ZDATE TIMESTAMP, ZDO_TYPE VARCHAR, ZAMOUNT FLOAT, ZAMOUNTSUB FLOAT, ZAMOUNTACCOUNT FLOAT, ZCURRENCYID INTEGER, ZISDEL INTEGER, ZUTIME INTEGER)",
            "CREATE TABLE ZREPEATTRANSACTION (Z_PK INTEGER PRIMARY KEY, ZAID INTEGER, ZISDEL INTEGER, ZREPEATTYPE INTEGER, ZNEXTDATE INTEGER, ZENDDATE INTEGER, ZMEMO VARCHAR, ZMARK INTEGER, ZPAYEE VARCHAR, ZDO_TYPE INTEGER, ZAMOUNT_SUB FLOAT, ZACCOUNT_ID INTEGER, ZTO_ACCOUNT_ID INTEGER, ZCATEGORYID INTEGER, ZCURRENCYID INTEGER)",
            "INSERT INTO ZETC (ZDATATYPE, ZDATA) VALUES (273,'2191')",
            "INSERT INTO ZCURRENCY VALUES (1,1,0,1,1,0,2,1.0,'I','INR','INR','','₹','P'),(2,2,0,0,1,1,2,80.0,'I','USD','INR','','$','P')",
            "INSERT INTO ZASSETGROUP VALUES (1,0,0,'Cash',11,0),(2,3,0,'Bank',1,1)",
            // group id 0 = default group 11
            "INSERT INTO ZASSET VALUES (1,1,'Wallet',0,NULL,NULL,'0',0,0,0,0,'0','note',NULL,0,0),(2,2,'Bank',1,NULL,NULL,'0',0,0,0,3,'0','',NULL,0,1)",
            "INSERT INTO ZCATEGORY VALUES (1,1,NULL,1,'Food',0,1000,0),(2,2,'1',1,'Lunch',1,2,0)",
            "INSERT INTO ZINOUTCOME VALUES (1,10,11,2,1,NULL,'0',NULL,'0','','',812628000,'3',1000,1000,1000,0,0,0)," +
                "(2,11,10,1,2,NULL,'0',NULL,'0','','',812628000,'4',1000,1000,1000,0,0,0)," +
                "(3,12,0,1,0,'2','0',NULL,'0','Burger','',812714400,'1',800,10,800,2,0,0)",
            // Next date in epoch seconds before v2194 (2026-10-26).
            "INSERT INTO ZREPEATTRANSACTION VALUES (1,5,0,6,1793000000,0,'',0,'Gym',1,99,1,0,1,0)",
        )
        val src = IosMoneyManagerSource(JdbcRowSource(conn))
        assertEquals(2191, src.version)
        val data = MoneyManagerBackup.read(src, TimeZone.UTC)
        val repo = repo()
        val r = repo.replaceWithMoneyManager(data)
        assertEquals(3, r.transactions); assertEquals(2, r.accounts); assertEquals(1, r.recurring)

        val accounts = repo.accounts.first().associateBy { it.id }
        assertEquals("11", accounts.getValue("1").groupId); assertEquals("note", accounts.getValue("1").memo)
        val all = repo.allTxns().associateBy { it.id }
        assertEquals("10_11", all.getValue("10").transferGroupId); assertEquals("10_11", all.getValue("11").transferGroupId)
        assertEquals("1", all.getValue("10").toAccountId); assertEquals(TxnKind.TRANSFER_IN, all.getValue("11").kind)
        all.getValue("12").let { assertEquals("2", it.categoryId); assertEquals("USD", it.currency); assertEquals(1_000L, it.originalAmount); assertEquals(80_000L, it.amount) }
        assertEquals("1", repo.categories.first().single { it.id == "2" }.parentId)
        val rule = repo.rules.first().single()
        assertEquals(RepeatType.WEEKLY, rule.repeat); assertEquals(LocalDate.parse("2026-10-26"), rule.nextDate); assertEquals("1", rule.accountId)
    }
}
