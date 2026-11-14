package app.moneytracker.activity.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.FilterChoices
import app.moneytracker.activity.FilterChoicesModel
import app.moneytracker.activity.FilterLine
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.AccentTint
import app.moneytracker.designsystem.classic.FillColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.UnderlineTabs
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Txn

/**
 * Trans.-tab filter. Within a dimension any checked item matches; across dimensions all must match
 * (e.g. Food + Cash = food bought with cash). Empty = no filter.
 */
data class TxnFilter(
    val categoryIds: Set<String> = emptySet(),
    val accountIds: Set<String> = emptySet(),
    /** Any of these tags (as in search); set from search's "Apply filter to Activity". */
    val tagIds: Set<String> = emptySet(),
) {
    val isActive get() = categoryIds.isNotEmpty() || accountIds.isNotEmpty() || tagIds.isNotEmpty()

    /** [tagged]: ids of entries carrying any of [tagIds] (looked up by the caller). */
    fun matches(t: Txn, catById: Map<String, Category>, tagged: Set<String> = emptySet()): Boolean =
        (tagIds.isEmpty() || t.id in tagged) && matchesCategory(t, catById) && matchesAccount(t)

    /** A checked category matches its sub-categories too. */
    private fun matchesCategory(t: Txn, catById: Map<String, Category>): Boolean {
        if (categoryIds.isEmpty()) return true
        val c = t.categoryId?.let { catById[it] } ?: return false
        return c.id in categoryIds || c.parentId in categoryIds
    }

    /** A transfer matches from either side. */
    private fun matchesAccount(t: Txn) = accountIds.isEmpty() || t.accountId in accountIds || t.toAccountId in accountIds
}

/** Name lookups for [TxnFilter.names]; [liveCategoryIds] are the categories not deleted. */
class FilterNames(
    val catById: Map<String, Category>,
    val liveCategoryIds: Set<String>,
    val accountName: Map<String, String>,
    val tagName: Map<String, String>,
)

/** What the filter bar lists: categories, accounts, then tags as #name. */
fun TxnFilter.names(n: FilterNames, includeDeletedCategories: Boolean = true): List<String> =
    categoryIds.filter { includeDeletedCategories || it in n.liveCategoryIds }.mapNotNull { n.catById[it]?.name } +
        accountIds.mapNotNull { n.accountName[it] } + tagIds.mapNotNull { n.tagName[it]?.let { name -> "#$name" } }

/** Bar shown under the tabs while a filter is applied. */
@Composable
fun FilterBar(names: List<String>, onEdit: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AccentTint).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Filter: {0}", names.joinToString(", ")), Modifier.weight(1f), color = Accent, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tr("Edit"), Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onEdit).padding(horizontal = 10.dp, vertical = 10.dp),
            color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        IconButton(onClick = onClear) { Icon(Icons.Filled.Close, tr("Clear filter"), tint = Accent) }
    }
}

/** Income / Expenses / Account checklists with this period's amounts; Apply sets the filter. */
@Composable
fun FilterSheet(range: DateRange, initial: TxnFilter, onDismiss: () -> Unit, onApply: (TxnFilter) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val model = remember(repo, range) { FilterChoicesModel(range, repo.txnStore, repo.categoryStore, repo.accountStore, scope) }
    val choices by model.state.collectAsState()
    FilterSheetContent(choices, initial, onDismiss, onApply)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheetContent(choices: FilterChoices, initial: TxnFilter, onDismiss: () -> Unit, onApply: (TxnFilter) -> Unit) {
    var cats by remember { mutableStateOf(initial.categoryIds) }
    var accs by remember { mutableStateOf(initial.accountIds) }
    var tags by remember { mutableStateOf(initial.tagIds) }   // not editable here; kept unless cleared
    var tab by remember { mutableStateOf(1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = SurfaceColor) {
        Column(Modifier.navigationBarsPadding()) {
            Text(tr("Filter"), Modifier.padding(horizontal = 20.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(8.dp))
            UnderlineTabs(listOf(tr("Income"), tr("Expenses"), tr("Account")), tab) { tab = it }
            val selected = if (tab == 2) accs else cats
            FilterChecklist(choices.forTab(tab), selected) { id ->
                val on = id in selected
                if (tab == 2) accs = if (on) accs - id else accs + id else cats = if (on) cats - id else cats + id
            }
            FilterSheetButtons(
                onClear = { cats = emptySet(); accs = emptySet(); tags = emptySet() },
                onApply = { onApply(TxnFilter(cats, accs, tags)) },
            )
        }
    }
}

@Composable
private fun FilterChecklist(lines: List<FilterLine>, selected: Set<String>, onToggle: (String) -> Unit) {
    val currency = LocalCurrency.current
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(lines, key = { it.id }) { l ->
            Row(
                Modifier.fillMaxWidth().toggleable(l.id in selected, role = Role.Checkbox) { onToggle(l.id) }.padding(start = 8.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(l.id in selected, null, colors = CheckboxDefaults.colors(checkedColor = Accent), modifier = Modifier.padding(12.dp))
                Text(l.label, Modifier.weight(1f), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(l.amount?.let { currency.format(it) } ?: "", color = SubtleText, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FilterSheetButtons(onClear: () -> Unit, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(FillColor).clickable(role = Role.Button, onClick = onClear),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Clear"), color = Ink, fontWeight = FontWeight.Medium) }
        Box(
            Modifier.weight(2f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Accent).clickable(role = Role.Button, onClick = onApply),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Filter"), color = Color.White, fontWeight = FontWeight.SemiBold) }
    }
}
