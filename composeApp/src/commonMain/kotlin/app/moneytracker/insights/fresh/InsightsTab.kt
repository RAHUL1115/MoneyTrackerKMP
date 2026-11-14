package app.moneytracker.insights.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalBackups
import app.moneytracker.app.LocalCurrency
import app.moneytracker.core.Privacy
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.DatePickerHost
import app.moneytracker.designsystem.classic.MonthPickerDialog
import app.moneytracker.designsystem.classic.label
import app.moneytracker.designsystem.classic.long
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.ChangeBadge
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.HGap
import app.moneytracker.designsystem.fresh.IconTile
import app.moneytracker.designsystem.fresh.MoneyBar
import app.moneytracker.designsystem.fresh.MoneyBars
import app.moneytracker.designsystem.fresh.PeriodStepper
import app.moneytracker.designsystem.fresh.Pill
import app.moneytracker.designsystem.fresh.Segmented
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SheetOption
import app.moneytracker.designsystem.fresh.SheetOptionGroup
import app.moneytracker.designsystem.fresh.TabHeader
import app.moneytracker.insights.InsightsActions
import app.moneytracker.insights.InsightsSide
import app.moneytracker.insights.InsightsSpan
import app.moneytracker.insights.InsightsState
import app.moneytracker.insights.SlotLabel
import app.moneytracker.insights.dayCount
import app.moneytracker.insights.rememberInsightsModel

private fun InsightsSpan.label() = when (this) {
    InsightsSpan.WEEK -> "Week"
    InsightsSpan.MONTH -> "Month"
    InsightsSpan.YEAR -> "Year"
    InsightsSpan.CUSTOM -> "Custom period"
}

private fun InsightsSide.label() = when (this) {
    InsightsSide.SPENDING -> "Spending"
    InsightsSide.INCOME -> "Income"
    InsightsSide.NET -> "Net"
}

@Composable
internal fun InsightsTab() {
    val model = rememberInsightsModel()
    val state by model.state.collectAsState()
    val fileTransfer = LocalBackups.current.files
    InsightsContent(state, model.actions, onExport = { fileTransfer.exportExcel() })
}

/** Which pickers are open on Insights; only the screen itself cares. */
private class InsightsPickers {
    var periodSheet by mutableStateOf(false)
    var monthPicker by mutableStateOf(false)
    var pickStart by mutableStateOf(false)
    var pickEnd by mutableStateOf(false)
}

@Composable
private fun InsightsContent(state: InsightsState, actions: InsightsActions, onExport: () -> Unit) {
    val pickers = remember { InsightsPickers() }
    // The breakdown part that is open; it closes when what the breakdown shows changes.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val range = state.range
    if (pickers.monthPicker) MonthPickerDialog(state.choice.month, state.periods.labelFor(state.today), { pickers.monthPicker = false }) {
        actions.pickMonth(it); pickers.monthPicker = false
    }
    DatePickerHost(pickers.pickStart, range.start, { pickers.pickStart = false }, { it?.let(actions.pickStart) })
    DatePickerHost(pickers.pickEnd, range.endInclusive, { pickers.pickEnd = false }, { it?.let(actions.pickEnd) })

    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("Insights")) {
            IconTile(Glyph.Download, tr("Export to Excel"), onExport)
            // Just the calendar icon; its label says which period is showing.
            IconTile(Glyph.Calendar, tr("Period: {0}", periodTileLabel(state)), { pickers.periodSheet = true })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PeriodControls(state, actions, pickers)
            Segmented(InsightsSide.entries.map { tr(it.label()) }, state.choice.side.ordinal, { actions.pickSide(InsightsSide.entries[it]); openKey = null })
            InsightsHero(state, actions)
            BudgetSummaryCard(state.budgetMonth, state.budget, state.catById)
            BreakdownCard(state, openKey, onGroup = { actions.pickGroup(it); openKey = null }) { key -> openKey = if (openKey == key) null else key }
            TopEntriesCard(state)
            Gap(24.dp)
        }
    }

    if (pickers.periodSheet) PeriodSheet(state, onDismiss = { pickers.periodSheet = false }) { s ->
        actions.pickSpan(s); pickers.periodSheet = false; openKey = null
        if (s == InsightsSpan.CUSTOM) pickers.pickStart = true
    }
}

private fun periodTileLabel(state: InsightsState): String {
    val ym = state.choice.month
    return when (state.choice.span) {
        InsightsSpan.WEEK -> tr("Week")
        InsightsSpan.MONTH -> monthShort(ym.month) + " " + ym.year
        InsightsSpan.YEAR -> ym.year.toString()
        InsightsSpan.CUSTOM -> tr("Custom")
    }
}

