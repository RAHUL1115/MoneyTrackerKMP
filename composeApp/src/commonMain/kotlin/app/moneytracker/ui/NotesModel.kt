package app.moneytracker.ui

import app.moneytracker.data.CachedFlow
import app.moneytracker.data.NoteStore
import app.moneytracker.data.newId
import app.moneytracker.domain.Note
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** Notes are dated by day: a note's day is stored as midnight UTC of that day, in milliseconds. */
const val NOTE_DAY_MS = 86_400_000L

fun noteDay(date: LocalDate): Long = date.toEpochDays() * NOTE_DAY_MS

fun noteDate(createdAt: Long): LocalDate = LocalDate.fromEpochDays((createdAt / NOTE_DAY_MS).toInt())

/** The notes list: pinned first, then by day, newest first. */
class NotesModel(notes: NoteStore, scope: CoroutineScope) {
    val notes: StateFlow<List<Note>> = notes.notes.map(::pinnedFirst).stateIn(scope, SharingStarted.Eagerly, pinnedFirst(notes.notes.current))

    private fun pinnedFirst(list: List<Note>) =
        list.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAt }.thenByDescending { it.updatedAt })
}

/** A note as it is being written: what the editor's fields hold. */
data class NoteDraft(val title: String, val content: String, val color: Int, val pinned: Boolean, val createdAt: Long) {
    val filled: Boolean get() = title.isNotBlank() || content.isNotBlank()

    /** True for a new note, or when anything differs from the stored [original]. */
    fun changedFrom(original: Note?): Boolean = original == null || original.title != title.trim() || original.content != content ||
        original.color != color || original.pinned != pinned || original.createdAt != createdAt

    fun toNote(id: String) = Note(id, title.trim(), content, color, pinned, createdAt, 0)

    companion object {
        fun blank(today: LocalDate) = NoteDraft("", "", 0, false, noteDay(today))
        fun of(n: Note) = NoteDraft(n.title, n.content, n.color, n.pinned, n.createdAt)
    }
}

/**
 * Saving and deleting one note. [scope] must outlive the editor: the editor closes as soon as it saves, and
 * Undo is tapped after it has closed.
 */
class NoteEditorModel(private val store: NoteStore, private val io: CoroutineDispatcher, private val scope: CoroutineScope) {
    val notes: CachedFlow<List<Note>> get() = store.notes

    /**
     * Saves [draft] when leaving the editor, if it has text and something changed since [original]. [loaded] is false
     * while an existing note is still being read, so its empty fields never overwrite it. True when a save started.
     */
    fun saveOnLeave(noteId: String?, draft: NoteDraft, original: Note?, loaded: Boolean): Boolean {
        if (!draft.filled || !draft.changedFrom(original) || !loaded) return false
        val note = draft.toNote(noteId ?: newId())
        write { store.saveNote(note) }
        return true
    }

    fun delete(id: String) = write { store.deleteNote(id) }

    /** Undo for [delete]. */
    fun restore(note: Note) = write { store.saveNote(note) }

    private fun write(block: () -> Unit) {
        scope.launch { withContext(io) { block() } }
    }
}
