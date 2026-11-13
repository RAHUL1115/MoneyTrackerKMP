package app.moneytracker.data

import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.TAG_USAGE_EXCEPTION
import app.moneytracker.domain.Tag
import app.moneytracker.db.Tag as TagRow

/** Tags and which entries carry them, including the two built-in card tags. */
class TagStore(private val db: Db) {
    private val q get() = db.q

    val tags: CachedFlow<List<Tag>> = db.cached({ q.allTags() }) { rows -> rows.map { Tag(it.id, it.name, it.sortOrder.toInt()) } }
    /** txnId → tag names, for list rendering. */
    val txnTagNames: CachedFlow<Map<String, List<String>>> = db.cached({ q.allTxnTags() }) { rows ->
        rows.groupBy({ it.txnId }, { it.name })
    }
    fun tagsNow(): List<Tag> = tags.now()
    fun txnTagNamesNow(): Map<String, List<String>> = txnTagNames.now()

    fun saveTag(t: Tag) = q.upsertTag(TagRow(t.id, t.name.trim(), t.sortOrder.toLong(), 0, db.now()))
    fun txnIdsWithTag(tagId: String): Set<String> = q.txnIdsWithTag(tagId).executeAsList().toSet()

    /** The two card tags; created once, renaming is allowed. Tags still carrying an old default name get the new one. */
    fun ensureSystemTags() {
        fun ensure(id: String, name: String, oldName: String, order: Long) {
            val row = q.tagById(id).executeAsOneOrNull()
            if (row == null || row.name == oldName) q.upsertTag(TagRow(id, name, row?.sortOrder ?: order, row?.deleted ?: 0, db.now()))
        }
        ensure(TAG_PREPAYMENT, "Due now", "Prepayment", -2)
        ensure(TAG_USAGE_EXCEPTION, "Excluded from target", "Exception", -1)
    }
    fun deleteTag(id: String) = q.softDeleteTag(db.now(), id)
    fun tagsOf(txnId: String): List<String> = q.tagIdsForTxn(txnId).executeAsList()
    fun setTags(txnId: String, tagIds: List<String>) = db.transaction {
        q.clearTxnTags(txnId)
        tagIds.forEach { q.addTxnTag(txnId, it) }
    }

    /** Finds or creates a tag by name (case-insensitive). */
    fun tagNamed(name: String): String {
        val existing = q.allTags().executeAsList().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        if (existing != null) return existing.id
        val id = newId()
        saveTag(Tag(id, name, q.allTags().executeAsList().size))
        return id
    }
}
