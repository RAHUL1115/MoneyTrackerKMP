package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one save path shared by both phone layouts and the web dashboard. */
class EntryEditorTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private lateinit var editor: EntryEditor
    private var changes = 0

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
        editor = EntryEditor(repo) { changes++ }
    }

    private fun expense(amount: Long = 25_000, account: String = "acc_cash", note: String = "Lunch") =
        TxnDraft(DraftType.EXPENSE, today, account, categoryId = "cat_ex_0", amount = amount, note = note)

    // ───────────── checks

    @Test fun checksComeInTheOrderTheFormShowsThem() {
        fun check(type: DraftType = DraftType.EXPENSE, amount: Long? = 100, fee: Long? = 0, account: String? = "acc_cash",
                  to: String? = null, category: String? = "cat_ex_0", adjustment: Boolean = false) =
            EntryEditor.check(type, amount, fee, account, to, category, adjustment)

        assertNull(check())
        assertEquals(EntryProblem.NO_AMOUNT, check(amount = null, account = null))
        assertEquals(EntryProblem.NO_AMOUNT, check(amount = 0))
        assertEquals(EntryProblem.BAD_FEE, check(fee = null))
        assertEquals(EntryProblem.NO_ACCOUNT, check(account = null, category = null))
        assertEquals(EntryProblem.NO_CATEGORY, check(category = null))
        assertNull(check(category = null, adjustment = true))                       // balance corrections have no category
        assertNull(check(DraftType.TRANSFER, category = null, to = "acc_bank"))       // nor do transfers
        assertEquals(EntryProblem.NO_DESTINATION, check(DraftType.TRANSFER, category = null))
        assertEquals(EntryProblem.SAME_ACCOUNT, check(DraftType.TRANSFER, to = "acc_cash"))
    }

    // ───────────── create

    @Test fun newEntryKeepsPhotosLearnsAndRefreshes() {
        val id = editor.save(expense(note = "Pizza at Dominos"), photos = listOf("a.jpg", "b.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg"), repo.photosNow(id).map { it.fileName }.sorted())
        assertEquals("cat_ex_0", repo.suggest(app.moneytracker.domain.SmsParser.words("Pizza"))?.categoryId)
        assertEquals(1, changes)
    }

    @Test fun repeatRulesGetNoPhotos() = runTest {
        val ruleId = editor.save(expense().copy(repeat = RepeatType.MONTHLY), photos = listOf("a.jpg"))
        assertTrue(repo.rules.first().any { it.id == ruleId })
        assertTrue(repo.allTxns().none { repo.photosNow(it.id).isNotEmpty() })
    }

    @Test fun instalmentsOnlyOnCardExpenses() {
        val card = editor.save(expense(30_000, "acc_card").copy(installments = 3))
        assertEquals(3, repo.installmentGroup(repo.txn(card)!!.installmentGroupId!!).size)
        val cash = editor.save(expense(30_000, "acc_cash").copy(installments = 3))
        assertNull(repo.txn(cash)!!.installmentGroupId)
    }

    @Test fun savingADetectedSmsMarksItSaved() = runTest {
        val c = repo.ingestMessage("SMS", "AD-HDFCBK", "Rs.250.00 debited from a/c XX1234 at SWIGGY on 10-03-26", 1_773_000_000_000)!!
        val id = editor.save(repo.draftFromCapture(c).copy(categoryId = "cat_ex_0", accountId = "acc_bank"), capture = c)
        assertTrue(repo.pendingCaptures.first().none { it.id == c.id })
        assertNotNull(repo.liveTxn(id))
    }

    // ───────────── edit

    @Test fun openBringsBackLocationAndPhotos() {
        val id = editor.save(expense().copy(lat = 12.9, lng = 77.6), photos = listOf("a.jpg"))
        val opened = editor.open(id)!!
        assertEquals(12.9, opened.draft.lat); assertEquals(77.6, opened.draft.lng)
        assertEquals(listOf("a.jpg"), opened.photos.map { it.fileName })
        assertEquals(false, opened.isAdjustment)
    }

    @Test fun editKeepsPhotosLocationAndAddsNewOnes() {
        val id = editor.save(expense().copy(lat = 12.9, lng = 77.6), photos = listOf("a.jpg"))
        val opened = editor.open(id)!!
        val newId = editor.save(opened.draft.copy(amount = 30_000, note = "Lunch x2"), existing = opened.txn, photos = opened.photos.map { it.fileName } + "b.jpg")
        assertNotEquals(id, newId)                                    // update() rewrites the rows
        val t = repo.txn(newId)!!
        assertEquals(30_000, t.amount); assertEquals(12.9, t.lat)
        assertEquals(listOf("a.jpg", "b.jpg"), repo.photosNow(newId).map { it.fileName }.sorted())
        assertNull(repo.liveTxn(id))
    }

    @Test fun photoRemovedInTheFormIsGoneAfterSave() {
        val id = editor.save(expense(), photos = listOf("a.jpg", "b.jpg"))
        val opened = editor.open(id)!!
        val newId = editor.save(opened.draft, existing = opened.txn, photos = listOf("b.jpg"))
        assertEquals(listOf("b.jpg"), repo.photosNow(newId).map { it.fileName })
    }

    @Test fun withoutAPhotoListAnEditKeepsThePhotos() {
        val id = editor.save(expense(), photos = listOf("a.jpg"))
        val opened = editor.open(id)!!
        val newId = editor.save(opened.draft.copy(note = "Web edit"), existing = opened.txn)
        assertEquals(listOf("a.jpg"), repo.photosNow(newId).map { it.fileName })
    }

    @Test fun editingNeverTurnsAnEntryIntoARepeat() = runTest {
        val id = editor.save(expense())
        val opened = editor.open(id)!!
        editor.save(opened.draft.copy(repeat = RepeatType.MONTHLY), existing = opened.txn)
        assertTrue(repo.rules.first().isEmpty())
    }

    @Test fun editingATransferKeepsItsFee() {
        val id = editor.save(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_cash", amount = 5_000, fee = 50))
        val opened = editor.open(id)!!
        assertEquals(50, opened.draft.fee)
        val newId = editor.save(opened.draft.copy(amount = 6_000), existing = opened.txn)
        val group = repo.transferGroup(repo.txn(newId)!!.transferGroupId!!)
        assertEquals(50L, group.first { it.kind == TxnKind.EXPENSE }.amount)
        assertEquals(6_000L, group.first { it.kind == TxnKind.TRANSFER_OUT }.amount)
    }

    @Test fun balanceCorrectionsAreEditedInPlace() {
        repo.adjustBalance("acc_cash", 10_000)
        val adj = repo.allTxns().single { it.kind == TxnKind.ADJUST_UP }
        val opened = editor.open(adj.id)!!
        assertTrue(opened.isAdjustment)
        editor.saveAdjustment(opened.txn, today, 4_000, "acc_cash", up = false, note = "Fixed", memo = "")
        val t = repo.txn(adj.id)!!
        assertEquals(TxnKind.ADJUST_DOWN, t.kind); assertEquals("Fixed", t.note)
        assertEquals(1, changes)
    }

    // ───────────── delete and undo

    @Test fun deleteAndRestoreKeepPhotosLocationAndRate() {
        val id = editor.save(expense().copy(lat = 1.0, lng = 2.0), photos = listOf("a.jpg"))
        val gone = editor.delete(repo.txn(id)!!)
        assertNotNull(gone)
        assertNull(repo.liveTxn(id))
        val back = editor.restore(gone)
        val t = repo.txn(back)!!
        assertEquals(25_000, t.amount); assertEquals(1.0, t.lat)
        assertEquals(listOf("a.jpg"), repo.photosNow(back).map { it.fileName })
        assertEquals(3, changes)
    }

    @Test fun deletingAPlanEntryDeletesTheWholePlan() {
        val id = editor.save(expense(30_000, "acc_card").copy(installments = 3))
        val group = repo.txn(id)!!.installmentGroupId!!
        editor.delete(repo.installmentGroup(group).last())
        assertTrue(repo.installmentGroup(group).none { repo.liveTxn(it.id) != null })
    }

    @Test fun balanceCorrectionsCannotBeRestoredAsEntries() {
        repo.adjustBalance("acc_cash", 10_000)
        val adj = repo.allTxns().single { it.kind == TxnKind.ADJUST_UP }
        assertNull(editor.delete(adj))
        assertNull(repo.liveTxn(adj.id))
    }
}
