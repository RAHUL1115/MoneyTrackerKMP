package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.cardUsage
import app.moneytracker.domain.cardStatus
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.TAG_USAGE_EXCEPTION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V5FeaturesTest {
    private val today = LocalDate.parse("2026-04-20")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    private fun spend(date: String, amount: Long) =
        repo.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse(date), "acc_card", categoryId = "cat_ex_0", amount = amount))

    @Test fun cardUsageMeasuresLastCycleOrThreeCycleAverage() {
        // Cycles start on the 1st: Apr is current, Mar/Feb/Jan are the previous three.
        spend("2026-04-02", 100); spend("2026-03-10", 300); spend("2026-02-10", 600); spend("2026-01-10", 900)
        val txns = repo.accountTxns("acc_card")
        assertEquals(300, cardUsage(txns, 1, today, averageOf3 = false).measured)
        assertEquals(600, cardUsage(txns, 1, today, averageOf3 = true).measured)
        assertEquals(100, cardUsage(txns, 1, today, averageOf3 = false).thisCycle)
    }

    @Test fun ruleDescriptionReachesGeneratedEntries() = runTest {
        repo.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-02-20"), "acc_bank", categoryId = "cat_ex_0", amount = 500,
            note = "Rent", memo = "Flat 4B", repeat = RepeatType.MONTHLY))
        val made = repo.accountTxns("acc_bank").filter { it.note == "Rent" }
        assertEquals(3, made.size)                                        // Feb, Mar, Apr
        assertTrue(made.all { it.memo == "Flat 4B" })
        assertEquals("Flat 4B", repo.rules.first().single().memo)
    }

    @Test fun accountKeepsMemoAndCardDetails() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(memo = "PIN hint", cardPaymentDay = 5, cardAutoPay = true, usageTarget = 1_000, usageTargetType = 2, hidden = true))
        val saved = repo.accounts.first().first { it.id == "acc_card" }
        assertEquals("PIN hint", saved.memo); assertEquals(5, saved.cardPaymentDay); assertTrue(saved.cardAutoPay)
        assertEquals(1_000L, saved.usageTarget); assertEquals(2, saved.usageTargetType); assertTrue(saved.hidden)
    }

    @Test fun systemTagsExistAndShapeCardMaths() = runTest {
        assertTrue(repo.tags.first().any { it.id == TAG_PREPAYMENT } && repo.tags.first().any { it.id == TAG_USAGE_EXCEPTION })
        val pre = spend("2026-04-05", 700)                                   // current cycle, but prepaid → due now
        spend("2026-04-06", 50)
        val skip = spend("2026-03-05", 200); spend("2026-03-06", 100)        // last cycle; one excluded from usage
        repo.setTags(pre, listOf(TAG_PREPAYMENT)); repo.setTags(skip, listOf(TAG_USAGE_EXCEPTION))
        val txns = repo.accountTxns("acc_card")
        val status = cardStatus(txns, 1, today, repo.txnIdsWithTag(TAG_PREPAYMENT))
        assertEquals(700, status.overdue); assertEquals(300, status.statementDue); assertEquals(50, status.currentCycle)
        assertEquals(100, cardUsage(txns, 1, today, false, repo.txnIdsWithTag(TAG_USAGE_EXCEPTION)).measured)
    }
}

class MessageRulesTest {
    private val today = LocalDate.parse("2026-04-20")
    private lateinit var repo: MoneyRepository
    private val sms = "Rs.250.00 spent on HDFC Bank Card x1234 at SWIGGY on 2026-04-19."

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    @Test fun blockedSendersAndWordsAreIgnored() {
        repo.putSetting(MoneyRepository.Keys.SMS_BLOCKED_SENDERS, "VM-PROMO")
        repo.putSetting(MoneyRepository.Keys.SMS_BLOCKED_PHRASES, "OTP")
        assertEquals(null, repo.ingestMessage("SMS", "VM-PROMO", sms, 1))
        assertEquals(null, repo.ingestMessage("SMS", "AD-HDFCBK", "$sms OTP 1234", 2))
        assertTrue(repo.ingestMessage("SMS", "AD-HDFCBK", sms, 3) != null)
    }

    @Test fun accountRuleFilesTheMessageAndAutoSaveKeepsTheOriginal() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(smsMatch = "x1234"))
        repo.putSetting(MoneyRepository.Keys.SMS_AUTO_SAVE, "1")
        val c = repo.ingestMessage("SMS", "AD-HDFCBK", sms, 1_776_600_000_000)!!
        assertEquals("acc_card", c.accountId)
        val saved = repo.accountTxns("acc_card").single()
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
