package app.moneytracker.lists

import androidx.compose.runtime.saveable.listSaver
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

/** How the notes list is ordered. Both put pinned notes first. */
enum class NoteOrder {
    /** Then by day, newest first: the new layout. */
    BY_DAY,

    /** As stored, last changed first: the classic layout. */
    LAST_CHANGED,
}

/** The notes list in [order]. */
class NotesModel(notes: NoteStore, scope: CoroutineScope, private val order: NoteOrder = NoteOrder.BY_DAY) {
    val notes: StateFlow<List<Note>> = notes.notes.map(::ordered).stateIn(scope, SharingStarted.Eagerly, ordered(notes.notes.current))

    private fun ordered(list: List<Note>) = when (order) {
        NoteOrder.BY_DAY -> list.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAt }.thenByDescending { it.updatedAt })
        NoteOrder.LAST_CHANGED -> list
    }
}

/** A note as it is being written: what the editor's fields hold. */
data class NoteDraft(val title: String, val content: String, val color: Int, val pinned: Boolean, val createdAt: Long) {
    val filled: Boolean get() = title.isNotBlank() || content.isNotBlank()

    /** True for a new note, or when anything differs from the stored [original]. */
    fun changedFrom(original: Note?): Boolean = original == null || original.title != title.trim() || original.content != content ||
        original.color != color || original.pinned != pinned || original.createdAt != createdAt

    fun toNote(id: String) = Note(id, title.trim(), content, color, pinned, createdAt, 0)

    /** The note's day, or [today] while it has none (0): the classic editor dates a new note when it is saved. */
    fun dayOr(today: LocalDate): Long = if (createdAt == 0L) noteDay(today) else createdAt

    companion object {
        fun blank(today: LocalDate) = NoteDraft("", "", 0, false, noteDay(today))

        /** A new note without a day yet; [dayOr] gives it one. */
        val undated = NoteDraft("", "", 0, false, 0)
        fun of(n: Note) = NoteDraft(n.title, n.content, n.color, n.pinned, n.createdAt)
    }
}

/** Keeps a [NoteDraft] across the app being closed in the background. */
val NoteDraftSaver = listSaver<NoteDraft, Any>(
    save = { listOf(it.title, it.content, it.color, it.pinned, it.createdAt) },
    restore = { NoteDraft(it[0] as String, it[1] as String, it[2] as Int, it[3] as Boolean, it[4] as Long) },
)

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

    /**
     * The classic editor's save: stores [draft] whenever it has text, even unchanged, dated [today] if it has no day,
     * then calls [then] once the save is done. An empty draft is not stored; [then] runs at once.
     */
    fun saveThen(noteId: String?, draft: NoteDraft, today: LocalDate, then: () -> Unit) {
        if (!draft.filled) { then(); return }
        val note = draft.copy(createdAt = draft.dayOr(today)).toNote(noteId ?: newId())
        write(then) { store.saveNote(note) }
    }

    /** Deletes the note, then calls [then] once it is gone. */
    fun delete(id: String, then: () -> Unit = {}) = write(then) { store.deleteNote(id) }

    /** Undo for [delete]. */
    fun restore(note: Note) = write { store.saveNote(note) }

    private fun write(then: () -> Unit = {}, block: () -> Unit) {
        scope.launch { withContext(io) { block() }; then() }
    }
}
