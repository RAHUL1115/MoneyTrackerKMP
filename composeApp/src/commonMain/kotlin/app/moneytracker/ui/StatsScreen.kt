package app.moneytracker.ui

import androidx.compose.ui.text.drawText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.EditNote
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Periods
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.domain.totals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TRANSFERS = "__transfers"
private const val UNCATEGORIZED = "__none"

/** Chart palette: distinct, readable on white. */
val ChartColors = listOf(
    Color(0xFF14B8A6), Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF0EA5E9),
    Color(0xFF84CC16), Color(0xFFA855F7), Color(0xFFF97316), Color(0xFF06B6D4), Color(0xFF94A3B8),
)

private enum class StatsSpan(val label: String) { WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Annually"), PERIOD("Period") }

private fun nameOf(key: String, catById: Map<String, Category>) = when (key) {
    TRANSFERS -> tr("Saved / invested")
    UNCATEGORIZED -> tr("Uncategorized")
    app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY -> app.moneytracker.domain.MODIFIED_BALANCE_NAME
    TOTAL_BUDGET -> tr("Total")
    else -> catById[key]?.name ?: tr("Deleted category")
}

/** Amount per main category for the given flow. */
fun byMainCategory(rows: List<ReportRow>, catById: Map<String, Category>, flow: Flow): Map<String, Long> =
    rows.filter { classify(it) == flow }
        .groupBy { r ->
            when {
                r.kind == TxnKind.TRANSFER_OUT -> TRANSFERS
                r.categoryId == app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY -> r.categoryId
                else -> r.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } ?: UNCATEGORIZED
            }
        }
        .mapValues { (_, v) -> v.sumOf { it.amount } }

