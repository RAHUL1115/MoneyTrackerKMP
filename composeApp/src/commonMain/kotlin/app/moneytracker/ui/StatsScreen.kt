package app.moneytracker.ui

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
import androidx.compose.foundation.layout.width
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
import kotlin.math.roundToLong
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

private enum class GroupBy(val label: String) { CATEGORY("Category"), ACCOUNT("Account"), TAG("Tag") }
private const val NO_TAG = "__notag"

/** One part of the breakdown: amount in the shown and the previous period; [open] drills in when there is somewhere to go. */
private class Slice(val key: String, val name: String, val amount: Long, val previous: Long, val open: (() -> Unit)?)

private fun DateRange.days() = endInclusive.toEpochDays() - start.toEpochDays() + 1
private fun DateRange.shift(days: Int) = DateRange(start.plus(DatePeriod(days = days)), endInclusive.plus(DatePeriod(days = days)))

@Composable
fun StatsTab() {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val nav = LocalNavigator.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var periodKey by rememberPeriod()
    var span by rememberSaveable { mutableStateOf(StatsSpan.MONTHLY) }
    var showIncome by rememberSaveable { mutableStateOf(false) }
    var groupBy by rememberSaveable { mutableStateOf(GroupBy.CATEGORY) }
    var spanMenu by remember { mutableStateOf(false) }
    val ym = YearMonth.fromKey(periodKey)
    val today = repo.today()
    // Weekly: any day of the shown week. Period: user-chosen dates (default: this month).
    var weekDay by rememberSaveable { mutableStateOf(today.toEpochDays()) }
    var periodStart by rememberSaveable { mutableStateOf(ps.month(ps.labelFor(today)).start.toEpochDays()) }
    var periodEnd by rememberSaveable { mutableStateOf(today.toEpochDays()) }
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
    // The period before, for the comparisons; and the bars of the trend chart (six months up to the shown one for Monthly).
    val previousRange = when (span) {
        StatsSpan.WEEKLY -> range.shift(-7)
        StatsSpan.MONTHLY -> ps.month(ym.plus(-1))
        StatsSpan.YEARLY -> ps.year(ym.year - 1)
        StatsSpan.PERIOD -> range.shift(-range.days())
    }
    val trendRange = if (span == StatsSpan.MONTHLY) DateRange(ps.month(ym.plus(-5)).start, range.endInclusive) else range
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val previousRows by remember(previousRange) { repo.reportRows(previousRange) }.collectAsState(emptyList())
    val trendRows by remember(trendRange) { repo.reportRows(trendRange) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accounts by repo.accounts.collectAsState(emptyList())
    val tagNames by repo.txnTagNames.collectAsState(emptyMap())
    val t = totals(rows)
    val flow = if (showIncome) Flow.INCOME else Flow.EXPENSE
    val flowColor = if (showIncome) IncomeColor else ExpenseColor
    val flowLabel = if (showIncome) tr("Income") else tr("Expenses")
    val flowTotal = if (showIncome) t.income else t.expense
    val previousTotal = totals(previousRows).let { if (showIncome) it.income else it.expense }

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
                val len = range.days()
                periodStart = range.start.toEpochDays() + len * dir; periodEnd = range.endInclusive.toEpochDays() + len * dir
            }
            else -> periodKey = ym.plus(step * dir).key
        }
        var monthPicker by remember { mutableStateOf(false) }
        if (monthPicker) MonthPickerDialog(ym, ps.labelFor(today), { monthPicker = false }) { periodKey = it.key; monthPicker = false }
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

        // Daily average over the days so far; a projection while the period is still running.
        val elapsed = when {
            today < range.start -> 0
            today > range.endInclusive -> range.days()
            else -> today.toEpochDays() - range.start.toEpochDays() + 1
        }
        val dailyAverage = if (elapsed > 0) flowTotal / elapsed else 0L
        val projected = if (elapsed in 1 until range.days()) flowTotal * range.days() / elapsed else null
        val compareLabel = when (span) {
            StatsSpan.MONTHLY -> tr("vs {0}", ym.plus(-1).label())
            StatsSpan.YEARLY -> tr("vs {0}", (ym.year - 1).toString())
            else -> tr("vs previous period")
        }

        val trend = trendBars(span, range, ym, today, ps, trendRows, flow)
        val slices = run {
            val current = rows.filter { classify(it) == flow }
            val previous = previousRows.filter { classify(it) == flow }
            val accountById = accounts.associateBy { it.id }
            when (groupBy) {
                GroupBy.CATEGORY -> {
                    val before = byMainCategory(previousRows, catById, flow)
                    byMainCategory(rows, catById, flow).map { (key, amount) ->
                        val name = nameOf(key, catById)
                        Slice(key, name, amount, before[key] ?: 0L,
                            if (catById[key] != null) ({ nav.push(Screen.StatsDetail(key, showIncome, range.start, range.endInclusive, name)) }) else null)
                    }
                }
                GroupBy.ACCOUNT -> {
                    fun sum(list: List<ReportRow>) = list.groupBy { it.accountId }.mapValues { (_, v) -> v.sumOf { it.amount } }
                    val before = sum(previous)
                    sum(current).map { (key, amount) ->
                        Slice(key, accountById[key]?.name ?: tr("Deleted account"), amount, before[key] ?: 0L,
                            if (accountById[key] != null) ({ nav.push(Screen.AccountDetail(key)) }) else null)
                    }
                }
                GroupBy.TAG -> {
                    // An entry counts once for each of its tags.
                    fun sum(list: List<ReportRow>): Map<String, Long> = buildMap {
                        list.forEach { r -> tagNames[r.txnId].orEmpty().ifEmpty { listOf(NO_TAG) }.distinct().forEach { k -> put(k, (get(k) ?: 0L) + r.amount) } }
                    }
                    val before = sum(previous)
                    sum(current).map { (key, amount) -> Slice(key, if (key == NO_TAG) tr("No tag") else key, amount, before[key] ?: 0L, null) }
                }
            }.sortedByDescending { it.amount }
        }
        val top = rows.filter { classify(it) == flow }.sortedByDescending { it.amount }.take(5)
        val accountName = accounts.associate { it.id to it.name }

        val chartMemory = remember { ChartMemory() }
        androidx.compose.runtime.CompositionLocalProvider(LocalChartMemory provides chartMemory) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "overview") {
                Column(Modifier.padding(top = 10.dp).card().fillMaxWidth().padding(16.dp)) {
                    Text(flowLabel, color = SubtleText, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(currency.format(animatedAmount(flowTotal, "overview")), Modifier.weight(1f, fill = false), color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        ChangeChip(flowTotal, previousTotal, upIsGood = showIncome)
                    }
                    Text("$compareLabel · ${currency.format(previousTotal)}", color = SubtleText, fontSize = 12.sp)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DividerColor)
                    Row {
                        MiniStat(tr("Daily average"), currency.format(dailyAverage), Ink, Modifier.weight(1f))
                        if (projected != null) MiniStat(tr("Projected"), currency.format(projected), Ink, Modifier.weight(1f))
                        MiniStat(
                            tr("Net"), currency.format(t.net), if (t.net >= 0) IncomeColor else ExpenseColor, Modifier.weight(1f),
                            note = if (t.income > 0) tr("{0}% of income", (t.net * 100 / t.income).toString()) else null,
                        )
                    }
                }
            }
            item(key = "trend") {
                Column(Modifier.padding(top = 10.dp).card().fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                        Text(tr("Trend"), Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
                        Text(trend.subtitle, color = SubtleText, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    BarChart(trend.values, trend.labels, flowColor, trend.highlight, id = "trend",
                        onBarClick = trend.open?.let { open -> { i: Int -> open(i).let { (s, key) -> span = s; periodKey = key } } })
                }
            }
            item(key = "breakdown") {
                Column(Modifier.padding(top = 10.dp).card().fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Breakdown"), Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
                        GroupBy.entries.forEach { g ->
                            val sel = g == groupBy
                            Text(tr(g.label),
                                Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(if (sel) AccentTint else FillColor)
                                    .clickable { groupBy = g }.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (sel) Accent else InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (slices.isEmpty()) {
                        Text(tr("No data available."), Modifier.fillMaxWidth().padding(vertical = 32.dp), color = SubtleText, textAlign = TextAlign.Center)
                    } else {
                        // Tapping a slice (or its row) shows that part in the middle; the selection clears when the data changes.
                        var picked by remember(slices.map { it.key }) { mutableStateOf<Int?>(null) }
                        val sel = picked?.let { slices.getOrNull(it) }
                        DonutChart(slices.map { it.amount }, Modifier.fillMaxWidth().height(220.dp).padding(vertical = 12.dp), id = "donut",
                            selected = picked, onSelect = { picked = it }) {
                            Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (sel == null) {
                                    Text(currency.format(animatedAmount(flowTotal, "donut")), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(flowLabel, color = SubtleText, fontSize = 12.sp)
                                } else {
                                    val pct = if (flowTotal > 0) (sel.amount * 100.0 / flowTotal).roundToInt() else 0
                                    Text(sel.name, color = ChartColors[picked!! % ChartColors.size], fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(currency.format(sel.amount), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(if (pct == 0 && sel.amount > 0) "<1%" else "$pct%", color = SubtleText, fontSize = 12.sp)
                                    if (sel.previous > 0) ChangeChip(sel.amount, sel.previous, upIsGood = showIncome, compact = true)
                                }
                            }
                        }
                        slices.forEachIndexed { i, sl ->
                            SliceRow(sl, ChartColors[i % ChartColors.size], flowTotal, showIncome, currency, highlighted = picked == i,
                                dimmed = picked != null && picked != i)
                        }
                        if (groupBy == GroupBy.TAG) Text(tr("An entry with several tags counts once for each tag."),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = SubtleText, fontSize = 11.sp)
                    }
                }
            }
            if (top.isNotEmpty()) item(key = "top") {
                Column(Modifier.padding(top = 10.dp).card().fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(tr("Top entries"), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), color = Ink, fontWeight = FontWeight.Medium)
                    top.forEach { r ->
                        val title = r.note.ifBlank {
                            if (r.kind == TxnKind.TRANSFER_OUT) nameOf(TRANSFERS, catById) else r.categoryId?.let { catById[it]?.name } ?: tr("Uncategorized")
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.push(Screen.EditTxn(txnId = r.txnId)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, color = Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(listOfNotNull(r.date?.short(), accountName[r.accountId]).joinToString(" · "), color = SubtleText, fontSize = 12.sp, maxLines = 1)
                            }
                            Text(currency.format(r.amount), Modifier.padding(start = 12.dp), color = flowColor, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}

/** Bars for the trend card, and where a tapped bar leads (span and period), if anywhere. */
private class TrendBars(
    val values: List<Long>, val labels: List<String>, val highlight: Int?, val subtitle: String,
    val open: ((Int) -> Pair<StatsSpan, Int>)?,
)

private fun trendBars(
    span: StatsSpan, range: DateRange, ym: YearMonth, today: LocalDate, ps: app.moneytracker.data.PeriodSettings,
    rows: List<ReportRow>, flow: Flow,
): TrendBars {
    val inFlow = rows.filter { classify(it) == flow && it.date != null }
    fun byMonth(months: List<YearMonth>): List<Long> {
        val sums = inFlow.groupBy { ps.labelFor(it.date!!) }.mapValues { (_, v) -> v.sumOf { it.amount } }
        return months.map { sums[it] ?: 0L }
    }
    fun byDay(): Pair<List<LocalDate>, List<Long>> {
        val sums = inFlow.groupBy { it.date!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val days = (0 until range.days()).map { range.start.plus(DatePeriod(days = it)) }
        return days to days.map { sums[it] ?: 0L }
    }
    return when {
        span == StatsSpan.MONTHLY -> {
            val months = (5 downTo 0).map { ym.plus(-it) }
            TrendBars(byMonth(months), months.map { monthShort(it.month) }, 5, tr("Last 6 months")) { i -> StatsSpan.MONTHLY to months[i].key }
        }
        span == StatsSpan.YEARLY -> {
            val months = (1..12).map { YearMonth(ym.year, it) }
            TrendBars(byMonth(months), months.map { monthShort(it.month) }, months.indexOf(ps.labelFor(today)).takeIf { it >= 0 }, tr("By month")) { i ->
                StatsSpan.MONTHLY to months[i].key
            }
        }
        range.days() <= 62 -> {
            val (days, values) = byDay()
            val every = (days.size + 7) / 8   // about eight labels
            TrendBars(values, days.mapIndexed { i, d -> if (i % every == 0) d.dayOfMonth.toString() else "" },
                days.indexOf(today).takeIf { it >= 0 }, tr("By day"), null)
        }
        else -> {
            val first = ps.labelFor(range.start); val last = ps.labelFor(range.endInclusive)
            val months = generateSequence(first) { it.plus(1) }.takeWhile { it <= last }.toList()
            TrendBars(byMonth(months), months.map { monthShort(it.month) }, months.indexOf(ps.labelFor(today)).takeIf { it >= 0 }, tr("By month")) { i ->
                StatsSpan.MONTHLY to months[i].key
            }
        }
    }
}

/** Counts from the last shown amount to [target] whenever it changes; settles on the exact value. [id] names it in [LocalChartMemory]. */
@Composable
private fun animatedAmount(target: Long, id: String): Long {
    val memory = LocalChartMemory.current
    val key = "amount:$id"
    val last = memory?.shown?.get(key) as? Long
    val anim = remember { androidx.compose.animation.core.Animatable((last ?: 0L).toFloat()) }
    var settled by remember { mutableStateOf(last == target) }
    androidx.compose.runtime.LaunchedEffect(target) {
        if (memory != null && memory.shown[key] == target) { anim.snapTo(target.toFloat()); settled = true; return@LaunchedEffect }
        memory?.shown?.set(key, target)
        settled = false
        anim.animateTo(target.toFloat(), androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        settled = true
    }
    return if (settled) target else anim.value.roundToLong()
}

/** "▲ 12%" against the previous period; green when the change is good (less spending, more income). */
@Composable
private fun ChangeChip(current: Long, previous: Long, upIsGood: Boolean, compact: Boolean = false) {
    if (previous <= 0L) {
        if (current > 0 && !compact) Text(tr("New"), Modifier.background(FillColor, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
            color = InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        return
    }
    val pct = ((current - previous) * 100.0 / previous).roundToInt()
    val color = when {
        pct == 0 -> SubtleText
        (pct > 0) == upIsGood -> IncomeColor
        else -> ExpenseColor
    }
    val text = "${if (pct > 0) "▲" else if (pct < 0) "▼" else "="} ${kotlin.math.abs(pct)}%"
    if (compact) Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    else Text(text, Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun MiniStat(label: String, value: String, color: Color, modifier: Modifier, note: String? = null) {
    Column(modifier) {
        Text(label, color = SubtleText, fontSize = 12.sp, maxLines = 1)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (note != null) Text(note, color = SubtleText, fontSize = 11.sp, maxLines = 1)
    }
}

/** A breakdown row: colour dot, name and amount, then a share bar with the percentage and the change. */
@Composable
private fun SliceRow(
    slice: Slice, color: Color, total: Long, income: Boolean, currency: app.moneytracker.domain.CurrencyFormat,
    highlighted: Boolean = false, dimmed: Boolean = false,
) {
    val share = if (total > 0) slice.amount.toFloat() / total else 0f
    val pct = (share * 100).roundToInt()
    // The bar slides to its new width when the period or grouping changes.
    val shownShare by androidx.compose.animation.core.animateFloatAsState(share.coerceIn(0f, 1f), androidx.compose.animation.core.tween(700), label = "share")
    Row(
        Modifier.fillMaxWidth().background(if (highlighted) color.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = slice.open != null) { slice.open?.invoke() }.padding(horizontal = 16.dp, vertical = 11.dp)
            .then(if (dimmed) Modifier.alpha(0.45f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slice.name, Modifier.weight(1f), color = Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(currency.format(slice.amount), Modifier.padding(start = 8.dp), color = Ink, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(FillColor)) {
                    Box(Modifier.fillMaxWidth(shownShare).height(4.dp).clip(RoundedCornerShape(50)).background(color))
                }
                Text(if (pct == 0 && slice.amount > 0) "<1%" else "$pct%", Modifier.padding(start = 8.dp), color = SubtleText, fontSize = 12.sp)
                if (slice.previous > 0) {
                    Spacer(Modifier.width(8.dp))
                    ChangeChip(slice.amount, slice.previous, upIsGood = income, compact = true)
                }
            }
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
