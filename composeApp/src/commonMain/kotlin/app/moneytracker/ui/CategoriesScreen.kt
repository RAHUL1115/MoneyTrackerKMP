package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Classic categories: income or expense, reordered with arrows, edited in a dialog. */
@Composable
fun CategoriesScreen(initialKind: CategoryKind) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val categories by repo.categoryStore.categories.collectAsState()
    var kind by remember { mutableStateOf(initialKind) }
    val accounts by repo.accountStore.accounts.collectAsState()
    val accountNames by repo.accountStore.accountNames.collectAsState()
    val useSubcategories by repo.settingsStore.useSubcategories.collectAsState(false)
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    fun reorder(order: List<String>) { scope.launch { withContext(repo.io) { repo.categoryStore.reorderCategories(order) } } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(if (kind == CategoryKind.INCOME) tr("Income categories") else tr("Expense categories"), nav::pop)
            UnderlineTabs(listOf(tr("Income"), tr("Expense")), kind.ordinal) { kind = CategoryKind.entries[it] }
            CategoryList(kind, categories, accountNames, useSubcategories, onEdit = { editing = it }, onDelete = { deleting = it }, onReorder = ::reorder)
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { editing = Category("", "", kind, null, categories.size) }
    }

    editing?.let { c ->
        CategoryEditDialog(c, categories, accounts, accountNames, useSubcategories, onDismiss = { editing = null }) { saved ->
            scope.launch { withContext(repo.io) { repo.categoryStore.saveCategory(saved) } }
            editing = null
        }
    }
    deleting?.let { c ->
        CategoryDeleteDialog(c, onDismiss = { deleting = null }) {
            scope.launch { withContext(repo.io) { repo.categoryStore.deleteCategory(c.id) } }
            deleting = null
        }
    }
}

@Composable
private fun CategoryList(
    kind: CategoryKind,
    categories: List<Category>,
    accountNames: Map<String, String>,
    useSubcategories: Boolean,
    onEdit: (Category) -> Unit,
    onDelete: (Category) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (!useSubcategories) item(key = "hint") {
            Text(tr("Sub-categories are off. Turn them on in Configuration to add or show them."),
                Modifier.fillMaxWidth().background(PageBackground).padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = SubtleText)
        }
        items(flattenCategories(categories.filter { it.kind == kind }).filter { useSubcategories || it.parentId == null }, key = { it.id }) { c ->
            CategoryLine(c, categories, accountNames, onEdit = { onEdit(c) }, onDelete = { onDelete(c) }, onReorder = onReorder)
            HorizontalDivider(color = DividerColor)
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** One category with its default account, arrows to move it among its siblings, and delete. */
@Composable
private fun CategoryLine(
    c: Category,
    categories: List<Category>,
    accountNames: Map<String, String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(if (c.parentId == null) SurfaceColor else PageBackground).clickable(onClick = onEdit)
            .padding(start = if (c.parentId != null) 52.dp else 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(c.name, fontWeight = if (c.parentId == null) FontWeight.Medium else null,
                color = if (c.parentId == null) Ink else SubtleText)
            c.defaultAccountId?.let { accountNames[it] }?.let { Text(tr("Default: {0}", it), fontSize = 12.sp, color = IncomeColor) }
        }
        // Reorder among siblings (main categories, or the subs of one main).
        val siblings = categories.filter { it.kind == c.kind && it.parentId == c.parentId }
        val i = siblings.indexOf(c)
        fun move(delta: Int) {
            val order = siblings.map { it.id }.toMutableList()
            val j = i + delta
            if (j !in order.indices) return
            order[i] = order[j].also { order[j] = order[i] }
            onReorder(order)
        }
        IconButton(onClick = { move(-1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, tr("Move {0} up", c.name), tint = SubtleText) }
        IconButton(onClick = { move(1) }, enabled = i < siblings.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, tr("Move {0} down", c.name), tint = SubtleText) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, tr("Delete {0}", c.name), tint = SubtleText) }
    }
}

@Composable
private fun CategoryEditDialog(
    c: Category,
    categories: List<Category>,
    accounts: List<Account>,
    accountNames: Map<String, String>,
    useSubcategories: Boolean,
    onDismiss: () -> Unit,
    onSave: (Category) -> Unit,
) {
    var name by remember(c) { mutableStateOf(c.name) }
    var parentId by remember(c) { mutableStateOf(c.parentId) }
    var defaultAccount by remember(c) { mutableStateOf(c.defaultAccountId) }
    val parents = categories.filter { it.kind == c.kind && it.parentId == null && it.id != c.id }
    val hasChildren = categories.any { it.parentId == c.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (c.id.isEmpty()) tr("Add category") else tr("Edit category")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Name (emoji welcome 🍜)")) })
                val inherited = parentId?.let { p -> categories.firstOrNull { it.id == p }?.defaultAccountId }?.let { accountNames[it] }
                Picker(
                    tr("Default account"), listOf<String?>(null) + accounts.map { it.id }, defaultAccount,
                    { id -> id?.let { accountNames[it] } ?: inherited?.let { tr("Same as main category ({0})", it) } ?: tr("None") },
                    { defaultAccount = it },
                )
                if (!hasChildren && useSubcategories) {
                    Picker(tr("Parent (optional)"), listOf<Category?>(null) + parents, parents.firstOrNull { it.id == parentId },
                        { it?.name ?: tr("None — main category") }, { parentId = it?.id })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(c.copy(id = c.id.ifEmpty { newId() }, name = name.trim(), parentId = parentId, defaultAccountId = defaultAccount))
            }) { Text(tr("Save"), color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun CategoryDeleteDialog(c: Category, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Delete “{0}”?", c.name)) },
        text = { Text(tr("Sub-categories are removed too. Existing transactions keep their amounts but show as uncategorized.")) },
        confirmButton = { TextButton(onClick = onDelete) { Text(tr("Delete"), color = DangerColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
