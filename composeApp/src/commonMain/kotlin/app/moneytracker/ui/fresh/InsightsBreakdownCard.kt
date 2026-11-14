package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.tr
import app.moneytracker.domain.Category
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.TxnKind
import app.moneytracker.ui.BreakdownLink
import app.moneytracker.ui.BreakdownPart
import app.moneytracker.ui.InsightsGroup
import app.moneytracker.ui.InsightsSide
import app.moneytracker.ui.InsightsState
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.NO_TAG_KEY
import app.moneytracker.ui.Screen
import app.moneytracker.ui.TRANSFERS_KEY
import app.moneytracker.ui.UNCATEGORIZED_KEY
import app.moneytracker.ui.breakdownName
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.short
import kotlin.math.roundToInt

private fun InsightsGroup.label() = when (this) {
    InsightsGroup.CATEGORY -> "Category"
    InsightsGroup.ACCOUNT -> "Account"
    InsightsGroup.TAG -> "Tag"
}

/** Side padding inside the open breakdown panel. */
private val PANEL_INSET = 12.dp

private fun keyName(key: String, catById: Map<String, Category>) = when (key) {
    TRANSFERS_KEY -> tr("Saved / invested")
    UNCATEGORIZED_KEY -> tr("Uncategorized")
    MODIFIED_BALANCE_CATEGORY -> tr(MODIFIED_BALANCE_NAME)
    else -> catById[key]?.name ?: tr("Deleted category")
}

/** Breakdown by category, account or tag; a tapped part opens to show its top entries. */
@Composable
internal fun BreakdownCard(state: InsightsState, openKey: String?, onGroup: (InsightsGroup) -> Unit, onToggle: (String) -> Unit) {
    val groupBy = state.choice.groupBy
    val income = state.choice.side == InsightsSide.INCOME
    val parts = state.parts
    val partsTotal = parts.sumOf { it.amount.coerceAtLeast(0) }
    val footnote = groupBy == InsightsGroup.TAG && parts.isNotEmpty()
    // The last part row takes the card's bottom spacing into its highlight, unless something comes after it.
    val lastRowEndsCard = parts.isNotEmpty() && !footnote && openKey != parts.last().key
    // No side padding on the card: rows pad themselves so their tap highlight spans the card, like Activity's entry rows.
    FListCard {
        // Title with the Category / Account / Tag switch beside it.
        Row(Modifier.padding(start = ENTRY_INSET, end = ENTRY_INSET, top = ENTRY_INSET), verticalAlignment = Alignment.CenterVertically) {
            Text(if (income) tr("Income by") else tr("Spending by"), Modifier.weight(1f), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                InsightsGroup.entries.forEach { g -> GroupPill(tr(g.label()), g == groupBy) { onGroup(g) } }
            }
        }
        Gap(6.dp)
        if (parts.isEmpty()) Text(
            if (income) tr("No income recorded {0}.", periodPhrase(state)) else tr("Nothing spent {0} yet. Entries you add will show up here.", periodPhrase(state)),
            Modifier.padding(horizontal = ENTRY_INSET, vertical = 16.dp), color = F.muted, fontSize = 14.sp,
        )
        val top = parts.maxOfOrNull { it.amount }?.coerceAtLeast(1) ?: 1
        parts.forEachIndexed { i, part ->
            if (i > 0) Divider(ENTRY_INSET)
            val ownsBottom = lastRowEndsCard && i == parts.lastIndex
            PartRow(part, partsTotal, top, state, openKey == part.key, extraBottom = if (ownsBottom) ENTRY_INSET else 0.dp) { onToggle(part.key) }
        }
        if (footnote) Text(tr("An entry with several tags counts once for each tag."),
            Modifier.padding(horizontal = ENTRY_INSET).padding(top = 8.dp), color = F.muted, fontSize = 12.sp)
        if (!lastRowEndsCard) Gap(ENTRY_INSET)
    }
}

/** The biggest single entries of the period. */
@Composable
internal fun TopEntriesCard(state: InsightsState) {
    if (state.top.isNotEmpty()) FListCard {
        Box(Modifier.padding(start = ENTRY_INSET, end = ENTRY_INSET, top = ENTRY_INSET)) { CardTitle(tr("Top entries")) }
        // The last row takes the card's bottom spacing into its highlight.
        state.top.forEachIndexed { i, r ->
            EntryLine(r, state.accountName, state.catById, inset = ENTRY_INSET, extraBottom = if (i == state.top.lastIndex) ENTRY_INSET else 0.dp)
        }
    }
}

