package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem
import app.moneytracker.data.InputOrder
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.NoteSuggestion
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.TxnKind
import app.moneytracker.ui.AccountGuess
import app.moneytracker.ui.EntryFormModel
import app.moneytracker.ui.EntrySaved
import app.moneytracker.ui.EntrySetup
import app.moneytracker.ui.Screen
import app.moneytracker.ui.SmartGuess
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The entry form both layouts draw: defaults, rules, checks, save and delete paths, and saved state. */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryFormModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun model(screen: Screen.EditTxn = Screen.EditTxn()) = EntryFormModel(
        repo.entryEditor, AccountGuess(repo.accountStore, repo.categoryStore, repo.learningStore), repo.categoryStore,
        repo.photoStore, repo.captureStore, repo.bookmarkStore,
        EntrySetup.read(screen, repo.settingsStore, CurrencyFormat(), emptyMap(), repo.today), Dispatchers.Unconfined, scope,
    )

    private fun expense(account: String = "acc_cash", category: String = "cat_ex_0", amount: Long = 25_000, note: String = "Lunch") =
        TxnDraft(DraftType.EXPENSE, today, account, categoryId = category, amount = amount, note = note)

    private fun EntryFormModel.saveNow(): EntrySaved? {
        var result: EntrySaved? = null
        save { result = it }
        return result
    }

    private fun EntryFormModel.fillExpense() {
        edit { it.copy(amountText = "250") }
        pickCategory(repo.categoryStore.categoryById.current.getValue("cat_ex_0"))
        pickFrom(repo.accountStore.accountNow("acc_cash")!!)
    }

    // ───────────── starting values

    @Test fun newEntryStartsOnTheUsualAccountWithoutCountingItAsChosen() {
        repeat(3) { repo.txnStore.create(expense(account = "acc_card")) }
        val f = model().form
        assertEquals("acc_card", f.accountId)
        assertFalse(f.accountChosen)
    }

    @Test fun accountFirstLeavesTheAccountToBePicked() {
        repeat(3) { repo.txnStore.create(expense(account = "acc_card")) }
        repo.settingsStore.putSetting(Prefs.INPUT_ORDER, InputOrder.ACCOUNT)
        assertNull(model().form.accountId)
    }

    @Test fun presetFillsTheForm() {
        val preset = TxnDraft(DraftType.INCOME, today, "acc_bank", toAccountId = "", amount = 12_345, note = "Salary")
        val f = model(Screen.EditTxn(preset = preset)).form
        assertEquals(DraftType.INCOME, f.type)
        assertEquals("acc_bank", f.accountId)
        assertTrue(f.accountChosen)
        assertNull(f.toAccountId)
        assertEquals("123.45", f.amountText)
        assertEquals("Salary", f.note)
    }

    // ───────────── checks

    @Test fun saveRefusesAnIncompleteFormUntilAFieldItChecksChanges() {
        val m = model()
        assertEquals(EntryProblem.NO_AMOUNT, m.save {})
        assertEquals(EntryProblem.NO_AMOUNT, m.form.problem)
        m.edit { it.copy(amountText = "120+30") }
        assertNull(m.form.problem)
        assertEquals(15_000, m.amountValue())                         // a half-typed sum counts as its result
        assertEquals(EntryProblem.NO_ACCOUNT, m.save {})
        m.edit { it.copy(note = "Taxi") }
        assertEquals(EntryProblem.NO_ACCOUNT, m.form.problem)         // the note isn't what was missing
        m.pickFrom(repo.accountStore.accountNow("acc_cash")!!)
        assertNull(m.form.problem)
        assertEquals(EntryProblem.NO_CATEGORY, m.save {})
        assertTrue(repo.txnStore.allTxns().isEmpty())
    }

    // ───────────── saving and deleting

    @Test fun newEntrySavesAndItsUndoTakesItBack() {
        val m = model()
        m.fillExpense()
        val saved = assertNotNull(m.saveNow())
        assertTrue(saved.isNew)
        assertEquals(25_000, repo.txnStore.allTxns().single().amount)
        assertNotNull(saved.undo).invoke()
        assertTrue(repo.txnStore.allTxns().isEmpty())
    }

    @Test fun repeatingEntriesCannotBeUndoneFromTheToast() {
        val m = model()
        m.fillExpense()
        m.edit { it.copy(repeat = app.moneytracker.domain.RepeatType.MONTHLY) }
        assertNull(assertNotNull(m.saveNow()).undo)
    }

    @Test fun editingLoadsTheEntryAndSavesItInPlace() {
        val id = repo.txnStore.create(expense(account = "acc_card", note = "Pizza"))
        val m = model(Screen.EditTxn(txnId = id))
        assertEquals("Pizza", m.form.note)
        assertEquals("acc_card", m.form.accountId)
        assertTrue(m.form.accountChosen)
        assertEquals("250", m.form.amountText)
        m.edit { it.copy(note = "Pasta") }
        val saved = assertNotNull(m.saveNow())
        assertFalse(saved.isNew)
        assertNull(saved.undo)
        assertEquals("Pasta", repo.txnStore.allTxns().single().note)
    }

    @Test fun balanceAdjustmentSavesWithoutACategoryUntilATypeIsPicked() {
        repo.txnStore.adjustBalance("acc_cash", 50_000)
        val adj = repo.txnStore.allTxns().single()
        val m = model(Screen.EditTxn(txnId = adj.id))
        assertTrue(m.form.isAdjustment)
        m.edit { it.copy(amountText = "700") }
        assertNotNull(m.saveNow())
        assertEquals(70_000, repo.accountStore.balancesNow()["acc_cash"])
        val again = model(Screen.EditTxn(txnId = repo.txnStore.allTxns().single().id))
        again.pickType(DraftType.EXPENSE)
        assertFalse(again.form.isAdjustment)
        assertEquals(MODIFIED_BALANCE_CATEGORY, again.form.categoryId)
    }

    @Test fun deleteRemovesTheEntry() {
        val id = repo.txnStore.create(expense())
        var done = false
        model(Screen.EditTxn(txnId = id)).delete { done = true }
        assertTrue(done)
        assertTrue(repo.txnStore.allTxns().isEmpty())
    }

    @Test fun bookmarkKeepsTheSettledAmount() {
        val m = model()
        m.fillExpense()
        m.edit { it.copy(amountText = "100+50") }
        val t = m.saveBookmark("  Lunch  ")
        assertEquals("Lunch", t.name)
        assertEquals(15_000, repo.bookmarkStore.templates.current.single().amount)
    }

    // ───────────── field rules

    @Test fun pickingATypeClearsTheCategoryButClassicKeepsItFromRefundToExpense() {
        val m = model()
        m.pickType(DraftType.INCOME)
        m.toggleRefund()
        m.edit { it.copy(categoryId = "cat_ex_0") }
        m.pickType(DraftType.INCOME)                                  // a refund already sits on Income
        assertEquals(DraftType.REFUND, m.form.type)
        m.pickType(DraftType.EXPENSE, keepRefundCategory = true)
        assertEquals("cat_ex_0", m.form.categoryId)
        m.pickType(DraftType.TRANSFER)
        assertNull(m.form.categoryId)
    }

    @Test fun categoryBringsItsUsualAccountUnlessOneWasChosen() {
        repeat(3) { repo.txnStore.create(expense(account = "acc_card", category = "cat_ex_1")) }
        val cat = repo.categoryStore.categoryById.current.getValue("cat_ex_1")
        val m = model()
        m.pickCategory(cat)
        assertEquals("acc_card", m.form.accountId)
        m.pickFrom(repo.accountStore.accountNow("acc_bank")!!)
        m.pickCategory(cat)
        assertEquals("acc_bank", m.form.accountId)
    }

    @Test fun transferAccountsNeverMatchAndSwap() {
        val m = model()
        m.pickType(DraftType.TRANSFER)
        m.pickTo(repo.accountStore.accountNow("acc_bank")!!)
        m.pickFrom(repo.accountStore.accountNow("acc_bank")!!)
        assertNull(m.form.toAccountId)
        m.pickTo(repo.accountStore.accountNow("acc_cash")!!)
        m.swapAccounts()
        assertEquals("acc_cash", m.form.accountId)
        assertEquals("acc_bank", m.form.toAccountId)
    }

    @Test fun pastNoteBringsBackCategoryAccountAndAmount() {
        val m = model()
        m.pickNote(NoteSuggestion("Fuel", "cat_ex_1", "acc_card", TxnKind.EXPENSE, 4_000, null))
        assertEquals("Fuel", m.form.note)
        assertEquals("cat_ex_1", m.form.categoryId)
        assertEquals("acc_card", m.form.accountId)
        assertEquals("40", m.form.amountText)
        m.pickNote(NoteSuggestion("Salary", "cat_in_0", "acc_bank", TxnKind.INCOME, 9_000, null))
        assertEquals("cat_ex_1", m.form.categoryId)                   // an income category doesn't fit an expense
        assertEquals("acc_card", m.form.accountId)                   // nor does it replace a chosen account
        assertEquals("40", m.form.amountText)
    }

    @Test fun smartEntryFillsTheFormAndAGuessedAccountStaysADefault() {
        val m = model()
        val d = TxnDraft(DraftType.REFUND, today, "acc_card", categoryId = "cat_ex_0", amount = 1_200, note = "Shoes")
        m.fillFrom(SmartGuess(d, null, emptyList(), null, 0, accountGuessed = true))
        assertEquals(DraftType.REFUND, m.form.type)
        assertNull(m.form.categoryId)
        assertEquals("12", m.form.amountText)
        assertEquals("acc_card", m.form.accountId)
        assertFalse(m.form.accountChosen)
    }

    @Test fun savedPhotosLeaveOnlyOnSaveAndNewOnesGoAtOnce() {
        val id = repo.entryEditor.save(expense(), photos = listOf("a.jpg"))
        val m = model(Screen.EditTxn(txnId = id))
        assertEquals(listOf("a.jpg"), m.form.photoFiles)
        m.edit { it.copy(newPhotos = it.newPhotos + "b.jpg") }
        assertFalse(m.removePhoto("b.jpg"))
        assertTrue(m.removePhoto("a.jpg"))
        assertTrue(m.form.photoFiles.isEmpty())
        assertEquals(1, repo.photoStore.photosNow(id).size)
    }

    @Test fun installmentsOnlyForCardExpenses() {
        val m = model()
        m.pickFrom(repo.accountStore.accountNow("acc_card")!!)
        assertTrue(m.canInstall)
        m.pickFrom(repo.accountStore.accountNow("acc_cash")!!)
        assertFalse(m.canInstall)
    }

    // ───────────── unsaved changes, next entry, saved state

    @Test fun dirtyOnceChangedAndCleanAgainAfterSaveAndNew() {
        val m = model()
        m.takeBaseline()
        assertFalse(m.isDirty)
        m.fillExpense()
        assertTrue(m.isDirty)
        m.saveNow()
        m.startNext(usualAccount = true)
        assertFalse(m.isDirty)
        assertEquals("", m.form.amountText)
        assertNull(m.form.categoryId)
    }

    @Test fun whatWasTypedComesBackAfterTheAppIsClosed() {
        val m = model()
        m.fillExpense()
        m.edit { it.copy(note = "Dinner: 3 people", tagIds = listOf("t1")) }
        m.takeBaseline()
        m.edit { it.copy(memo = "late") }
        val back = model().apply { restoreState(m.saveState()) }
        assertEquals(m.form, back.form)
        assertTrue(back.isDirty)
        val untouched = model().apply { restoreState("not a saved form") }
        assertEquals(model().form, untouched.form)
    }

    @Test fun copyAndRefundStartFromTheEntry() {
        val id = repo.txnStore.create(expense(account = "acc_card").copy(date = LocalDate.parse("2026-03-01")))
        val m = model(Screen.EditTxn(txnId = id))
        assertEquals(LocalDate.parse("2026-03-01"), m.copyDraft(withTodaysDate = false)?.date)
        assertEquals(today, m.copyDraft(withTodaysDate = true)?.date)
        assertTrue(m.form.canRecordRefund)
        val refund = m.refundDraft()
        assertEquals(DraftType.REFUND, refund.type)
        assertEquals(25_000, refund.amount)
        assertEquals("acc_card", refund.accountId)
        assertNull(model().copyDraft(withTodaysDate = true))
    }
}
