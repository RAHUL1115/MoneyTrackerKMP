package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyManagerBackup
import app.moneytracker.data.MoneyManagerExport
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.RowSink
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Currency
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Note
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JDBC-backed [RowSink] for tests (the app uses Android's SQLiteDatabase). */
class JdbcRowSink(private val c: Connection) : RowSink {
    override fun exec(sql: String) { c.createStatement().use { it.execute(sql) } }
    override fun insert(table: String, values: Map<String, Any?>) {
        val cols = values.keys.toList()
        c.prepareStatement("INSERT INTO \"$table\" (${cols.joinToString { "\"$it\"" }}) VALUES (${cols.joinToString { "?" }})").use { st ->
            cols.forEachIndexed { i, k -> st.setObject(i + 1, values[k]) }
            st.executeUpdate()
        }
    }
}

class MoneyManagerExportTest {
    private val today = LocalDate.parse("2026-04-20")
    private val tz = TimeZone.of("Asia/Kolkata")
    private lateinit var repo: MoneyRepository

    private fun newRepo(): MoneyRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        return MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today }).also { it.seedIfEmpty() }
    }

    @BeforeTest fun setUp() {
        repo = newRepo()
        repo.saveCurrency(Currency("USD", "$", 2, 83.5, 1))
        val d = LocalDate.parse("2026-04-10")
        repo.create(TxnDraft(DraftType.EXPENSE, d, "acc_bank", categoryId = "cat_ex_0_1", amount = 450_00, note = "Dinner", memo = "with friends", timeOfDay = 20 * 60 + 15))
        repo.create(TxnDraft(DraftType.REFUND, d, "acc_cash", categoryId = "cat_ex_0", amount = 120_00, note = "Order refund"))
        repo.create(TxnDraft(DraftType.INCOME, d, "acc_bank", categoryId = "cat_in_0", amount = 50_000_00, note = "Pay"))
        repo.create(TxnDraft(DraftType.TRANSFER, d, "acc_bank", "acc_cash", amount = 2_000_00, fee = 10_00, note = "ATM"))
        repo.create(TxnDraft(DraftType.EXPENSE, d, "acc_card", categoryId = "cat_ex_3", amount = 3_000_00, installments = 3, note = "Phone"))
        repo.create(TxnDraft(DraftType.EXPENSE, d, "acc_bank", categoryId = "cat_ex_1", amount = 20_00, currency = "USD", note = "App"))
        repo.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-03-01"), "acc_bank", categoryId = "cat_ex_2", amount = 8_000_00,
            note = "Rent", repeat = RepeatType.MONTHLY))
        repo.create(TxnDraft(DraftType.REFUND, LocalDate.parse("2026-04-05"), "acc_bank", categoryId = "cat_ex_4", amount = 99_00,
            note = "Cashback", repeat = RepeatType.WEEKLY))
        repo.adjustBalance("acc_savings", 10_000_00)
        repo.setBudget("", YearMonth(2026, 1), 30_000_00)
        repo.setBudget("cat_ex_0", YearMonth(2026, 4), 5_000_00)
        val tag = repo.tagNamed("Trip")
        repo.allTxns().first { it.note == "Dinner" }.let { repo.setTags(it.id, listOf(tag, TAG_PREPAYMENT)) }
        repo.saveNote(Note("n1", "Plan", "Save more", pinned = true, createdAt = 1_776_000_000_000, updatedAt = 1_776_000_000_000))
        repo.putSetting(Keys.MONTH_START_DAY, "25"); repo.putSetting(Keys.COLOR_SET, "B"); repo.putSetting(Keys.WEEK_START, "MONDAY")
        repo.putSetting(Keys.TIME_INPUT, "LIST"); repo.putSetting(Keys.TIME_SORT, "ASC"); repo.putSetting(Keys.SMS_BLOCKED_SENDERS, "AD-PROMO\nVM-SPAM")
    }

    private fun export(d: MoneyManagerBackup.Data): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").also { MoneyManagerExport.write(JdbcRowSink(it), d, tz, 1_776_600_000_000) }

    @Test fun fileLooksLikeMoneyManagersOwn() {
        val c = export(repo.moneyManagerData())
        val tables = JdbcRowSource(c).tables()
        assertTrue(MoneyManagerBackup.isMoneyManager(tables))
        assertTrue("sqlite_sequence" in tables)                              // Money Manager's restore checks for it
        assertEquals(19, c.createStatement().executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) })
        val refund = JdbcRowSource(c).rows("INOUTCOME").first { it["ZCONTENT"] == "Order refund" }
        assertEquals("1", refund["DO_TYPE"]); assertEquals("-120.0", refund["ZMONEY"])   // Money Manager's own refund format
        val rows = JdbcRowSource(c).rows("INOUTCOME")
        assertTrue(rows.filter { it["ZCONTENT"] == "Rent" }.all { it["CARDDIVIDMONTH"] == "1" })   // "(Every Month)" in Money Manager
        assertTrue(rows.filter { it["ZCONTENT"] == "Phone" }.all { it["CARDDIVIDMONTH"] == "0" })  // instalments aren't repeats
        val etc = JdbcRowSource(c).rows("ZETC").associate { it["dataTypeKey"] to it["ZDATA"] }
        assertEquals("1", etc["money_color"]); assertEquals("25", etc["start_day"])
    }

    @Test fun roundTripKeepsEverything() {
        val before = repo.moneyManagerData()
        val after = MoneyManagerBackup.read(JdbcRowSource(export(before)), tz)

        assertEquals(before.mainCode, after.mainCode); assertEquals(before.mainSymbol, after.mainSymbol)
        assertEquals(before.currencies, after.currencies)
        assertEquals(before.groups.filterNot { it.deleted }, after.groups.filterNot { it.deleted })
        assertEquals(before.categories.sortedBy { it.id }, after.categories.sortedBy { it.id })
        // Card days are always written (Money Manager needs one); everything else matches.
        assertEquals(before.accounts.map { it.copy(cardSettlementDay = null, cardPaymentDay = null) }.sortedBy { it.id },
            after.accounts.map { it.copy(cardSettlementDay = null, cardPaymentDay = null) }.sortedBy { it.id })

        val a = after.txns.associateBy { it.id }
        assertEquals(before.txns.size, after.txns.size)
        before.txns.forEach { t ->
            val r = a.getValue(t.id)
            // Without a recorded time Money Manager still needs one; the creation time stands in for it.
            assertEquals(t.copy(createdAt = 0, timeOfDay = t.timeOfDay ?: r.timeOfDay), r.copy(createdAt = 0), t.note)
        }
        assertEquals(before.rules.sortedBy { it.id }, after.rules.sortedBy { it.id })
        assertEquals(before.budgets.sortedBy { it.categoryId + it.period }, after.budgets.sortedBy { it.categoryId + it.period })
        // Money Manager's built-in tags are not re-imported as user tags.
        assertEquals(before.tags.filterNot { it.id.startsWith("system_") }, after.tags)
        assertEquals(before.txnTags.filterNot { it.second.startsWith("system_") }, after.txnTags)
        assertEquals(before.memos, after.memos)
        before.settings.forEach { (k, v) -> assertEquals(v, after.settings[k], k) }
    }

    @Test fun reimportedBookHasTheSameTotals() {
        val other = newRepo()
        other.replaceWithMoneyManager(MoneyManagerBackup.read(JdbcRowSource(export(repo.moneyManagerData())), tz))
        val april = DateRange(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"))
        assertEquals(repo.totalsNow(april), other.totalsNow(april))
        listOf("acc_bank", "acc_cash", "acc_card", "acc_savings").forEach { id ->
            assertEquals(repo.accountTxns(id).sumOf { it.signedAccountValue }, other.accountTxns(id).sumOf { it.signedAccountValue }, id)
        }
    }
}
