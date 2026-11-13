package app.moneytracker.data

import app.moneytracker.db.Template as TemplateRow

/** A saved one-tap entry ("bookmark"). [amount] 0 = ask every time. */
data class Template(
    val id: String,
    val name: String,
    val type: DraftType,
    val accountId: String?,
    val toAccountId: String?,
    val categoryId: String?,
    val amount: Long,
    val note: String = "",
    val memo: String = "",
    val sortOrder: Int = 0,
)

/** Bookmarks: saved entries that pre-fill a new one in a tap (stored as [Template]s). */
class BookmarkStore(private val db: Db) {
    private val q get() = db.q

    val templates: CachedFlow<List<Template>> = db.cached({ q.allTemplates() }) { rows ->
        rows.map { Template(it.id, it.name, DraftType.valueOf(it.type), it.accountId, it.toAccountId, it.categoryId, it.amount, it.note, it.memo, it.sortOrder.toInt()) }
    }
    fun saveTemplate(t: Template) = q.upsertTemplate(TemplateRow(t.id, t.name, t.type.name, t.accountId, t.toAccountId, t.categoryId,
        t.amount, t.note, t.memo, t.sortOrder.toLong(), 0, db.now()))
    fun deleteTemplate(id: String) = q.softDeleteTemplate(db.now(), id)
}
