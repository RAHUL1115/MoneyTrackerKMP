package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.db.MoneyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

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
        repo.putSetting(Prefs.USE_SUBCATEGORIES.key, "1")
        assertEquals("cat_ex_0_1", repo.draftFromCapture(second).categoryId)
    }
}
