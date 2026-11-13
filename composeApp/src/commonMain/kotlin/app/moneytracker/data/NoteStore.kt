package app.moneytracker.data

import app.moneytracker.domain.Note
import app.moneytracker.db.Note as NoteRow

/** The free-text notes page (not an entry's note line). */
class NoteStore(private val db: Db) {
    private val q get() = db.q

    val notes: CachedFlow<List<Note>> = db.cached({ q.allNotes() }) { rows ->
        rows.map { Note(it.id, it.title, it.content, it.color.toInt(), it.pinned != 0L, it.createdAt, it.updatedAt) }
    }
    fun saveNote(n: Note) = q.upsertNote(NoteRow(n.id, n.title, n.content, n.color.toLong(), n.pinned.toLong(), n.createdAt, db.now(), 0))
    fun deleteNote(id: String) = q.softDeleteNote(db.now(), id)
}
