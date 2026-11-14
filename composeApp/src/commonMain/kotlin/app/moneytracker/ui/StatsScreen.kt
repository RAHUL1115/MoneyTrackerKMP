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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.PrivacyEyeButton
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.BarChart
import app.moneytracker.designsystem.classic.ChartMemory
import app.moneytracker.designsystem.classic.DatePickerHost
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.EmptyState
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.LocalChartMemory
import app.moneytracker.designsystem.classic.MonthPickerDialog
import app.moneytracker.designsystem.classic.MonthTopBar
import app.moneytracker.designsystem.classic.PageBackground
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.UnderlineTabs
import app.moneytracker.designsystem.classic.card
import app.moneytracker.designsystem.classic.dotted
import app.moneytracker.designsystem.classic.label
import app.moneytracker.designsystem.classic.long
import app.moneytracker.domain.BudgetLine
import app.moneytracker.domain.Category
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.TxnKind
import app.moneytracker.entry.classic.save
import kotlin.math.roundToInt

/** Chart palette: distinct, readable on white. */
val ChartColors = listOf(
    Color(0xFF14B8A6), Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF0EA5E9),
    Color(0xFF84CC16), Color(0xFFA855F7), Color(0xFFF97316), Color(0xFF06B6D4), Color(0xFF94A3B8),
)

private fun InsightsSpan.label() = when (this) {
    InsightsSpan.WEEK -> "Weekly"
    InsightsSpan.MONTH -> "Monthly"
    InsightsSpan.YEAR -> "Annually"
    InsightsSpan.CUSTOM -> "Period"
}

/** Classic name of a breakdown or budget key. */
internal fun statsKeyName(key: String, catById: Map<String, Category>) = when (key) {
    TRANSFERS_KEY -> tr("Saved / invested")
    UNCATEGORIZED_KEY -> tr("Uncategorized")
    MODIFIED_BALANCE_CATEGORY -> MODIFIED_BALANCE_NAME
    TOTAL_BUDGET -> tr("Total")
    else -> catById[key]?.name ?: tr("Deleted category")
}

@Composable
fun StatsTab() {
    val model = rememberInsightsModel()
    val state by model.state.collectAsState()
    StatsContent(state, model.actions)
}

@Composable
private fun StatsContent(state: InsightsState, actions: InsightsActions) {
    val currency = LocalCurrency.current
    val t = state.totals
    Column(Modifier.fillMaxSize()) {
        StatsPeriodBar(state, actions)
        UnderlineTabs(
            listOf(tr("Income  {0}", currency.format(t.income)), tr("Expenses  {0}", currency.format(t.expense))),
            if (state.choice.side == InsightsSide.INCOME) 0 else 1,
        ) { actions.pickSide(if (it == 0) InsightsSide.INCOME else InsightsSide.SPENDING) }
        val chartMemory = remember { ChartMemory() }
        CompositionLocalProvider(LocalChartMemory provides chartMemory) {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "overview") { OverviewCard(state) }
                item(key = "trend") { TrendCard(state, actions) }
                item(key = "breakdown") { StatsBreakdownCard(state, actions) }
                if (state.top.isNotEmpty()) item(key = "top") { StatsTopEntries(state) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Month bar with the period menu, and for a custom period its two dates. */
@Composable
private fun StatsPeriodBar(state: InsightsState, actions: InsightsActions) {
    val span = state.choice.span
    val ym = state.choice.month
    val range = state.range
    var monthPicker by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    val label = when (span) {
        InsightsSpan.WEEK, InsightsSpan.CUSTOM -> range.dotted()
        InsightsSpan.YEAR -> ym.year.toString()
        InsightsSpan.MONTH -> ym.label()
    }
    if (monthPicker) MonthPickerDialog(ym, state.periods.labelFor(state.today), { monthPicker = false }) { actions.pickMonth(it); monthPicker = false }
    val monthly = span == InsightsSpan.MONTH || span == InsightsSpan.YEAR
    MonthTopBar(label, { actions.step(-1) }, { actions.step(1) }, onLabelClick = if (monthly) ({ monthPicker = true }) else null) {
        PrivacyEyeButton()
        SpanMenu(span, actions.pickSpan)
    }
    if (span == InsightsSpan.CUSTOM) {
        Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(range.start.long(), Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(PageBackground).clickable { pickStart = true }
                .padding(horizontal = 12.dp, vertical = 8.dp), textAlign = TextAlign.Center, fontSize = 13.sp, color = Ink)
            Text("~", Modifier.padding(horizontal = 8.dp), color = SubtleText)
            Text(range.endInclusive.long(), Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(PageBackground).clickable { pickEnd = true }
                .padding(horizontal = 12.dp, vertical = 8.dp), textAlign = TextAlign.Center, fontSize = 13.sp, color = Ink)
        }
    }
    DatePickerHost(pickStart, range.start, { pickStart = false }, { it?.let(actions.pickStart) })
    DatePickerHost(pickEnd, range.endInclusive, { pickEnd = false }, { it?.let(actions.pickEnd) })
}

/** Just the calendar icon; the menu ticks the period in use. */
@Composable
private fun SpanMenu(span: InsightsSpan, onPick: (InsightsSpan) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 4.dp)) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.CalendarMonth, tr("Period: {0}", tr(span.label())), tint = Ink)
        }
        DropdownMenu(open, { open = false }) {
            InsightsSpan.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(tr(s.label()), fontWeight = if (s == span) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = if (s == span) ({ Icon(Icons.Filled.Check, null, tint = Accent) }) else null,
                    onClick = { onPick(s); open = false },
                )
            }
        }
    }
}

