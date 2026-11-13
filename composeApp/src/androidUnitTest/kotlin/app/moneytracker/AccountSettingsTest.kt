package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** Settings saved on accounts and categories: hidden, order, memo, card details and default account. */
class AccountSettingsTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
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

    @Test fun hiddenAccountsAndOrdering() = runTest {
        repo.setAccountHidden("acc_cash", true)
        assertTrue(repo.accounts.first().first { it.id == "acc_cash" }.hidden)
        repo.reorderAccounts(listOf("acc_savings", "acc_card", "acc_bank", "acc_cash"))
        assertEquals("acc_savings", repo.accounts.first().first().id)
    }

    @Test fun accountKeepsMemoAndCardDetails() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(memo = "PIN hint", cardPaymentDay = 5, cardAutoPay = true, usageTarget = 1_000, usageTargetType = 2, hidden = true))
        val saved = repo.accounts.first().first { it.id == "acc_card" }
        assertEquals("PIN hint", saved.memo); assertEquals(5, saved.cardPaymentDay); assertTrue(saved.cardAutoPay)
        assertEquals(1_000L, saved.usageTarget); assertEquals(2, saved.usageTargetType); assertTrue(saved.hidden)
    }
}
