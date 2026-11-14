package app.moneytracker

import androidx.compose.runtime.saveable.SaverScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Account
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.accounts.AccountEditModel
import app.moneytracker.ui.accounts.AccountForm
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The account form of both layouts: filling it, its checks, saving and deleting. */
class AccountEditModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private val fmt = CurrencyFormat()
    private lateinit var repo: MoneyRepository
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun model(id: String?) = AccountEditModel(id, repo.accountStore, repo.currencyStore, repo.io, scope)
    private fun stored(id: String): Account = repo.accountStore.accountsNow().first { it.id == id }

    @Test fun aNewFormStartsFilledAndGoesLast() {
        val form = model(null).blankForm()
        assertTrue(form.loaded)
        assertEquals(4, form.sortOrder)
        assertNull(model(null).filled(form, fmt))
    }

    @Test fun anExistingFormFillsFromTheStoredAccount() {
        repo.accountStore.saveAccount(stored("acc_card").copy(cardLimit = 50_000, usageTarget = 12_345, usageTargetType = 2, memo = "Lounge"))
        val m = model("acc_card")
        val blank = m.blankForm()
        assertFalse(blank.loaded)

        val form = assertNotNull(m.filled(blank, fmt))
        assertEquals("Credit Card", form.name)
        assertEquals("grp_credit_card", form.groupId)
        assertEquals("acc_bank", form.paymentAccountId)
        assertEquals("500", form.limitText)
        assertEquals("123.45", form.targetText)
        assertEquals(2, form.targetType)
        assertEquals("Lounge", form.memo)
        // Filled once: later changes to the account don't overwrite what's typed.
        assertNull(m.filled(form, fmt))
    }

    @Test fun theFormIsCheckedBeforeSaving() {
        val m = model(null)
        val card = AccountForm(loaded = true, name = "Visa", groupId = "grp_credit_card")
        assertEquals("Choose a group", m.problem(card.copy(groupId = null)))
        assertEquals("Enter a name", m.problem(card.copy(name = " ")))
        assertEquals("Billing day must be 1–31", m.problem(card.copy(settlementDay = "32")))
        assertEquals("Payment day must be 1–31", m.problem(card.copy(paymentDay = "")))
        assertNull(m.problem(card))
        // Days only matter on credit cards.
        assertNull(m.problem(card.copy(groupId = "grp_bank", settlementDay = "")))
    }

    @Test fun savingANewCreditCardKeepsItsCardFields() = runTest {
        val m = model(null)
        var saved: Account? = null
        val form = m.blankForm().copy(
            name = " Visa ", groupId = "grp_credit_card", settlementDay = "5", paymentDay = "20", paymentAccountId = "acc_bank",
            limitText = "1000", autoPay = true, smsMatch = " x1234 ",
        )
        assertNull(m.save(form, fmt) { saved = it })

        val a = stored(requireNotNull(saved).id)
        assertEquals("Visa", a.name)
        assertEquals(5, a.cardSettlementDay)
        assertEquals(20, a.cardPaymentDay)
        assertEquals("acc_bank", a.cardPaymentAccountId)
        assertEquals(100_000L, a.cardLimit)
        assertTrue(a.cardAutoPay)
        assertEquals("x1234", a.smsMatch)
        assertEquals(4, a.sortOrder)
    }

    @Test fun movingACardToAnotherGroupDropsItsCardFields() = runTest {
        val m = model("acc_card")
        val form = assertNotNull(m.filled(m.blankForm(), fmt)).copy(groupId = "grp_bank", limitText = "10")
        assertNull(m.save(form, fmt) {})

        val a = stored("acc_card")
        assertEquals("grp_bank", a.groupId)
        assertNull(a.cardSettlementDay)
        assertNull(a.cardPaymentAccountId)
        assertNull(a.cardLimit)
        assertFalse(a.cardAutoPay)
    }

    @Test fun aFormWithAProblemIsNotSaved() = runTest {
        val m = model(null)
        var saved = false
        assertEquals("Enter a name", m.save(m.blankForm().copy(groupId = "grp_cash"), fmt) { saved = true })
        assertFalse(saved)
        assertEquals(4, repo.accountStore.accountsNow().size)
    }

    @Test fun deletingCanBeUndone() = runTest {
        val m = model("acc_cash")
        var deleted = false
        m.delete { deleted = true }
        assertTrue(deleted)
        assertFalse(repo.accountStore.accountsNow().any { it.id == "acc_cash" })

        m.restore()
        assertTrue(repo.accountStore.accountsNow().any { it.id == "acc_cash" })
    }

    @Test fun aHalfTypedFormSurvivesSavedState() {
        val form = AccountForm(loaded = true, name = "Wallet: 1", groupId = null, currency = "USD", targetType = 2, memo = "line\nbreak")
        val saver = AccountForm.Saver
        val text = assertNotNull(with(saver) { SaverScope { true }.save(form) })
        assertEquals(form, saver.restore(text))
    }
}
