package app.moneytracker.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.db.MoneyDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class MessageRulesTest {
    private val today = LocalDate.parse("2026-04-20")
    private lateinit var repo: MoneyRepository
    private val sms = "Rs.250.00 spent on HDFC Bank Card x1234 at SWIGGY on 2026-04-19."

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @Test fun blockedSendersAndWordsAreIgnored() {
        repo.settingsStore.putSetting(Prefs.SMS_BLOCKED_SENDERS.key, "VM-PROMO")
        repo.settingsStore.putSetting(Prefs.SMS_BLOCKED_PHRASES.key, "OTP")
        assertEquals(null, repo.messageIntake.ingestMessage("SMS", "VM-PROMO", sms, 1))
        assertEquals(null, repo.messageIntake.ingestMessage("SMS", "AD-HDFCBK", "$sms OTP 1234", 2))
        assertTrue(repo.messageIntake.ingestMessage("SMS", "AD-HDFCBK", sms, 3) != null)
    }

    @Test fun accountRuleFilesTheMessageAndAutoSaveKeepsTheOriginal() = runTest {
        val card = repo.accountStore.accounts.first().first { it.id == "acc_card" }
        repo.accountStore.saveAccount(card.copy(smsMatch = "x1234"))
        repo.settingsStore.putSetting(Prefs.SMS_AUTO_SAVE.key, "1")
        // Auto-save needs a category too; this shop was filed under one before.
        val food = repo.categoryStore.categoriesNow().first { it.kind == app.moneytracker.domain.CategoryKind.EXPENSE && it.parentId == null }
        repo.learningStore.learn(app.moneytracker.domain.SmsParser.tokens(app.moneytracker.domain.SmsParser.parse("AD-HDFCBK", sms)!!), food.id, null)
        val c = repo.messageIntake.ingestMessage("SMS", "AD-HDFCBK", sms, 1_776_600_000_000)!!
        assertEquals("acc_card", c.accountId)
        val saved = repo.txnStore.accountTxns("acc_card").single()
        assertEquals(food.id, saved.categoryId)
        assertEquals(25_000L, saved.amount); assertEquals(sms, saved.origin)
    }

    @Test fun contentFilterAndMatching() {
        assertEquals("Spent Rs 10 at SHOP", app.moneytracker.domain.MessageRules.clean("Spent Rs 10 [promo] at SHOP", listOf("[promo]")))
        val a = app.moneytracker.domain.Account("a", "g", "A", smsMatch = "1234")
        val b = app.moneytracker.domain.Account("b", "g", "B", smsMatch = "x1234")
        val c = app.moneytracker.domain.Account("c", "g", "C", smsSender = "SBIINB")
        val all = listOf(a, b, c)
        assertEquals("b", app.moneytracker.domain.MessageRules.matchAccount(all, "X", "card x1234 used"))     // longest match wins
        assertEquals("c", app.moneytracker.domain.MessageRules.matchAccount(all, "VM-SBIINB", "debited"))
        assertEquals(null, app.moneytracker.domain.MessageRules.matchAccount(all, "Other", "debited"))
    }
}
