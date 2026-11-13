package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.db.MoneyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Upgrading a database made by an older version keeps its data and adds the new tables and columns. */
class SchemaMigrationTest {
    @Test fun migrationFromV1KeepsDataAndAddsTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        V1_DDL.forEach { driver.execute(null, it, 0) }
        driver.execute(null, "INSERT INTO Txn(id,kind,date,createdAt,accountId,amount,updatedAt) VALUES ('t1','EXPENSE',20527,0,'a',500,0)", 0)
        MoneyDatabase.Schema.migrate(driver, 1, MoneyDatabase.Schema.version)
        val db = MoneyDatabase(driver)
        assertEquals(500, db.moneyQueries.allTxns().executeAsList().single().amount)
        assertTrue(db.moneyQueries.allCurrencies().executeAsList().isEmpty())   // new table exists
        assertEquals(7L, MoneyDatabase.Schema.version)
        assertEquals(null, db.moneyQueries.allTxns().executeAsList().single().rate)                         // v7 column exists
        assertEquals(null, db.moneyQueries.allCategories().executeAsList().firstOrNull()?.defaultAccountId)   // v3 column exists
        assertEquals(null, db.moneyQueries.allTxns().executeAsList().single().timeOfDay)                    // v4 column exists
        driver.execute(null, "INSERT INTO Account(id,groupId,name,sortOrder,updatedAt) VALUES ('a','g','A',0,0)", 0)
        assertEquals("", db.moneyQueries.allAccounts().executeAsList().single().memo)                        // v5 columns exist
        assertEquals(1L, db.moneyQueries.allAccounts().executeAsList().single().usageTargetType)
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
