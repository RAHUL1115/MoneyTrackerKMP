package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.newId
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.ui.CategoriesModel
import app.moneytracker.ui.CategoriesState
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.tr

/** What the Categories screen asks of its holder. */
private class CategoryActions(
    val move: (Category, Int) -> Unit,
    /** The category as it was before (id empty for a new one) and as saved. */
    val save: (before: Category, saved: Category) -> Unit,
    val delete: (Category) -> Unit,
    val turnOnSubcategories: () -> Unit,
)

@Composable
internal fun FreshCategories(kind: CategoryKind) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) { CategoriesModel(repo.categoryStore, repo.accountStore, repo.settingsStore, repo.io, scope) }
    val state by model.state.collectAsState()
    val actions = CategoryActions(
        move = model::move,
        save = { before, saved ->
            model.save(saved)
            if (before.id.isEmpty()) toaster.show(tr("Added {0}", saved.name)) { model.delete(saved) }
            else toaster.show(tr("Saved {0}", saved.name)) { model.save(before) }
        },
        delete = { c ->
            val children = state.childrenOf(c)
            model.delete(c)
            toaster.show(tr("{0} deleted", c.name)) { model.restore(c, children) }
        },
        turnOnSubcategories = model::turnOnSubcategories,
    )
    CategoriesContent(state, kind, onBack = nav::pop, actions)
}

@Composable
private fun CategoriesContent(state: CategoriesState, kind: CategoryKind, onBack: () -> Unit, actions: CategoryActions) {
    var shown by rememberSaveable { mutableStateOf(kind) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    val categories = state.categories

    SubScreen(tr("Categories"), onBack, add = tr("Add category") to { editing = state.blank(shown) }) {
        val kinds = listOf(CategoryKind.EXPENSE, CategoryKind.INCOME)
        Segmented(
            kinds.map { k -> (if (k == CategoryKind.EXPENSE) tr("Expense") else tr("Income")) + " · " + categories.count { it.kind == k && it.parentId == null } },
            kinds.indexOf(shown), { shown = kinds[it] },
        )
        Text(tr("Tap a category to rename it, move it under another or set its usual account. Arrows change the order on the Add screen."),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp)
        val list = state.shown(shown)
        if (list.isEmpty()) EmptyCard(tr("No categories yet."), tr("Add category")) { editing = state.blank(shown) }
        else CategoryListCard(state, list, onEdit = { editing = it }, onMove = actions.move)
        if (!state.useSubcategories) SubcategoriesOffCard(actions.turnOnSubcategories)
    }

    editing?.let { c ->
        CategorySheet(c, state, onDismiss = { editing = null }, onDelete = { deleting = c }) { saved -> editing = null; actions.save(c, saved) }
    }
    deleting?.let { c ->
        val children = state.childrenOf(c)
        Confirm(
            tr("Delete “{0}”?", c.name),
            (if (children.isNotEmpty()) tr("Its {0} sub-categories are removed too.", children.size) + " " else "") +
                tr("Existing transactions keep their amounts but show as uncategorized."),
            tr("Delete"), { deleting = null },
        ) {
            editing = null
            actions.delete(c)
        }
    }
}

@Composable
private fun CategoryListCard(state: CategoriesState, list: List<Category>, onEdit: (Category) -> Unit, onMove: (Category, Int) -> Unit) {
    FListCard(verticalPadding = 4.dp) {
        list.forEachIndexed { i, c ->
            if (i > 0) Divider(inset = ROW_INSET)
            // Reorder among siblings (main categories, or the subs of one main).
            val siblings = state.siblingsOf(c)
            val at = siblings.indexOf(c)
            val indent = if (c.parentId != null) 28.dp else 0.dp
            Row(Modifier.fillMaxWidth().padding(start = ROW_INSET + indent, end = ROW_INSET), verticalAlignment = Alignment.CenterVertically) {
                CategoryLine(c, categoryMeta(c, state), Modifier.weight(1f)) { onEdit(c) }
                ArrowButton(Glyph.ChevronUp, tr("Move {0} up", c.name), at > 0) { onMove(c, -1) }
                HGap(6.dp)
                ArrowButton(Glyph.ChevronDown, tr("Move {0} down", c.name), at < siblings.lastIndex) { onMove(c, 1) }
            }
        }
    }
}

/** Category rows start 12dp in from their card's edge. */
private val ROW_INSET = 12.dp

/** "in Food · Usual: Cash", "3 sub-categories" or "No usual account". */
private fun categoryMeta(c: Category, state: CategoriesState): String {
    val subs = state.childrenOf(c).size
    val parent = c.parentId?.let { p -> state.categories.firstOrNull { it.id == p } }
    val usual = c.defaultAccountId?.let { state.accountNames[it] }
    return listOfNotNull(
        parent?.let { tr("in {0}", it.name) } ?: if (subs > 0 && state.useSubcategories) tr("{0} sub-categories", subs) else null,
        usual?.let { tr("Usual: {0}", it) },
    ).joinToString(" · ").ifEmpty { tr("No usual account") }
}

@Composable
private fun CategoryLine(c: Category, meta: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(F.iconShape).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mark(c.name, if (c.parentId != null) 26.dp else 34.dp)
        Column(Modifier.weight(1f)) {
            Text(c.name, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SubcategoriesOffCard(onTurnOn: () -> Unit) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Sub-categories are off, so Add shows only main categories."), Modifier.weight(1f), color = F.muted, fontSize = 13.sp)
            StgTextButton(tr("Turn on"), onTurnOn)
        }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(F.innerShape).background(F.page).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) F.ink else F.muted.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) }
}

