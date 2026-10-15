package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TabularImport
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Account
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V2FeaturesTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val march = Periods.month(YearMonth(2026, 3), 1)
    private val all = DateRange(LocalDate.parse("2000-01-01"), LocalDate.parse("2100-01-01"))

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    @Test fun migrationFromV1KeepsDataAndAddsTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        V1_DDL.forEach { driver.execute(null, it, 0) }
        driver.execute(null, "INSERT INTO Txn(id,kind,date,createdAt,accountId,amount,updatedAt) VALUES ('t1','EXPENSE',20527,0,'a',500,0)", 0)
        MoneyDatabase.Schema.migrate(driver, 1, MoneyDatabase.Schema.version)
        val db = MoneyDatabase(driver)
        assertEquals(500, db.moneyQueries.allTxns().executeAsList().single().amount)
        assertTrue(db.moneyQueries.allCurrencies().executeAsList().isEmpty())   // new table exists
        assertEquals(6L, MoneyDatabase.Schema.version)
        assertEquals(null, db.moneyQueries.allCategories().executeAsList().firstOrNull()?.defaultAccountId)   // v3 column exists
        assertEquals(null, db.moneyQueries.allTxns().executeAsList().single().timeOfDay)                    // v4 column exists
        driver.execute(null, "INSERT INTO Account(id,groupId,name,sortOrder,updatedAt) VALUES ('a','g','A',0,0)", 0)
        assertEquals("", db.moneyQueries.allAccounts().executeAsList().single().memo)                        // v5 columns exist
        assertEquals(1L, db.moneyQueries.allAccounts().executeAsList().single().usageTargetType)
    }

    @Test fun searchMatchesNoteCategoryAccountAndAmountRange() = runTest {
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 25_000, note = "Pizza night"))
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_bank", categoryId = "cat_ex_2_0", amount = 900_000, note = "March rent"))
        assertEquals(listOf("Pizza night"), repo.search("pizza", all, null, null).map { it.note })
        assertEquals(listOf("Pizza night"), repo.search("eating", all, null, null).map { it.note })      // sub-category name
        assertEquals(listOf("March rent"), repo.search("bank", all, null, null).map { it.note })         // account name
        assertEquals(listOf("March rent"), repo.search("", all, 100_000, null).map { it.note })          // amount filter
    }

    @Test fun foreignCurrencyAccountKeepsOwnBalanceAndReportsInMain() = runTest {
        repo.saveCurrency(Currency("USD", "$", 2, 80.0))
        repo.saveAccount(Account("acc_usd", "grp_bank", "USD Account", currency = "USD"))
        repo.create(TxnDraft(DraftType.INCOME, today, "acc_usd", amount = 10_000, currency = "USD"))     // $100.00
        assertEquals(10_000L, repo.balances.first()["acc_usd"])                                         // account currency
        assertEquals(800_000L, totals(repo.reportRows(march).first()).income)                            // ₹8,000.00
        repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_usd", toAccountId = "acc_bank", amount = 5_000, currency = "USD"))
        assertEquals(5_000L, repo.balances.first()["acc_usd"])
        assertEquals(400_000L, repo.balances.first()["acc_bank"])                                       // ₹4,000 arrived
    }

    @Test fun cardAtPaymentMovesExpenseToBillPayment() = runTest {
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_0", amount = 3_000))
        repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_card", amount = 3_000))
        assertEquals(3_000, totals(repo.reportRows(march).first()).expense)
        repo.putSetting(MoneyRepository.Keys.CARD_AT_PAYMENT, "1")
        assertEquals(3_000, totals(repo.reportRows(march).first()).expense)   // counted once, at payment
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_0", amount = 1_000))
        assertEquals(3_000, totals(repo.reportRows(march).first()).expense)   // unpaid purchase not yet expense
    }

    @Test fun importOwnExportRoundTrips() = runTest {
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 12_345, note = "Lunch, with \"team\""))
        repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_savings", amount = 50_000))
        val rows = TabularImport.exportRows(repo.allTxns(), repo.accounts.first(), repo.categories.first(), CurrencyFormat(), emptyList(), emptyMap())
        val csv = TabularImport.toCsv(rows)
        val result = repo.importRows(TabularImport.parseCsv(csv))
        assertEquals(2, result.imported)
        assertEquals(0, result.createdAccounts)
        assertEquals(0, result.createdCategories)
        val lunches = repo.allTxns().filter { it.note == "Lunch, with \"team\"" }
        assertEquals(2, lunches.size)
        assertTrue(lunches.all { it.amount == 12_345L && it.categoryId == "cat_ex_0_1" })
    }

    @Test fun importSimpleSheetCreatesAccountsAndCategories() = runTest {
        val rows = listOf(
            listOf("Date", "Account", "Category", "Amount", "Note"),
            listOf("13/03/2026", "Wallet", "Snacks", "-45.50", "Chips"),
            listOf("2026-03-14", "Wallet", "Gifts", "100", "From aunt"),
            listOf("bad date", "Wallet", "", "1", ""),
        )
        val r = repo.importRows(rows)
        assertEquals(2, r.imported); assertEquals(1, r.skipped); assertEquals(1, r.createdAccounts)
        val t = totals(repo.reportRows(march).first())
        assertEquals(4_550, t.expense); assertEquals(10_000, t.income)
    }

    @Test fun categoryDefaultAccountPersistsAndSubcategoriesInherit() = runTest {
        val food = repo.categories.first().first { it.id == "cat_ex_0" }
        repo.saveCategory(food.copy(defaultAccountId = "acc_card"))
        val cats = repo.categories.first().associateBy { it.id }
        assertEquals("acc_card", cats.getValue("cat_ex_0").defaultAccountId)
        assertEquals("acc_card", app.moneytracker.domain.defaultAccountFor("cat_ex_0_1", cats))   // "Eating out" inherits
        repo.saveCategory(cats.getValue("cat_ex_0_1").copy(defaultAccountId = "acc_cash"))
        val cats2 = repo.categories.first().associateBy { it.id }
        assertEquals("acc_cash", app.moneytracker.domain.defaultAccountFor("cat_ex_0_1", cats2))  // own default wins
        assertEquals(null, app.moneytracker.domain.defaultAccountFor("cat_ex_1", cats2))          // none set
    }

    @Test fun weekendShiftMovesPeriodStart() {
        // 2026-03-25 is a Wednesday; 2026-04-25 is a Saturday.
        val before = Periods.month(YearMonth(2026, 5), 25, Periods.WeekendShift.BEFORE)
        assertEquals(LocalDate.parse("2026-04-24"), before.start)
        val after = Periods.month(YearMonth(2026, 5), 25, Periods.WeekendShift.AFTER)
        assertEquals(LocalDate.parse("2026-04-27"), after.start)
    }

    @Test fun tagsAndTemplatesAndNotesPersist() = runTest {
        val tag = repo.tagNamed("Trip")
        val id = repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", amount = 100, tagIds = listOf(tag)))
        assertEquals(listOf(tag), repo.tagsOf(id))
        assertEquals(listOf("Trip"), repo.txnTagNames.first()[id])
        assertEquals(1, repo.search("trip", all, null, null).size)
        assertEquals(tag, repo.tagNamed("trip"))   // case-insensitive reuse
    }

    companion object {
        private val V1_DDL = listOf(
            "CREATE TABLE AccountGroup (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, sortOrder INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE Account (id TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, name TEXT NOT NULL, includeInTotals INTEGER NOT NULL DEFAULT 1, transferAsExpense INTEGER NOT NULL DEFAULT 0, cardSettlementDay INTEGER, cardPaymentAccountId TEXT, sortOrder INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE Category (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, parentId TEXT, sortOrder INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE Txn (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, date INTEGER NOT NULL, createdAt INTEGER NOT NULL, accountId TEXT NOT NULL, toAccountId TEXT, categoryId TEXT, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', memo TEXT NOT NULL DEFAULT '', transferGroupId TEXT, installmentGroupId TEXT, installmentLabel TEXT, recurringRuleId TEXT, deleted INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE BudgetAmount (categoryId TEXT NOT NULL, period INTEGER NOT NULL, amount INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY (categoryId, period))",
            "CREATE TABLE RecurringRule (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, accountId TEXT NOT NULL, toAccountId TEXT, categoryId TEXT, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', repeat TEXT NOT NULL, startDate INTEGER NOT NULL, nextDate INTEGER NOT NULL, endDate INTEGER, deleted INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)",
            "CREATE TABLE Setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        )
    }
}

