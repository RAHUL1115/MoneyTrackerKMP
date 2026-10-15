package app.moneytracker.ui

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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.flow.map

/**
 * Trans.-tab filter. Within a dimension any checked item matches; across dimensions all must match
 * (e.g. Food + Cash = food bought with cash). Empty = no filter.
 */
data class TxnFilter(val categoryIds: Set<String> = emptySet(), val accountIds: Set<String> = emptySet()) {
    val isActive get() = categoryIds.isNotEmpty() || accountIds.isNotEmpty()

    fun matches(t: Txn, catById: Map<String, Category>): Boolean {
        if (categoryIds.isNotEmpty()) {
            val c = t.categoryId?.let { catById[it] } ?: return false
            if (c.id !in categoryIds && c.parentId !in categoryIds) return false
        }
        if (accountIds.isNotEmpty() && t.accountId !in accountIds && t.toAccountId !in accountIds) return false
        return true
    }
}

val LocalTxnFilter = compositionLocalOf { TxnFilter() }

/**
 * Entries in [range] for the Trans. tab, narrowed by the active filter. Balance adjustments are left out,
 * they only appear on their account's page.
 */
@Composable
fun rememberFilteredTxns(range: DateRange): State<List<Txn>> {
    val repo = LocalRepo.current
    val filter = LocalTxnFilter.current
    val categories by repo.categories.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    return remember(range, filter, catById) {
        repo.txnsBetween(range).map { list ->
            list.filter { it.kind != TxnKind.ADJUST_UP && it.kind != TxnKind.ADJUST_DOWN && (!filter.isActive || filter.matches(it, catById)) }
        }
    }.collectAsState(emptyList())
}

/** Report rows for [range]; with a filter they are rebuilt from the matching entries. */
@Composable
fun rememberFilteredRows(range: DateRange): State<List<ReportRow>> {
    val repo = LocalRepo.current
    val filter = LocalTxnFilter.current
    if (!filter.isActive) return remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val flags = accounts.associate { it.id to it.transferAsExpense }
    val txns by rememberFilteredTxns(range)
    // TRANSFER_IN mirrors TRANSFER_OUT; report rows count each transfer once.
    return androidx.compose.runtime.rememberUpdatedState(txns.filter { it.kind != TxnKind.TRANSFER_IN }.map { it.toReportRow(flags) })
}

/** Bar shown under the tabs while a filter is applied. */
@Composable
fun FilterBar(filter: TxnFilter, catById: Map<String, Category>, accountName: Map<String, String>, onEdit: () -> Unit, onClear: () -> Unit) {
    val names = filter.categoryIds.mapNotNull { catById[it]?.name } + filter.accountIds.mapNotNull { accountName[it] }
    Row(
        Modifier.fillMaxWidth().background(AccentTint).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Filter: {0}", names.joinToString(", ")), Modifier.weight(1f), color = Accent, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tr("Edit"), Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 10.dp),
            color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        IconButton(onClick = onClear) { Icon(Icons.Filled.Close, tr("Clear filter"), tint = Accent) }
    }
}

/** Income / Expenses / Account checklists with this period's amounts; Apply sets the filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(range: DateRange, initial: TxnFilter, onDismiss: () -> Unit, onApply: (TxnFilter) -> Unit) {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val accountNames by repo.accountNames.collectAsState(emptyMap())
    val txns by remember(range) { repo.txnsBetween(range) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    var cats by remember { mutableStateOf(initial.categoryIds) }
    var accs by remember { mutableStateOf(initial.accountIds) }
    var tab by remember { mutableStateOf(1) }
    val mainOf = { t: Txn -> t.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } }
    val perCategory = txns.filter { it.kind == TxnKind.INCOME || it.kind == TxnKind.EXPENSE }.groupBy { mainOf(it) }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val perAccount = txns.groupBy { it.accountId }.mapValues { (_, v) -> v.sumOf { it.signedAccountValue } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = SurfaceColor) {
        Column(Modifier.navigationBarsPadding()) {
            Text(tr("Filter"), Modifier.padding(horizontal = 20.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(8.dp))
            UnderlineTabs(listOf(tr("Income"), tr("Expenses"), tr("Account")), tab) { tab = it }
            data class Line(val id: String, val label: String, val amount: Long?)
            val lines = when (tab) {
                0, 1 -> {
                    val kind = if (tab == 0) CategoryKind.INCOME else CategoryKind.EXPENSE
                    // Deleted categories still show while they have entries in this period.
                    val deleted = perCategory.keys.mapNotNull { id -> id?.let { catById[it] } }
                        .filter { it.kind == kind && it.parentId == null && categories.none { c -> c.id == it.id } }
                    (categories.filter { it.kind == kind && it.parentId == null } + deleted).map { Line(it.id, it.name, perCategory[it.id]) }
                }
                // Hidden and deleted accounts show while they have entries in this period.
                else -> accounts.filter { !it.hidden || it.id in perAccount }.map { Line(it.id, it.name, perAccount[it.id]) } +
                    perAccount.keys.filter { id -> accounts.none { it.id == id } }.map { Line(it, accountNames[it] ?: it, perAccount[it]) }
            }
            val selected = if (tab == 2) accs else cats
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(lines, key = { it.id }) { l ->
                    val on = l.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (tab == 2) accs = if (on) accs - l.id else accs + l.id else cats = if (on) cats - l.id else cats + l.id
                        }.padding(start = 8.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Accent), modifier = Modifier.padding(12.dp))
                        Text(l.label, Modifier.weight(1f), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l.amount?.let { currency.format(it) } ?: "", color = SubtleText, fontSize = 13.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(FillColor)
                        .clickable { cats = emptySet(); accs = emptySet() },
                    contentAlignment = Alignment.Center,
                ) { Text(tr("Clear"), color = Ink, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(2f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Accent)
                        .clickable { onApply(TxnFilter(cats, accs)) },
                    contentAlignment = Alignment.Center,
                ) { Text(tr("Filter"), color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