@Composable
private fun CategorySheet(c: Category, state: CategoriesState, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Category) -> Unit) {
    var name by remember(c) { mutableStateOf(c.name) }
    var parentId by remember(c) { mutableStateOf(c.parentId) }
    var defaultAccount by remember(c) { mutableStateOf(c.defaultAccountId) }
    val isNew = c.id.isEmpty()
    val trimmed = name.trim()
    val duplicate = state.isDuplicate(c, trimmed, parentId)
    val kindName = if (c.kind == CategoryKind.INCOME) tr("New income category") else tr("New expense category")

    Sheet(if (isNew) kindName else tr("Edit {0}", c.name), onDismiss, bottom = {
        CategorySheetButtons(isNew, enabled = trimmed.isNotEmpty() && !duplicate, onDelete) {
            onSave(c.copy(id = c.id.ifEmpty { newId() }, name = trimmed, parentId = parentId, defaultAccountId = defaultAccount))
        }
    }) {
        StgField(name, { name = it }, label = tr("Name (emoji welcome 🍜)"), placeholder = tr("e.g. Pets"), error = duplicate)
        if (duplicate) Text(tr("There is already a category called {0}", trimmed), color = F.danger, fontSize = 12.sp)
        if (state.useSubcategories) ParentChips(c, state, parentId) { parentId = it }
        StgLabel(tr("Usual account · picked for you on Add"))
        UsualAccountChips(state, parentId, defaultAccount) { defaultAccount = it }
    }
}

@Composable
private fun CategorySheetButtons(isNew: Boolean, enabled: Boolean, onDelete: () -> Unit, onSave: () -> Unit) {
    val deleteLabel = tr("Delete category")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!isNew) Box(
            Modifier.size(52.dp).clip(F.buttonShape).background(F.dangerTint).clickable(role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = deleteLabel },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Delete, null, tint = F.danger) }
        PrimaryButton(if (isNew) tr("Add category") else tr("Save changes"), enabled = enabled, modifier = Modifier.weight(1f), onClick = onSave)
    }
}

/** The main category to sit under; a main category that has subs stays a main one. */
@Composable
private fun ParentChips(c: Category, state: CategoriesState, parentId: String?, onPick: (String?) -> Unit) {
    val parents = state.categories.filter { it.kind == c.kind && it.parentId == null && it.id != c.id }
    StgLabel(tr("Parent (optional)"))
    if (state.childrenOf(c).isNotEmpty()) StgChips(listOf(Unit), Unit, { tr("Main category (has sub-categories)") }) {}
    else StgChips(listOf<Category?>(null) + parents, parents.firstOrNull { it.id == parentId }, { it?.name ?: tr("None, main category") }) { onPick(it?.id) }
}

/** The usual account; "none" says when the main category's usual account applies instead. */
@Composable
private fun UsualAccountChips(state: CategoriesState, parentId: String?, defaultAccount: String?, onPick: (String?) -> Unit) {
    val accountNames = state.accountNames
    val inherited = parentId?.let { p -> state.categories.firstOrNull { it.id == p }?.defaultAccountId }?.let { accountNames[it] }
    val options = listOf<String?>(null) + state.accounts.filter { !it.hidden || it.id == defaultAccount }.map { it.id }
    StgChips(options, defaultAccount, { id ->
        id?.let { accountNames[it] } ?: inherited?.let { tr("Same as main category ({0})", it) } ?: tr("None")
    }) { onPick(it) }
}