@Composable
fun StatsTab() {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val nav = LocalNavigator.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var periodKey by rememberPeriod()
    var span by rememberSaveable { mutableStateOf(StatsSpan.MONTHLY) }
    var showIncome by rememberSaveable { mutableStateOf(false) }
    var spanMenu by remember { mutableStateOf(false) }
    val ym = YearMonth.fromKey(periodKey)
    // Weekly: any day of the shown week. Period: user-chosen dates (default: this month).
    var weekDay by rememberSaveable { mutableStateOf(repo.today().toEpochDays()) }
    var periodStart by rememberSaveable { mutableStateOf(ps.month(ps.labelFor(repo.today())).start.toEpochDays()) }
    var periodEnd by rememberSaveable { mutableStateOf(repo.today().toEpochDays()) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    val range = when (span) {
        StatsSpan.WEEKLY -> {
            val d = LocalDate.fromEpochDays(weekDay)
            val back = (d.dayOfWeek.ordinal - ps.weekStart.ordinal + 7) % 7
            val start = d.minus(DatePeriod(days = back))
            DateRange(start, start.plus(DatePeriod(days = 6)))
        }
        StatsSpan.MONTHLY -> ps.month(ym)
        StatsSpan.YEARLY -> ps.year(ym.year)
        StatsSpan.PERIOD -> DateRange(LocalDate.fromEpochDays(minOf(periodStart, periodEnd)), LocalDate.fromEpochDays(maxOf(periodStart, periodEnd)))
    }
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val t = totals(rows)

    Column(Modifier.fillMaxSize()) {
        val step = if (span == StatsSpan.YEARLY) 12 else 1
        val label = when (span) {
            StatsSpan.WEEKLY, StatsSpan.PERIOD -> range.dotted()
            StatsSpan.YEARLY -> ym.year.toString()
            StatsSpan.MONTHLY -> ym.label()
        }
        fun move(dir: Int) = when (span) {
            StatsSpan.WEEKLY -> weekDay += 7 * dir
            StatsSpan.PERIOD -> {
                // Step by the period's own length.
                val len = range.endInclusive.toEpochDays() - range.start.toEpochDays() + 1
                periodStart = range.start.toEpochDays() + len * dir; periodEnd = range.endInclusive.toEpochDays() + len * dir
            }
            else -> periodKey = ym.plus(step * dir).key
        }
        var monthPicker by remember { mutableStateOf(false) }
        if (monthPicker) MonthPickerDialog(ym, ps.labelFor(repo.today()), { monthPicker = false }) { periodKey = it.key; monthPicker = false }
        MonthTopBar(label, { move(-1) }, { move(1) }, onLabelClick = if (span == StatsSpan.MONTHLY || span == StatsSpan.YEARLY) ({ monthPicker = true }) else null) {
            Box(Modifier.padding(end = 12.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(FillColor).clickable { spanMenu = true }.padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr(span.label), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = InkSoft)
                }
                DropdownMenu(spanMenu, { spanMenu = false }) {
                    StatsSpan.entries.forEach { s -> DropdownMenuItem(text = { Text(tr(s.label)) }, onClick = { span = s; spanMenu = false }) }
                }
            }
        }
        if (span == StatsSpan.PERIOD) {
            Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(range.start.long(), Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(PageBackground).clickable { pickStart = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp), textAlign = TextAlign.Center, fontSize = 13.sp, color = Ink)
                Text("~", Modifier.padding(horizontal = 8.dp), color = SubtleText)
                Text(range.endInclusive.long(), Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(PageBackground).clickable { pickEnd = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp), textAlign = TextAlign.Center, fontSize = 13.sp, color = Ink)
            }
        }
        DatePickerHost(pickStart, range.start, { pickStart = false }, { it?.let { d -> periodStart = d.toEpochDays() } })
        DatePickerHost(pickEnd, range.endInclusive, { pickEnd = false }, { it?.let { d -> periodEnd = d.toEpochDays() } })
        UnderlineTabs(
            listOf(tr("Income  {0}", currency.format(t.income)), tr("Expenses  {0}", currency.format(t.expense))),
            if (showIncome) 0 else 1,
        ) { showIncome = it == 0 }

        val amounts = byMainCategory(rows, catById, if (showIncome) Flow.INCOME else Flow.EXPENSE)
        val total = amounts.values.sum()
        if (total == 0L) { EmptyState(icon = Icons.Outlined.PieChart); return@Column }
        val sorted = amounts.entries.sortedByDescending { it.value }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(Modifier.padding(top = 10.dp).card().fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    PieChart(
                        sorted.map { it.value }, Modifier.fillMaxWidth().height(260.dp),
                        labels = sorted.map { e -> "${nameOf(e.key, catById)}\n${((e.value * 100.0) / total).toInt()}%" },
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            item(key = "list") {
                Column(Modifier.card().padding(vertical = 4.dp)) {
                    sorted.forEachIndexed { i, e ->
                        val drillable = catById[e.key] != null
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = drillable) {
                                    nav.push(Screen.StatsDetail(e.key, showIncome, range.start, range.endInclusive, nameOf(e.key, catById)))
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PercentBadge(((e.value * 100.0) / total).toInt().coerceAtLeast(if (e.value > 0) 1 else 0), ChartColors[i % ChartColors.size])
                            Text(nameOf(e.key, catById), Modifier.weight(1f).padding(start = 12.dp), color = Ink)
                            Text(currency.format(e.value), color = Ink, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PieChart(values: List<Long>, modifier: Modifier = Modifier, labels: List<String>? = null) {
    val total = values.sum().toFloat().takeIf { it > 0 } ?: return
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Ink)
    Canvas(modifier) {
        // With labels the pie shrinks to leave room for leader lines and text on both sides.
        val radius = if (labels != null) minOf(size.width * 0.27f, size.height * 0.40f) else minOf(size.width, size.height) / 2
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius)
        val pieSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        var start = -90f
        values.forEachIndexed { i, v ->
            val sweep = v / total * 360f
            val color = ChartColors[i % ChartColors.size]
            drawArc(color, start, sweep, useCenter = true, topLeft = topLeft, size = pieSize)
            if (values.size > 1) drawArc(SurfaceColor, start, sweep, useCenter = true, topLeft = topLeft, size = pieSize, style = Stroke(width = 2f))
            val label = labels?.getOrNull(i)
            // Label slices of at least 3%; thinner ones would overlap.
            if (label != null && sweep >= 10.8f) {
                val mid = (start + sweep / 2) * kotlin.math.PI / 180.0
                val cos = kotlin.math.cos(mid).toFloat(); val sin = kotlin.math.sin(mid).toFloat()
                val p1 = androidx.compose.ui.geometry.Offset(center.x + cos * radius, center.y + sin * radius)
                val p2 = androidx.compose.ui.geometry.Offset(center.x + cos * (radius + 14.dp.toPx()), center.y + sin * (radius + 14.dp.toPx()))
                val right = cos >= 0
                val p3 = androidx.compose.ui.geometry.Offset(p2.x + (if (right) 10.dp.toPx() else -10.dp.toPx()), p2.y)
                drawLine(color, p1, p2, strokeWidth = 1.5.dp.toPx()); drawLine(color, p2, p3, strokeWidth = 1.5.dp.toPx())
                val maxW = (if (right) size.width - p3.x else p3.x) - 4.dp.toPx()
                val text = measurer.measure(label, labelStyle, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
                val x = if (right) p3.x + 4.dp.toPx() else p3.x - 4.dp.toPx() - text.size.width
                drawText(text, topLeft = androidx.compose.ui.geometry.Offset(x, p3.y - text.size.height / 2))
            }
            start += sweep
        }
    }
}

// ───────────────────────── Budget

@Composable
fun BudgetScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var periodKey by rememberPeriod()
    val ym = YearMonth.fromKey(periodKey)
    val range = ps.month(ym)
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val entries by remember(ym) { repo.budgetEntries(ym) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val expenseRows = rows.filter { it.kind == TxnKind.EXPENSE && classify(it) == Flow.EXPENSE }
    val spent = byMainCategory(expenseRows, catById, Flow.EXPENSE)
    val spentBySub = expenseRows.filter { r -> r.categoryId?.let { catById[it]?.parentId } != null }
        .groupBy { it.categoryId!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val budgets = effectiveBudgets(entries, ym)
    val lines = budgetLines(budgets, spent, spentBySub) { id -> catById[id]?.parentId != null }
    var editing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Budget"), nav::pop)
        MonthTopBar(ym.label(), { periodKey = ym.plus(-1).key }, { periodKey = ym.plus(1).key }, statusBar = false)
        HorizontalDivider(color = DividerColor)
        val useSubcategories by repo.useSubcategories.collectAsState(false)
        val options = listOf(TOTAL_BUDGET) + flattenCategories(categories.filter { it.kind == CategoryKind.EXPENSE })
            .filter { useSubcategories || it.parentId == null }.map { it.id }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(tr("Budgets apply from the selected month onward. Tap a category to set its monthly amount."),
                    Modifier.padding(20.dp), color = SubtleText, fontSize = 13.sp)
            }
            items(options, key = { it.ifEmpty { "total" } }) { key ->
                val line = lines.firstOrNull { it.categoryId == key }
                val isSub = catById[key]?.parentId != null
                Column(Modifier.fillMaxWidth().background(if (isSub) PageBackground else SurfaceColor).clickable { editing = key }
                    .padding(start = if (isSub) 40.dp else 20.dp, end = 20.dp, top = if (isSub) 10.dp else 14.dp, bottom = if (isSub) 10.dp else 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nameOf(key, catById), Modifier.weight(1f), fontWeight = if (key == TOTAL_BUDGET) FontWeight.SemiBold else null,
                            color = if (isSub) SubtleText else Ink)
                        if (line == null) Text(tr("Set budget"), color = SubtleText)
                        else Text("${currency.format(line.spent)} / ${currency.format(line.budget)}")
                    }
                    if (line != null) {
                        LinearProgressIndicator(
                            progress = { line.ratio.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
                            color = if (line.ratio > 1f) ExpenseColor else IncomeColor, trackColor = PageBackground,
                        )
                        Text(
                            if (line.remaining >= 0) tr("{0} left", currency.format(line.remaining)) else tr("{0} over", currency.format(-line.remaining)),
                            Modifier.padding(top = 4.dp), color = SubtleText, fontSize = 12.sp,
                        )
                    }
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    }

    editing?.let { key ->
        var text by remember(key) { mutableStateOf(budgets[key]?.let { currency.toInput(it) }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr("Budget · {0}", nameOf(key, catById))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("From {0} onward. Leave empty or 0 to remove.", ym.label()), fontSize = 13.sp, color = SubtleText)
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(tr("Monthly amount")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = if (text.isBlank()) 0L else currency.parse(text)
                    if (amount != null) {
                        scope.launch { withContext(repo.io) { repo.setBudget(key, ym, amount) } }
                        editing = null
                    }
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }
}

/** Budget detail: every budget for the month with what's left; a category opens its entries. */
@Composable
fun BudgetDetailScreen(initialKey: Int) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var periodKey by rememberSaveable { mutableStateOf(initialKey) }
    val ym = YearMonth.fromKey(periodKey)
    val range = ps.month(ym)
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val entries by remember(ym) { repo.budgetEntries(ym) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val expenseRows = rows.filter { it.kind == TxnKind.EXPENSE && classify(it) == Flow.EXPENSE }
    val spent = byMainCategory(expenseRows, catById, Flow.EXPENSE)
    val spentBySub = expenseRows.filter { r -> r.categoryId?.let { catById[it]?.parentId } != null }
        .groupBy { it.categoryId!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val lines = budgetLines(effectiveBudgets(entries, ym), spent, spentBySub) { id -> catById[id]?.parentId != null }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Budget"), nav::pop) {
            TextButton(onClick = { nav.push(Screen.Budget) }) { Text(tr("Setting"), color = Accent) }
        }
        MonthTopBar(ym.label(), { periodKey = ym.plus(-1).key }, { periodKey = ym.plus(1).key }, statusBar = false)
        HorizontalDivider(color = DividerColor)
        if (lines.isEmpty()) { EmptyState(tr("No budget for this month yet."), Icons.Outlined.EditNote); return@Column }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines, key = { it.categoryId.ifEmpty { "total" } }) { line ->
                val cat = catById[line.categoryId]
                Column(
                    Modifier.fillMaxWidth().background(SurfaceColor)
                        .clickable(enabled = cat != null) {
                            val main = cat!!.parentId ?: cat.id
                            nav.push(Screen.StatsDetail(main, false, range.start, range.endInclusive, nameOf(main, catById)))
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nameOf(line.categoryId, catById), Modifier.weight(1f),
                            fontWeight = if (line.categoryId == TOTAL_BUDGET) FontWeight.SemiBold else null)
                        Text("${(line.ratio * 100).roundToInt()}%", color = if (line.ratio > 1f) ExpenseColor else SubtleText, fontSize = 13.sp)
                    }
                    LinearProgressIndicator(
                        progress = { line.ratio.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
                        color = if (line.ratio > 1f) ExpenseColor else IncomeColor, trackColor = PageBackground,
                    )
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(tr("{0} of {1}", currency.format(line.spent), currency.format(line.budget)), Modifier.weight(1f), color = SubtleText, fontSize = 12.sp)
                        Text(if (line.remaining >= 0) tr("{0} left", currency.format(line.remaining)) else tr("{0} over", currency.format(-line.remaining)),
                            color = if (line.remaining >= 0) SubtleText else ExpenseColor, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    }
}