/** Total with its change, then the daily average, projection and net. */
@Composable
private fun OverviewCard(state: InsightsState) {
    val currency = LocalCurrency.current
    val income = state.choice.side == InsightsSide.INCOME
    val ym = state.choice.month
    val compareLabel = when (state.choice.span) {
        InsightsSpan.MONTH -> tr("vs {0}", ym.plus(-1).label())
        InsightsSpan.YEAR -> tr("vs {0}", (ym.year - 1).toString())
        InsightsSpan.WEEK, InsightsSpan.CUSTOM -> tr("vs previous period")
    }
    val t = state.totals
    Column(Modifier.padding(top = 10.dp).card().fillMaxWidth().padding(16.dp)) {
        Text(statsFlowLabel(state), color = SubtleText, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currency.format(animatedAmount(state.total, "overview")), Modifier.weight(1f, fill = false), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            ChangeChip(state.total, state.previousTotal, upIsGood = income)
        }
        Text("$compareLabel · ${currency.format(state.previousTotal)}", color = SubtleText, fontSize = 12.sp)
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DividerColor)
        Row {
            MiniStat(tr("Daily average"), currency.format(state.dailyAverage), Ink, Modifier.weight(1f))
            state.projected?.let { MiniStat(tr("Projected"), currency.format(it), Ink, Modifier.weight(1f)) }
            MiniStat(
                tr("Net"), currency.format(t.net), if (t.net >= 0) IncomeColor else ExpenseColor, Modifier.weight(1f),
                note = state.savedPercent?.let { tr("{0}% of income", it.toString()) },
            )
        }
    }
}

@Composable
private fun TrendCard(state: InsightsState, actions: InsightsActions) {
    val trend = statsTrendOf(state)
    val flowColor = if (state.choice.side == InsightsSide.INCOME) IncomeColor else ExpenseColor
    Column(Modifier.padding(top = 10.dp).card().fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text(tr("Trend"), Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
            Text(trend.subtitle, color = SubtleText, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        BarChart(trend.values, trend.labels, flowColor, trend.highlight, id = "trend",
            onBarClick = trend.open?.let { open -> { i: Int -> actions.openMonth(open(i)) } })
    }
}

// ───────────────────────── Budget (classic). Draws the same [BudgetModel] as the new layout's Budget screen.

@Composable
fun BudgetScreen() {
    val model = rememberBudgetModel()
    val state by model.state.collectAsState()
    BudgetSettingContent(state, model.actions, onSave = { key, amount -> model.save(key, amount) })
}

/** Every budget option (total first) with what's spent; a tap opens the amount dialog. */
@Composable
private fun BudgetSettingContent(state: BudgetState, actions: BudgetActions, onSave: (key: String, amount: Long) -> Unit) {
    val nav = LocalNavigator.current
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Budget"), nav::pop)
        MonthTopBar(state.month.label(), { actions.step(-1) }, { actions.step(1) }, statusBar = false)
        HorizontalDivider(color = DividerColor)
        val options = listOf(TOTAL_BUDGET) + state.options.map { it.id }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(tr("Budgets apply from the selected month onward. Tap a category to set its monthly amount."),
                    Modifier.padding(20.dp), color = SubtleText, fontSize = 13.sp)
            }
            items(options, key = { it.ifEmpty { "total" } }) { key ->
                BudgetOptionRow(state, key) { editing = key }
                HorizontalDivider(color = DividerColor)
            }
        }
    }
    editing?.let { key ->
        BudgetAmountDialog(state, key, onDismiss = { editing = null }) { amount -> onSave(key, amount); editing = null }
    }
}

