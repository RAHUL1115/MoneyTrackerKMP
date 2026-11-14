package app.moneytracker.ui

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.Prefs
import app.moneytracker.data.SettingsStore
import app.moneytracker.designsystem.classic.flattenCategories
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the Categories screen draws, in both layouts. */
data class CategoriesState(
    val categories: List<Category>,
    val accounts: List<Account>,
    val accountNames: Map<String, String>,
    val useSubcategories: Boolean,
) {
    /** The categories of [kind] in tree order; only main ones while sub-categories are off. */
    fun shown(kind: CategoryKind): List<Category> =
        flattenCategories(categories.filter { it.kind == kind }).filter { useSubcategories || it.parentId == null }

    /** The categories a category is ordered among: the main ones of its kind, or the subs of its main one. */
    fun siblingsOf(c: Category): List<Category> = categories.filter { it.kind == c.kind && it.parentId == c.parentId }

    fun childrenOf(c: Category): List<Category> = categories.filter { it.parentId == c.id }

    /** A blank category of [kind] for the editor, placed last. */
    fun blank(kind: CategoryKind) = Category("", "", kind, null, categories.size)

    /** True when another category beside [c] under [parentId] already has [name], ignoring case. */
    fun isDuplicate(c: Category, name: String, parentId: String?): Boolean =
        categories.any { it.id != c.id && it.kind == c.kind && it.parentId == parentId && it.name.equals(name, ignoreCase = true) }
}

/** The Categories screen: list, reorder, add, change and delete categories, with undo for the last change. */
class CategoriesModel(
    private val categories: CategoryStore,
    accounts: AccountStore,
    private val settings: SettingsStore,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<CategoriesState> =
        combine(categories.categories, accounts.accounts, accounts.accountNames, settings.useSubcategories, ::CategoriesState)
            .stateIn(
                scope, SharingStarted.Eagerly,
                CategoriesState(categories.categories.current, accounts.accounts.current, accounts.accountNames.current, Prefs.USE_SUBCATEGORIES.default),
            )

    /** Swaps [c] with the sibling [delta] places away; does nothing at either end. */
    fun move(c: Category, delta: Int) {
        val order = state.value.siblingsOf(c).map { it.id }.toMutableList()
        val at = order.indexOf(c.id)
        val j = at + delta
        if (at < 0 || j !in order.indices) return
        order[at] = order[j].also { order[j] = order[at] }
        write { categories.reorderCategories(order) }
    }

    fun save(c: Category) = write { categories.saveCategory(c) }

    /** Deletes [c] and its sub-categories; entries keep their amounts and show as uncategorized. */
    fun delete(c: Category) = write { categories.deleteCategory(c.id) }

    /** Undo for [delete]: brings back [c] and the sub-categories it had. */
    fun restore(c: Category, children: List<Category>) = write { (listOf(c) + children).forEach { categories.saveCategory(it) } }

    fun turnOnSubcategories() = write { settings.putSetting(Prefs.USE_SUBCATEGORIES, true) }

    private fun write(block: () -> Unit) {
        scope.launch { withContext(io) { block() } }
    }
}
