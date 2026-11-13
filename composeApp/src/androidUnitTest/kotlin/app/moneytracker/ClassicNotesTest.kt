package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Note
import app.moneytracker.ui.NoteDraft
import app.moneytracker.ui.NoteEditorModel
import app.moneytracker.ui.NoteOrder
import app.moneytracker.ui.NotesModel
import app.moneytracker.ui.noteDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the notes holders do for the classic layout: stored order, saving every time, closing after the write. */
class ClassicNotesTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun editor() = NoteEditorModel(repo.noteStore, repo.io, scope)

    @Test fun classicListKeepsStoredOrderPinnedThenLastChanged() {
        repo.noteStore.saveNote(Note("older-day", "A", "", createdAt = noteDay(today) - 86_400_000L, updatedAt = 0))
        repo.noteStore.saveNote(Note("newer-day", "B", "", createdAt = noteDay(today), updatedAt = 0))
        repo.noteStore.saveNote(Note("pin", "P", "", pinned = true, createdAt = 0, updatedAt = 0))
        val stored = repo.noteStore.notes.now().map { it.id }
        assertEquals("pin", stored.first())
        assertEquals(stored, NotesModel(repo.noteStore, scope, NoteOrder.LAST_CHANGED).notes.value.map { it.id })
    }

    @Test fun classicSaveStoresEvenWhenUnchangedThenCloses() {
        var closed = 0
        editor().saveThen("n1", NoteDraft.undated.copy(title = " Bills "), today) { closed++ }
        val stored = repo.noteStore.notes.now().single()
        assertEquals("Bills", stored.title)
        assertEquals(noteDay(today), stored.createdAt)                     // an undated note gets today
        editor().saveThen("n1", NoteDraft.of(stored), today) { closed++ }  // unchanged, still written
        assertEquals(2, closed)
        assertEquals("Bills", repo.noteStore.notes.now().single().title)
    }

    @Test fun classicSaveSkipsAnEmptyNoteButStillCloses() {
        var closed = false
        editor().saveThen(null, NoteDraft.undated.copy(title = "  "), today) { closed = true }
        assertTrue(closed)
        assertTrue(repo.noteStore.notes.now().isEmpty())
    }

    @Test fun deleteClosesOnceTheNoteIsGone() {
        editor().saveThen("n1", NoteDraft.blank(today).copy(content = "Pay rent"), today) {}
        var goneWhenClosed = false
        editor().delete("n1") { goneWhenClosed = repo.noteStore.notes.now().isEmpty() }
        assertTrue(goneWhenClosed)
    }
}