/** The period stepper, and the From / To dates for a custom period. */
@Composable
private fun PeriodControls(state: InsightsState, actions: InsightsActions, pickers: InsightsPickers) {
    val span = state.choice.span
    val ym = state.choice.month
    val range = state.range
    PeriodStepper(
        when (span) { InsightsSpan.WEEK, InsightsSpan.CUSTOM -> range.label(); InsightsSpan.MONTH -> ym.label(); InsightsSpan.YEAR -> ym.year.toString() },
        { actions.step(-1) }, { actions.step(1) },
        onLabel = when (span) {
            InsightsSpan.MONTH, InsightsSpan.YEAR -> ({ pickers.monthPicker = true })
            InsightsSpan.CUSTOM -> ({ pickers.pickStart = true })
            InsightsSpan.WEEK -> null
        },
        sub = if (span == InsightsSpan.MONTH && state.periods.startDay != 1) range.label() else null,
    )
    if (span == InsightsSpan.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(tr("From {0}", range.start.long()), false, { pickers.pickStart = true }, Modifier.weight(1f))
        Pill(tr("To {0}", range.endInclusive.long()), false, { pickers.pickEnd = true }, Modifier.weight(1f))
    }
}

/** "this week", "in March 2026": the shown period inside a sentence. */
internal fun periodPhrase(state: InsightsState): String {
    val ym = state.choice.month
    return when (state.choice.span) {
        InsightsSpan.WEEK -> if (state.today in state.range) tr("this week") else state.range.label()
        InsightsSpan.MONTH -> tr("in {0}", ym.label())
        InsightsSpan.YEAR -> tr("in {0}", ym.year.toString())
        InsightsSpan.CUSTOM -> state.range.label()
    }
}

/** What the change is measured against: "last week", "Feb", "2025". */
private fun againstLabel(state: InsightsState): String {
    val ym = state.choice.month
    return when (state.choice.span) {
        InsightsSpan.WEEK -> tr("last week")
        InsightsSpan.MONTH -> monthShort(ym.plus(-1).month)
        InsightsSpan.YEAR -> (ym.year - 1).toString()
        InsightsSpan.CUSTOM -> tr("previous period")
    }
}

/** Hero: total, change, trend, daily average. While amounts are hidden, a tap peeks at them. */
@Composable
private fun InsightsHero(state: InsightsState, actions: InsightsActions) {
    val currency = LocalCurrency.current
    val side = state.choice.side
    val phrase = periodPhrase(state)
    FCard(onClick = if (Privacy.masked) ({ Privacy.peek() }) else null) {
        Text(when (side) { InsightsSide.SPENDING -> tr("Spent {0}", phrase); InsightsSide.INCOME -> tr("Earned {0}", phrase); InsightsSide.NET -> tr("Net {0}", phrase) },
            color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(currency.format(state.total), color = if (side == InsightsSide.NET) (if (state.total < 0) F.expense else F.income) else F.ink,
            fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
        Gap(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChangeBadge(state.total, state.previousTotal, upIsGood = side != InsightsSide.SPENDING, against = againstLabel(state), signedValues = side == InsightsSide.NET)
            HGap(8.dp)
            Text(currency.format(state.previousTotal), color = F.muted, fontSize = 12.sp, style = F.money, maxLines = 1)
        }
        Gap(16.dp)
        MoneyBars(state.slots.mapIndexed { i, s -> MoneyBar(slotLabel(s.label), state.slotValues[i]) }, currency.decimals, highlight = state.slotHighlight,
            signed = side == InsightsSide.NET, onTap = actions.openSlot)
        Gap(14.dp)
        HeroStats(state)
    }
}

private fun slotLabel(label: SlotLabel) = when (label) {
    is SlotLabel.Day -> label.date.short()
    is SlotLabel.Month -> monthShort(label.month)
    is SlotLabel.Year -> label.year.toString()
}

/** Daily average and a second figure as two soft tiles. */
@Composable
private fun HeroStats(state: InsightsState) {
    val currency = LocalCurrency.current
    val projected = state.projected
    val monthlyAverage = state.monthlyAverage
    val net = state.totals.net
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(tr("Daily average"), currency.format(state.dailyAverage), Modifier.weight(1f))
        when {
            state.choice.side == InsightsSide.NET -> Stat(tr("Saved of income"), state.savedPercent?.let { "$it%" } ?: "–", Modifier.weight(1f))
            projected != null -> Stat(tr("Projected"), currency.format(projected), Modifier.weight(1f))
            monthlyAverage != null -> Stat(tr("Monthly average"), currency.format(monthlyAverage), Modifier.weight(1f))
            else -> Stat(tr("Net"), currency.format(net), Modifier.weight(1f), if (net < 0) F.expense else F.income)
        }
    }
}

@Composable
private fun PeriodSheet(state: InsightsState, onDismiss: () -> Unit, onPick: (InsightsSpan) -> Unit) {
    val ym = state.choice.month
    Sheet(tr("Period"), onDismiss) {
        SheetOptionGroup {
            InsightsSpan.entries.forEach { s ->
                val subtitle = when (s) {
                    InsightsSpan.MONTH -> ym.label()
                    InsightsSpan.YEAR -> ym.year.toString()
                    InsightsSpan.WEEK, InsightsSpan.CUSTOM -> state.choice.copy(span = s).range(state.periods).label()
                }
                SheetOption(tr(s.label()), s == state.choice.span, subtitle = subtitle) { onPick(s) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = F.ink) {
    Column(modifier.clip(F.controlShape).background(F.page).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}