@Composable
private fun BudgetOptionRow(state: BudgetState, key: String, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val line = state.budget.lines.firstOrNull { it.categoryId == key }
    val isSub = state.catById[key]?.parentId != null
    Column(Modifier.fillMaxWidth().background(if (isSub) PageBackground else SurfaceColor).clickable(onClick = onClick)
        .padding(start = if (isSub) 40.dp else 20.dp, end = 20.dp, top = if (isSub) 10.dp else 14.dp, bottom = if (isSub) 10.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statsKeyName(key, state.catById), Modifier.weight(1f), fontWeight = if (key == TOTAL_BUDGET) FontWeight.SemiBold else null,
                color = if (isSub) SubtleText else Ink)
            if (line == null) Text(tr("Set budget"), color = SubtleText)
            else Text("${currency.format(line.spent)} / ${currency.format(line.budget)}")
        }
        if (line != null) {
            BudgetProgress(line)
            Text(leftOrOver(line, currency), Modifier.padding(top = 4.dp), color = SubtleText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BudgetProgress(line: BudgetLine) {
    LinearProgressIndicator(
        progress = { line.ratio.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
        color = if (line.ratio > 1f) ExpenseColor else IncomeColor, trackColor = PageBackground,
    )
}

private fun leftOrOver(line: BudgetLine, currency: CurrencyFormat) =
    if (line.remaining >= 0) tr("{0} left", currency.format(line.remaining)) else tr("{0} over", currency.format(-line.remaining))

/** The monthly amount for [key], from the shown month onward; empty or 0 removes it. */
@Composable
private fun BudgetAmountDialog(state: BudgetState, key: String, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val currency = LocalCurrency.current
    var text by remember(key) { mutableStateOf(state.budget.budgets[key]?.let { currency.toInput(it) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Budget · {0}", statsKeyName(key, state.catById))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("From {0} onward. Leave empty or 0 to remove.", state.month.label()), fontSize = 13.sp, color = SubtleText)
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(tr("Monthly amount")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton(onClick = { budgetAmount(text, currency)?.let(onSave) }) { Text(tr("Save"), color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Budget detail: every budget for the month with what's left; a category opens its entries. */
@Composable
fun BudgetDetailScreen(initialKey: Int) {
    val model = rememberBudgetModel(initialKey)
    val state by model.state.collectAsState()
    BudgetDetailContent(state, model.actions)
}

@Composable
private fun BudgetDetailContent(state: BudgetState, actions: BudgetActions) {
    val nav = LocalNavigator.current
    val lines = state.budget.lines
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Budget"), nav::pop) {
            TextButton(onClick = { nav.push(Screen.Budget) }) { Text(tr("Setting"), color = Accent) }
        }
        MonthTopBar(state.month.label(), { actions.step(-1) }, { actions.step(1) }, statusBar = false)
        HorizontalDivider(color = DividerColor)
        if (lines.isEmpty()) { EmptyState(tr("No budget for this month yet."), Icons.Outlined.EditNote); return@Column }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines, key = { it.categoryId.ifEmpty { "total" } }) { line ->
                BudgetDetailRow(state, line)
                HorizontalDivider(color = DividerColor)
            }
        }
    }
}

@Composable
private fun BudgetDetailRow(state: BudgetState, line: BudgetLine) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val main = state.catById[line.categoryId]?.let { it.parentId ?: it.id }
    Column(
        Modifier.fillMaxWidth().background(SurfaceColor)
            .clickable(enabled = main != null) {
                if (main != null) nav.push(Screen.StatsDetail(main, false, state.range.start, state.range.endInclusive, statsKeyName(main, state.catById)))
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statsKeyName(line.categoryId, state.catById), Modifier.weight(1f),
                fontWeight = if (line.categoryId == TOTAL_BUDGET) FontWeight.SemiBold else null)
            Text("${(line.ratio * 100).roundToInt()}%", color = if (line.ratio > 1f) ExpenseColor else SubtleText, fontSize = 13.sp)
        }
        BudgetProgress(line)
        Row(Modifier.padding(top = 6.dp)) {
            Text(tr("{0} of {1}", currency.format(line.spent), currency.format(line.budget)), Modifier.weight(1f), color = SubtleText, fontSize = 12.sp)
            Text(leftOrOver(line, currency), color = if (line.remaining >= 0) SubtleText else ExpenseColor, fontSize = 12.sp)
        }
    }
}
