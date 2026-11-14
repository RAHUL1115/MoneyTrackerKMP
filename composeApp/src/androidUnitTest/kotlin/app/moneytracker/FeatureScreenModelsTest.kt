package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.RepeatTiming
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Capture
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Note
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.lists.CategoriesModel
import app.moneytracker.lists.NoteDraft
import app.moneytracker.lists.NoteEditorModel
import app.moneytracker.lists.NotesModel
import app.moneytracker.lists.RepeatsModel
import app.moneytracker.lists.noteDay
import app.moneytracker.messages.MessageReviewModel
import app.moneytracker.messages.MessageSettingsModel
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate

/** The state holders behind Repeats, Notes, Categories and Bank SMS, over an in-memory database. */
class FeatureScreenModelsTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun repeats() = RepeatsModel(repo.repeatStore, repo.settingsStore, repo.accountStore, repo.categoryStore, repo.io, repo.today, scope)

    private fun addMonthlyRent() = repo.txnStore.create(
        TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-02-20"), "acc_bank", categoryId = "cat_ex_0", amount = 500, note = "Rent", repeat = RepeatType.MONTHLY),
    )

    // ───────── repeats

    @Test fun repeatsGroupIncomeSpendingAndTransfersLeavingOutEmptyOnes() {
        addMonthlyRent()
        val model = repeats()
        val groups = model.state.value.groups
        assertEquals(listOf(TxnKind.EXPENSE), groups.map { it.first })
        assertEquals("Rent", groups.single().second.single().note)
    }

    @Test fun changingARuleRunsRepeatsAgain() {
        addMonthlyRent()
        val model = repeats()
        val rule = model.state.value.rules.single()
        val before = repo.txnStore.accountTxns("acc_bank").size
        // Moved back to a date already passed: the entries that became due are added straight away.
        val march = LocalDate.parse("2026-03-01")
        model.saveRule(rule.copy(startDate = march, nextDate = march, amount = 700))
        assertEquals(700, model.state.value.rules.single().amount)
        assertEquals(before + 1, repo.txnStore.accountTxns("acc_bank").size)
    }

    @Test fun stoppingARuleKeepsItsEntriesAndUndoBringsItBack() {
        addMonthlyRent()
        val model = repeats()
        val rule = model.state.value.rules.single()
        val made = repo.txnStore.accountTxns("acc_bank").size
        model.stopRule(rule)
        assertTrue(model.state.value.rules.isEmpty())
        assertEquals(made, repo.txnStore.accountTxns("acc_bank").size)
        model.restoreRule(rule)
        assertEquals(rule.id, model.state.value.rules.single().id)
    }

    @Test fun timingIsStoredAndANewRepeatStartsAsAMonthlyExpenseToday() {
        val model = repeats()
        model.setTiming(RepeatTiming.MONTH_START)
        assertEquals(RepeatTiming.MONTH_START, model.state.value.timing)
        val draft = model.newRepeatDraft()
        assertEquals(today, draft.date)
        assertEquals(RepeatType.MONTHLY, draft.repeat)
        assertEquals(DraftType.EXPENSE, draft.type)
    }

    // ───────── categories

    private fun categories() = CategoriesModel(repo.categoryStore, repo.accountStore, repo.settingsStore, repo.io, scope)

    @Test fun movingACategorySwapsItWithItsNeighbourAndStopsAtTheEnds() {
        val model = categories()
        val mains = model.state.value.shown(CategoryKind.EXPENSE).filter { it.parentId == null }
        model.move(mains[0], 1)
        val after = model.state.value.siblingsOf(mains[0]).map { it.id }
        assertEquals(listOf(mains[1].id, mains[0].id), after.take(2))
        model.move(model.state.value.siblingsOf(mains[0]).first(), -1)   // already first: nothing changes
        assertEquals(after, model.state.value.siblingsOf(mains[0]).map { it.id })
    }

    @Test fun deletingACategoryTakesItsSubsAndRestoreBringsThemBack() {
        val model = categories()
        val main = Category("c_main", "Pets", CategoryKind.EXPENSE, null)
        val sub = Category("c_sub", "Vet", CategoryKind.EXPENSE, "c_main")
        model.save(main); model.save(sub)
        assertEquals(listOf(sub), model.state.value.childrenOf(main))
        model.delete(main)
        assertTrue(model.state.value.categories.none { it.id == "c_main" || it.id == "c_sub" })
        model.restore(main, listOf(sub))
        assertEquals(setOf("c_main", "c_sub"), model.state.value.categories.map { it.id }.filter { it.startsWith("c_") }.toSet())
    }

    @Test fun duplicateNamesAreCaughtIgnoringCaseWithinTheSameParent() {
        val model = categories()
        model.save(Category("c_main", "Pets", CategoryKind.EXPENSE, null))
        val state = model.state.value
        val fresh = state.blank(CategoryKind.EXPENSE)
        assertTrue(state.isDuplicate(fresh, "pets", null))
        assertFalse(state.isDuplicate(fresh, "pets", "c_main"))
        assertFalse(state.isDuplicate(state.blank(CategoryKind.INCOME), "pets", null))
    }

    @Test fun subCategoriesShowOnlyOnceTurnedOn() {
        val model = categories()
        model.save(Category("c_main", "Pets", CategoryKind.EXPENSE, null))
        model.save(Category("c_sub", "Vet", CategoryKind.EXPENSE, "c_main"))
        val subOn = repo.settingsStore.settingNow(Prefs.USE_SUBCATEGORIES)
        if (subOn) repo.settingsStore.putSetting(Prefs.USE_SUBCATEGORIES, false)
        assertTrue(model.state.value.shown(CategoryKind.EXPENSE).none { it.id == "c_sub" })
        model.turnOnSubcategories()
        assertTrue(model.state.value.shown(CategoryKind.EXPENSE).any { it.id == "c_sub" })
    }

    // ───────── notes

    @Test fun notesListPinnedFirstThenNewestDay() {
        repo.noteStore.saveNote(Note("old", "Old", "", createdAt = noteDay(today) - 86_400_000L, updatedAt = 0))
        repo.noteStore.saveNote(Note("new", "New", "", createdAt = noteDay(today), updatedAt = 0))
        repo.noteStore.saveNote(Note("pin", "Pinned", "", pinned = true, createdAt = 0, updatedAt = 0))
        assertEquals(listOf("pin", "new", "old"), NotesModel(repo.noteStore, scope).notes.value.map { it.id })
    }

    @Test fun leavingTheEditorSavesOnlyWhenThereIsSomethingNew() {
        val editor = NoteEditorModel(repo.noteStore, repo.io, scope)
        val blank = NoteDraft.blank(today)
        assertFalse(editor.saveOnLeave(null, blank, null, loaded = true))                      // nothing written
        assertTrue(editor.saveOnLeave("n1", blank.copy(title = " Bills "), null, loaded = true))
        val stored = repo.noteStore.notes.now().single()
        assertEquals("Bills", stored.title)
        assertFalse(editor.saveOnLeave("n1", NoteDraft.of(stored), stored, loaded = true))      // unchanged
        assertFalse(editor.saveOnLeave("n1", blank.copy(title = "x"), stored, loaded = false))  // still loading
    }

    @Test fun deletingANoteCanBeUndone() {
        val editor = NoteEditorModel(repo.noteStore, repo.io, scope)
        editor.saveOnLeave("n1", NoteDraft.blank(today).copy(content = "Pay rent"), null, loaded = true)
        val stored = repo.noteStore.notes.now().single()
        editor.delete("n1")
        assertTrue(repo.noteStore.notes.now().isEmpty())
        editor.restore(stored)
        assertEquals("Pay rent", repo.noteStore.notes.now().single().content)
    }

    // ───────── bank SMS

    private val sms = "Rs.250.00 debited from a/c XX1234 at SWIGGY on 10-03-26"

    private fun review() = MessageReviewModel(
        repo.captureStore, repo.messageIntake, repo.settingsStore, repo.accountStore, repo.categoryStore, repo.io, scope,
    )

    @Test fun pastedMessageJoinsTheQueueWithItsSuggestedEntry() {
        val model = review()
        var read: Capture? = null
        model.paste(sms) { read = it }
        val c = assertNotNull(read)
        assertEquals(listOf(c.id), model.state.value.captures.map { it.id })
        assertEquals(250_00L, model.drafts.value[c.id]?.amount)
        var notPayment: Capture? = c
        model.paste("See you at 7") { notPayment = it }
        assertNull(notPayment)
    }

    @Test fun dismissingAMessageCanBeUndone() {
        val model = review()
        var read: Capture? = null
        model.paste(sms) { read = it }
        val c = assertNotNull(read)
        var done = false
        model.dismiss(c) { done = true }
        assertTrue(done)
        assertTrue(model.state.value.captures.isEmpty())
        model.undoDismiss(c)
        assertEquals(listOf(c.id), model.state.value.captures.map { it.id })
    }

    @Test fun smsSettingsStoreFiltersOnePerLineAndCountWaitingMessages() {
        val model = MessageSettingsModel(repo.settingsStore, repo.captureStore, repo.io, scope)
        model.saveFilter(Prefs.SMS_BLOCKED_SENDERS, "  VM-PROMO \n\n AD-SPAM ")
        assertEquals("VM-PROMO\nAD-SPAM", model.state.value.filters.blockedSenders)
        model.put(Prefs.SMS_IMPORT_DAYS, 90)
        assertEquals(90, model.state.value.switches.importDays)
        repo.messageIntake.ingestMessage("SMS", "AD-HDFCBK", sms, 1)
        assertEquals(1, model.state.value.waiting)
    }
}
