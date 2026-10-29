package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SmsAccountMatchingTest {
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() = runTest { resetRepo() }

    private suspend fun resetRepo() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { LocalDate.parse("2026-04-20") })
        repo.seedIfEmpty()
        val accounts = repo.accounts.first()
        repo.saveAccount(accounts.first { it.id == "acc_bank" }.copy(name = "Slice account"))
        repo.saveAccount(accounts.first { it.id == "acc_card" }.copy(name = "Slice credit card"))
    }

    private fun capture(text: String) = repo.ingestMessage("SMS", "AD-SLICE", text, 1_776_600_000_000)!!

    @Test fun creditCardSpendSelectsCardNotSameBankAccount() {
        val c = capture("Rs. 250 spent on your credit card xx1234 at SHOP on 28-oct-26 (UPI ref: 123456789012). Not you? Call 1800000000 - slice")
        assertEquals("acc_card", c.accountId)
        assertEquals("acc_card", repo.draftFromCapture(c).accountId)
    }

    @Test fun creditCardMatchingWorksAcrossBanks() = runTest {
        for ((sender, bank) in listOf("AD-HDFCBK" to "HDFC Bank", "AD-ICICIB" to "ICICI Bank", "AD-AXISBK" to "Axis Bank")) {
            val accounts = repo.accounts.first()
            repo.saveAccount(accounts.first { it.id == "acc_bank" }.copy(name = "$bank account"))
            repo.saveAccount(accounts.first { it.id == "acc_card" }.copy(name = "$bank credit card"))
            val c = repo.ingestMessage("SMS", sender, "Rs.250 spent on your credit card xx1234 at SHOP.", 1)!!
            assertEquals("acc_card", c.accountId, bank)
        }
    }

    @Test fun bankDebitSelectsBankEvenWhenCardComesFirst() = runTest {
        val accounts = repo.accounts.first()
        repo.saveAccount(accounts.first { it.id == "acc_card" }.copy(sortOrder = -1))
        assertEquals("acc_bank", capture("Rs.250 debited from your slice account xx5678 to SHOP.").accountId)
    }

    @Test fun matchingDigitsBeatEarlierSameBankName() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(name = "Slice 1234"))
        assertEquals("acc_card", capture("Rs.250 spent on slice card x1234 at SHOP.").accountId)
    }

    @Test fun bankOnlyAmbiguityDoesNotGuessOrAutoSave() {
        repo.putSetting(MoneyRepository.Keys.SMS_AUTO_SAVE, "1")
        val c = capture("Rs.250 spent at SHOP via slice.")
        assertEquals(null, c.accountId)
        assertEquals(app.moneytracker.domain.CaptureStatus.PENDING, c.status)
        assertEquals("", repo.draftFromCapture(c).accountId)
    }

    @Test fun unrelatedBankLearningCannotHijackAnotherBanksCard() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(id = "hdfc_card", name = "HDFC credit card"))
        repo.saveAccount(card.copy(id = "icici_card", name = "ICICI credit card"))
        val old = SmsParser.parse("AD-HDFCBK", "Rs.250 spent on credit card xx5678 at FIRSTSHOP.")!!
        repo.learn(SmsParser.tokens(old), null, "hdfc_card")
        repo.putSetting(MoneyRepository.Keys.SMS_AUTO_SAVE, "1")
        val c = repo.ingestMessage("SMS", "AD-ICICIT", "Rs.350 spent on credit card xx1234 at SECONDSHOP.", 1)!!
        assertEquals("icici_card", c.accountId)
        assertEquals(0, repo.accountTxns("hdfc_card").size)
        assertEquals(1, repo.accountTxns("icici_card").size)
    }

    @Test fun genericCardCanBelongToBankAccountWithSenderRuleOrLearning() = runTest {
        val bank = repo.accounts.first().first { it.id == "acc_bank" }
        repo.saveAccount(bank.copy(smsSender = "SLICE"))
        val text = "Rs.250 spent using card xx5678 at SHOP. - slice"
        assertEquals("acc_bank", capture(text).accountId)
        repo.saveAccount(bank.copy(smsSender = ""))
        repo.learn(SmsParser.tokens(SmsParser.parse("AD-SLICE", text)!!), null, "acc_bank")
        assertEquals("acc_bank", capture(text.replace("250", "350")).accountId)
    }

    @Test fun compatibleDedicatedSenderBeatsAnotherBanksMatchingSuffix() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(name = "HDFC credit card", smsSender = "HDFCBK"))
        repo.saveAccount(card.copy(id = "icici_card", name = "ICICI credit card 1234"))
        val c = repo.ingestMessage("SMS", "AD-HDFCBK", "Rs.250 spent using credit card xx1234 at SHOP.", 1)!!
        assertEquals("acc_card", c.accountId)
    }

    @Test fun unknownBankDoesNotLearnIdentityFromInstrumentAlone() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(name = "Travel card"))
        repo.learn(SmsParser.tokens(SmsParser.parse("AD-HDFCBK", "Rs.250 spent on credit card xx5678 at FIRSTSHOP.")!!), null, "acc_card")
        val c = repo.ingestMessage("SMS", "AD-ICICIT", "Rs.350 spent on credit card xx1234 at SECONDSHOP.", 1)!!
        assertEquals(null, c.accountId)
    }

    @Test fun bankLearningCannotOverrideExplicitCreditCard() {
        val old = "Rs.250 debited from slice account xx5678 at SHOP."
        repo.learn(SmsParser.tokens(SmsParser.parse("AD-SLICE", old)!!), null, "acc_bank")
        val c = capture("Rs.250 spent on slice credit card x1234 at SHOP.")
        assertEquals("acc_card", c.accountId)
        assertEquals("acc_card", repo.draftFromCapture(c).accountId)
    }

    @Test fun sharedSenderDoesNotForceCreditCardSpendIntoBankAccount() = runTest {
        val accounts = repo.accounts.first()
        for (a in accounts.filter { it.id == "acc_bank" || it.id == "acc_card" }) {
            repo.saveAccount(a.copy(smsSender = "SLICE"))
        }
        assertEquals("acc_card", capture("Rs.250 spent on your credit card xx1234 at SHOP. - slice").accountId)
    }

    @Test fun payingCreditCardBillFromBankStillSelectsBank() {
        assertEquals("acc_bank", capture("Rs.250 debited from slice account xx5678 towards credit card payment.").accountId)
    }

    @Test fun explicitAccountRuleStillWins() = runTest {
        val bank = repo.accounts.first().first { it.id == "acc_bank" }
        repo.saveAccount(bank.copy(smsMatch = "x1234"))
        assertEquals("acc_bank", capture("Rs.250 spent on slice credit card x1234 at SHOP.").accountId)
    }

    @Test fun routesPublicMultiBankFormatsWithBankCreditAndDebitAccountsPresent() = runTest {
        for (example in PublicSmsFixtures.examples) {
            resetRepo()
            val accounts = repo.accounts.first()
            val bank = accounts.first { it.id == "acc_bank" }
            val card = accounts.first { it.id == "acc_card" }
            fun name(type: app.moneytracker.domain.AccountType): String =
                "${example.bank} ${type.name} ${if (type == example.target) example.digits.orEmpty() else "9999"}"
            repo.saveAccount(bank.copy(name = name(app.moneytracker.domain.AccountType.BANK)))
            repo.saveAccount(card.copy(name = name(app.moneytracker.domain.AccountType.CREDIT_CARD)))
            repo.saveAccount(bank.copy(id = "acc_debit", groupId = "grp_debit_card",
                name = name(app.moneytracker.domain.AccountType.DEBIT_CARD)))
            val expectedId = when (example.target) {
                app.moneytracker.domain.AccountType.BANK -> "acc_bank"
                app.moneytracker.domain.AccountType.CREDIT_CARD -> "acc_card"
                app.moneytracker.domain.AccountType.DEBIT_CARD -> "acc_debit"
                else -> null
            }
            val c = repo.ingestMessage("SMS", example.sender, example.text, 1_776_600_000_000)!!
            assertEquals(expectedId, c.accountId, "${example.name}: ${example.source}")
            assertEquals(expectedId.orEmpty(), repo.draftFromCapture(c).accountId, example.name)
            assertEquals(if (example.refund) -example.amount else example.amount, c.amount, example.name)
        }
    }

    @Test fun debitCardSpendDoesNotSelectSameBankCreditCard() = runTest {
        val bank = repo.accounts.first().first { it.id == "acc_bank" }
        repo.saveAccount(bank.copy(id = "acc_debit", groupId = "grp_debit_card", name = "Slice debit card"))
        assertEquals("acc_debit", capture("Rs.250 spent on slice debit card xx5678 at SHOP.").accountId)
    }

    @Test fun multipleCreditCardsNeedDigitsOrReview() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(id = "acc_card_two", name = "Slice second credit card"))
        val c = capture("Rs.250 spent on slice credit card xx1234 at SHOP.")
        assertEquals(null, c.accountId)
        assertEquals("", repo.draftFromCapture(c).accountId)
    }

    @Test fun digitMatchMustBeWholeSuffixNotPartOfLongerNumber() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(id = "acc_card_two", name = "Card 912345", sortOrder = -1))
        repo.saveAccount(card.copy(name = "Card 1234"))
        assertEquals("acc_card", capture("Rs.250 spent at SHOP. Card xx1234.").accountId)
    }

    @Test fun knownDigitsBeatLearningFromAnotherInstrument() = runTest {
        val card = repo.accounts.first().first { it.id == "acc_card" }
        repo.saveAccount(card.copy(name = "Slice 1234"))
        val old = "Rs.250 debited from slice account xx5678 at SHOP."
        repo.learn(SmsParser.tokens(SmsParser.parse("AD-SLICE", old)!!), null, "acc_bank")
        assertEquals("acc_card", capture("Rs.250 spent on slice card x1234 at SHOP.").accountId)
    }
}
