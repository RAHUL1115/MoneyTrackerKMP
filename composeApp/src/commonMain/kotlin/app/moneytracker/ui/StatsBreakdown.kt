package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.AccentTint
import app.moneytracker.designsystem.classic.DonutChart
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.FillColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.card
import app.moneytracker.designsystem.classic.label
import app.moneytracker.designsystem.classic.short
import app.moneytracker.domain.TxnKind
import kotlin.math.roundToInt

// Classic Stats: the breakdown card and the top entries.

private fun InsightsGroup.label() = when (this) {
    InsightsGroup.CATEGORY -> "Category"
    InsightsGroup.ACCOUNT -> "Account"
    InsightsGroup.TAG -> "Tag"
}

/** Space above the first and below the last line of a classic stats card. */
private val CARD_SPACING = 4.dp

internal fun statsFlowLabel(state: InsightsState) = if (state.choice.side == InsightsSide.INCOME) tr("Income") else tr("Expenses")

@Composable
internal fun StatsTopEntries(state: InsightsState) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val flowColor = if (state.choice.side == InsightsSide.INCOME) IncomeColor else ExpenseColor
    // The card's 4dp top and bottom spacing sit in the title and in the last row's highlight.
    Column(Modifier.padding(top = 10.dp).card().fillMaxWidth()) {
        Text(tr("Top entries"), Modifier.padding(start = 16.dp, top = CARD_SPACING + 10.dp, bottom = 4.dp), color = Ink, fontWeight = FontWeight.Medium)
        state.top.forEachIndexed { i, r ->
            val title = r.note.ifBlank {
                if (r.kind == TxnKind.TRANSFER_OUT) statsKeyName(TRANSFERS_KEY, state.catById) else r.categoryId?.let { state.catById[it]?.name } ?: tr("Uncategorized")
            }
            Row(
                Modifier.fillMaxWidth().clickable { nav.push(Screen.EditTxn(txnId = r.txnId)) }
                    .padding(bottom = if (i == state.top.lastIndex) CARD_SPACING else 0.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(r.date?.short(), state.accountName[r.accountId]).joinToString(" · "), color = SubtleText, fontSize = 12.sp, maxLines = 1)
                }
                Text(currency.format(r.amount), Modifier.padding(start = 12.dp), color = flowColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Breakdown: grouping switch, donut and one row per part. */
@Composable
internal fun StatsBreakdownCard(state: InsightsState, actions: InsightsActions) {
    val groupBy = state.choice.groupBy
    val footnote = state.parts.isNotEmpty() && groupBy == InsightsGroup.TAG
    // The card's 4dp top and bottom spacing sit in the title row and, when it ends the card, in the last part's highlight.
    Column(Modifier.padding(top = 10.dp).card().fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = CARD_SPACING + 10.dp, bottom = 4.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("Breakdown"), Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
            InsightsGroup.entries.forEach { g ->
                val sel = g == groupBy
                Text(tr(g.label()),
                    Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(if (sel) AccentTint else FillColor)
                        .selectable(sel, role = Role.RadioButton) { actions.pickGroup(g) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (sel) Accent else InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (state.parts.isEmpty()) {
            Text(tr("No data available."), Modifier.fillMaxWidth().padding(vertical = 32.dp), color = SubtleText, textAlign = TextAlign.Center)
        } else {
            StatsSlices(state, lastRowBottom = if (footnote) 0.dp else CARD_SPACING)
            if (footnote) Text(tr("An entry with several tags counts once for each tag."),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = SubtleText, fontSize = 11.sp)
        }
        if (state.parts.isEmpty() || footnote) Spacer(Modifier.height(CARD_SPACING))
    }
}

/** Tapping a slice (or its row) shows that part in the middle; the selection clears when the data changes. */
@Composable
private fun StatsSlices(state: InsightsState, lastRowBottom: Dp) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val slices = state.parts
    val income = state.choice.side == InsightsSide.INCOME
    val names = slices.map { breakdownName(it, state.accountName) { key -> statsKeyName(key, state.catById) } }
    var picked by remember(slices.map { it.key }) { mutableStateOf<Int?>(null) }
    // A category can end up below zero when refunds outweigh spending; it gets no slice.
    DonutChart(slices.map { it.amount.coerceAtLeast(0) }, Modifier.fillMaxWidth().height(220.dp).padding(vertical = 12.dp), id = "donut",
        selected = picked, onSelect = { picked = it }) {
        DonutCenter(state, picked, names)
    }
    slices.forEachIndexed { i, sl ->
        val range = state.range
        val open: (() -> Unit)? = when (sl.link) {
            BreakdownLink.CATEGORY_ENTRIES -> ({ nav.push(Screen.StatsDetail(sl.key, income, range.start, range.endInclusive, names[i])) })
            BreakdownLink.ACCOUNT -> ({ nav.push(Screen.AccountDetail(sl.key)) })
            null -> null
        }
        SliceRow(sl, names[i], ChartColors[i % ChartColors.size], state.total, income, currency, highlighted = picked == i,
            dimmed = picked != null && picked != i, open = open, extraBottom = if (i == slices.lastIndex) lastRowBottom else 0.dp)
    }
}

/** The donut's middle: the total, or the picked part with its share and change. */
@Composable
private fun DonutCenter(state: InsightsState, picked: Int?, names: List<String>) {
    val currency = LocalCurrency.current
    val sel = picked?.let { state.parts.getOrNull(it) }
    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (picked == null || sel == null) {
            Text(currency.format(animatedAmount(state.total, "donut")), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(statsFlowLabel(state), color = SubtleText, fontSize = 12.sp)
        } else {
            val pct = if (state.total > 0) (sel.amount * 100.0 / state.total).roundToInt() else 0
            Text(names[picked], color = ChartColors[picked % ChartColors.size], fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(currency.format(sel.amount), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(if (pct == 0 && sel.amount > 0) "<1%" else "$pct%", color = SubtleText, fontSize = 12.sp)
            if (sel.previous > 0) ChangeChip(sel.amount, sel.previous, upIsGood = state.choice.side == InsightsSide.INCOME, compact = true)
        }
    }
}