class SmsLearningTest {
    @Test fun inboxSuggestsAndLearnsFromCorrections() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        val repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-10-13") })
        repo.seedIfEmpty()
        val sms = "Rs.250.00 spent on HDFC Bank Card x1234 at SWIGGY on 2026-10-12."
        val first = repo.ingestMessage("SMS", "VM-HDFCBK", sms, 1_000_000)!!
        val d1 = repo.draftFromCapture(first)
        assertEquals(25_000, d1.amount); assertEquals("Swiggy", d1.note); assertEquals(null, d1.categoryId)
        // User files it under Food › Eating out on the credit card.
        repo.completeCapture(first, d1.copy(categoryId = "cat_ex_0_1", accountId = "acc_card"), "t1")
        // Same text within 2 minutes is a duplicate; a later one is new.
        assertEquals(null, repo.ingestMessage("SMS", "VM-HDFCBK", sms, 1_060_000))
        val second = repo.ingestMessage("SMS", "VM-HDFCBK", sms.replace("250.00", "410.00"), 9_000_000)!!
        val d2 = repo.draftFromCapture(second)
        // Sub-categories are off by default, so the learned "Eating out" is suggested as its main category.
        assertEquals("cat_ex_0", d2.categoryId)
        assertEquals("acc_card", d2.accountId)
        repo.putSetting(MoneyRepository.Keys.USE_SUBCATEGORIES, "1")
        assertEquals("cat_ex_0_1", repo.draftFromCapture(second).categoryId)
    }
}