/** Small unbordered pill for the breakdown switch: page grey, accent tint when selected. */
@Composable
private fun GroupPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(F.pillShape).background(if (selected) F.accentTint else F.page).choiceTap(selected, Role.RadioButton, onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
private fun PartRow(part: BreakdownPart, total: Long, top: Long, state: InsightsState, open: Boolean, extraBottom: Dp, onTap: () -> Unit) {
    val name = breakdownName(part, state.accountName) { keyName(it, state.catById) }
    Column(Modifier.fillMaxWidth().expandState(open).clickable(onClick = onTap).padding(bottom = extraBottom).padding(horizontal = ENTRY_INSET, vertical = 10.dp)) {
        PartHeadline(part, name, total, open)
        Gap(8.dp)
        Bar(part.amount.coerceAtLeast(0).toFloat() / top, Modifier.padding(start = 38.dp), fill = if (state.choice.side == InsightsSide.INCOME) F.income else F.accent, height = 6.dp)
    }
    if (open) PartEntries(part, name, state)
}

/** Mark, name with its share, change and refunds, amount and the open / closed chevron. */
@Composable
private fun PartHeadline(part: BreakdownPart, name: String, total: Long, open: Boolean) {
    val currency = LocalCurrency.current
    val pct = if (total > 0 && part.amount > 0) (part.amount * 100.0 / total).roundToInt() else null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mark(if (part.group == InsightsGroup.TAG && part.key != NO_TAG_KEY) "# $name" else name, 28.dp)
        HGap(10.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pct != null) Text(if (pct == 0) "<1%" else "$pct%", Modifier.padding(start = 6.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            val notes = listOfNotNull(
                if (part.previous > 0) changeText(part.amount, part.previous) else null,
                if (part.refunded > 0) tr("incl. {0} refunded", currency.format(part.refunded)) else null,
            )
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = F.muted, fontSize = 12.sp, maxLines = 1)
        }
        Text(currency.format(part.amount), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
        Icon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** The open part: its three biggest entries and a link to all of them (or to the account). */
@Composable
private fun PartEntries(part: BreakdownPart, name: String, state: InsightsState) {
    val nav = LocalNavigator.current
    val range = state.range
    val seeAll: (() -> Unit)? = when (part.link) {
        BreakdownLink.CATEGORY_ENTRIES -> ({ nav.push(Screen.StatsDetail(part.key, state.choice.side == InsightsSide.INCOME, range.start, range.endInclusive, name)) })
        BreakdownLink.ACCOUNT -> ({ nav.push(Screen.AccountDetail(part.key)) })
        null -> null
    }
    // No side padding on the panel: its entry rows pad themselves so their highlight spans the panel.
    Column(Modifier.fillMaxWidth().padding(horizontal = ENTRY_INSET).padding(bottom = 8.dp).clip(F.optionShape).background(F.fill).padding(vertical = 8.dp)) {
        Text(tr("Top entries").uppercase(), Modifier.padding(horizontal = PANEL_INSET), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        part.entries.sortedByDescending { it.amount }.take(3).forEach { r -> EntryLine(r, state.accountName, state.catById, inset = PANEL_INSET) }
        if (seeAll != null) Text(
            if (part.group == InsightsGroup.ACCOUNT) tr("Open account ›") else tr("See all {0} entries ›", part.entries.size),
            Modifier.padding(start = PANEL_INSET, top = 4.dp).clip(F.tagShape).clickable(role = Role.Button, onClick = seeAll).padding(vertical = 6.dp),
            color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
    }
}

private fun changeText(current: Long, previous: Long): String {
    val pct = ((current - previous) * 100.0 / previous).roundToInt()
    return (if (pct > 0) "▲ " else if (pct < 0) "▼ " else "= ") + kotlin.math.abs(pct) + "%"
}

@Composable
private fun EntryLine(r: ReportRow, accountName: Map<String, String>, catById: Map<String, Category>, inset: Dp, extraBottom: Dp = 0.dp) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val refund = r.amount < 0
    val title = r.note.ifBlank { if (r.kind == TxnKind.TRANSFER_OUT) tr("Saved / invested") else categoryPath(r.categoryId, catById) }
    Row(
        // The row pads itself, so its highlight spans the card or the open breakdown panel it sits in.
        Modifier.fillMaxWidth().clickable { nav.push(Screen.EditTxn(txnId = r.txnId)) }.padding(bottom = extraBottom).padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.date?.short(), accountName[r.accountId]).joinToString(" · "), color = F.muted, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text((if (refund) "+" else "") + currency.format(kotlin.math.abs(r.amount)), color = when { refund || r.kind == TxnKind.INCOME -> F.income; r.kind == TxnKind.EXPENSE -> F.expense; else -> F.ink },
            fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
    }
}
