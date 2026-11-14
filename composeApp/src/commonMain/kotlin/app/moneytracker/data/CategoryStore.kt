package app.moneytracker.data

import app.moneytracker.db.Category as CategoryRow
import app.moneytracker.domain.Category

/** Income and expense categories, main and sub. */
class CategoryStore(private val db: Db) {
    private val q get() = db.q

    val categories: CachedFlow<List<Category>> = db.cached({ q.allCategories() }) { rows -> rows.map { it.toDomain() } }
    /** Every category by id, deleted ones too: for showing the names of past entries (pickers use [categories]). */
    val categoryById: CachedFlow<Map<String, Category>> = db.cached({ q.categoriesWithDeleted() }) { rows -> rows.associate { it.id to it.toDomain() } }
    fun categoriesNow(): List<Category> = categories.now()
    fun categoryByIdNow(): Map<String, Category> = categoryById.now()

    fun saveCategory(c: Category) =
        q.upsertCategory(CategoryRow(c.id, c.name, c.kind.name, c.parentId, c.sortOrder.toLong(), 0, db.now(), c.defaultAccountId))

    fun deleteCategory(id: String) = q.softDeleteCategory(db.now(), id, id)

    fun reorderCategories(idsInOrder: List<String>) = db.saveOrder(idsInOrder, q::setCategoryOrder)
}
