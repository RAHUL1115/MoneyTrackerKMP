package app.moneytracker.ui.fresh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.flattenCategories
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FreshCategories(kind: CategoryKind) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val accountNames by repo.accountNames.collectAsState(emptyMap())
    val useSubcategories by repo.useSubcategories.collectAsState(false)
    var shown by rememberSaveable { mutableStateOf(kind) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    fun write(block: () -> Unit) = scope.launch { withContext(repo.io) { block() } }

    SubScreen(tr("Categories"), nav::pop, add = tr("Add category") to { editing = Category("", "", shown, null, categories.size) }) {
        val kinds = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME)
        Segmented(
            kinds.map { k -> (if (k == CategoryKind.EXPENSE) tr("Expense") else tr("Income")) + " · " + categories.count { it.kind == k && it.parentId == null } },
            kinds.indexOf(shown), { shown = kinds[it] },
        )
        Text(tr("Tap a category to rename it, move it under another or set its usual account. Arrows change the order on the Add screen."),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp)
        val list = flattenCategories(categories.filter { it.kind == shown }).filter { useSubcategories || it.parentId == null }
        if (list.isEmpty()) EmptyCard(tr("No categories yet."), tr("Add category")) { editing = Category("", "", shown, null, categories.size) }
        else FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                list.forEachIndexed { i, c ->
                    if (i > 0) Divider()
                    val subs = categories.count { it.parentId == c.id }
                    val parent = c.parentId?.let { p -> categories.firstOrNull { it.id == p } }
                    val usual = c.defaultAccountId?.let { accountNames[it] }
                    val meta = listOfNotNull(
                        parent?.let { tr("in {0}", it.name) } ?: if (subs > 0 && useSubcategories) tr("{0} sub-categories", subs) else null,
                        usual?.let { tr("Usual: {0}", it) },
                    ).joinToString(" · ").ifEmpty { tr("No usual account") }
                    // Reorder among siblings (main categories, or the subs of one main).
                    val siblings = categories.filter { it.kind == c.kind && it.parentId == c.parentId }
                    val at = siblings.indexOf(c)
                    fun move(delta: Int) {
                        val order = siblings.map { it.id }.toMutableList()
                        val j = at + delta
                        if (j !in order.indices) return
                        order[at] = order[j].also { order[j] = order[at] }
                        write { repo.reorderCategories(order) }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = if (c.parentId != null) 28.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).clip(F.iconShape).clickable { editing = c }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Mark(c.name, if (c.parentId != null) 26.dp else 34.dp)
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = F.ink, fontSize = if (c.parentId != null) 14.sp else 14.sp,
                                    fontWeight = if (c.parentId != null) FontWeight.Medium else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(meta, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        ArrowButton(Icons.Outlined.KeyboardArrowUp, tr("Move {0} up", c.name), at > 0) { move(-1) }
                        ArrowButton(Icons.Outlined.KeyboardArrowDown, tr("Move {0} down", c.name), at < siblings.lastIndex) { move(1) }
                    }
                }
            }
        }
        if (!useSubcategories) FCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Sub-categories are off, so Add shows only main categories."), Modifier.weight(1f), color = F.muted, fontSize = 13.sp)
                StgTextButton(tr("Turn on"), { write { repo.putSetting(Keys.USE_SUBCATEGORIES, "1") } })
            }
        }
    }

    editing?.let { c ->
        CategorySheet(c, categories, accounts, accountNames, useSubcategories, onDismiss = { editing = null }, onDelete = { deleting = c }) { saved ->
            editing = null
            write { repo.saveCategory(saved) }
            if (c.id.isEmpty()) toaster.show(tr("Added {0}", saved.name)) { write { repo.deleteCategory(saved.id) } }
            else toaster.show(tr("Saved {0}", saved.name)) { write { repo.saveCategory(c) } }
        }
    }
    deleting?.let { c ->
        val children = categories.filter { it.parentId == c.id }
        Confirm(
            tr("Delete “{0}”?", c.name),
            (if (children.isNotEmpty()) tr("Its {0} sub-categories are removed too.", children.size) + " " else "") +
                tr("Existing transactions keep their amounts but show as uncategorized."),
            tr("Delete"), { deleting = null },
        ) {
            editing = null
            write { repo.deleteCategory(c.id) }
            toaster.show(tr("{0} deleted", c.name)) { write { (listOf(c) + children).forEach { repo.saveCategory(it) } } }
        }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(F.iconShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) F.ink else F.border, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun CategorySheet(
    c: Category,
    categories: List<Category>,
    accounts: List<Account>,
    accountNames: Map<String, String>,
    useSubcategories: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (Category) -> Unit,
) {
    var name by remember(c) { mutableStateOf(c.name) }
    var parentId by remember(c) { mutableStateOf(c.parentId) }
    var defaultAccount by remember(c) { mutableStateOf(c.defaultAccountId) }
    val isNew = c.id.isEmpty()
    val parents = categories.filter { it.kind == c.kind && it.parentId == null && it.id != c.id }
    val hasChildren = categories.any { it.parentId == c.id }
    val trimmed = name.trim()
    val duplicate = categories.any { it.id != c.id && it.kind == c.kind && it.parentId == parentId && it.name.equals(trimmed, ignoreCase = true) }
    val kindName = if (c.kind == CategoryKind.INCOME) tr("New income category") else tr("New expense category")

    Sheet(if (isNew) kindName else tr("Edit {0}", c.name), onDismiss, bottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) Box(
                Modifier.size(52.dp).clip(F.buttonShape).clickable(onClickLabel = tr("Delete category"), onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Delete, tr("Delete category"), tint = F.danger) }
            PrimaryButton(if (isNew) tr("Add category") else tr("Save changes"), enabled = trimmed.isNotEmpty() && !duplicate, modifier = Modifier.weight(1f), onClick = {
                onSave(c.copy(id = c.id.ifEmpty { newId() }, name = trimmed, parentId = parentId, defaultAccountId = defaultAccount))
            })
        }
    }) {
        StgField(name, { name = it }, label = tr("Name (emoji welcome 🍜)"), placeholder = tr("e.g. Pets"), error = duplicate)
        if (duplicate) Text(tr("There is already a category called {0}", trimmed), color = F.danger, fontSize = 12.sp)
        if (useSubcategories) {
            StgLabel(tr("Parent (optional)"))
            if (hasChildren) StgChips(listOf(Unit), Unit, { tr("Main category (has sub-categories)") }) {}
            else StgChips(listOf<Category?>(null) + parents, parents.firstOrNull { it.id == parentId }, { it?.name ?: tr("None, main category") }) { parentId = it?.id }
        }
        StgLabel(tr("Usual account · picked for you on Add"))
        val inherited = parentId?.let { p -> categories.firstOrNull { it.id == p }?.defaultAccountId }?.let { accountNames[it] }
        val options = listOf<String?>(null) + accounts.filter { !it.hidden || it.id == defaultAccount }.map { it.id }
        StgChips(options, defaultAccount, { id ->
            id?.let { accountNames[it] } ?: inherited?.let { tr("Same as main category ({0})", it) } ?: tr("None")
        }) { defaultAccount = it }
    }
}
