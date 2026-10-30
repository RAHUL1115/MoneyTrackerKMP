package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.PeriodSettings
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.classify
import app.moneytracker.domain.totals
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.LocalBackup
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.MonthPickerDialog
import app.moneytracker.ui.Screen
import app.moneytracker.ui.label
import app.moneytracker.ui.long
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberPeriod
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt

private enum class Span(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year"), CUSTOM("Custom period") }
private enum class Side(val label: String) { SPENDING("Spending"), INCOME("Income"), NET("Net") }
private enum class GroupBy(val label: String) { CATEGORY("Category"), ACCOUNT("Account"), TAG("Tag") }

private const val TRANSFERS = "__transfers"
private const val UNCATEGORIZED = "__none"
private const val NO_TAG = "__notag"

private fun DateRange.days() = endInclusive.toEpochDays() - start.toEpochDays() + 1
private fun DateRange.shift(days: Int) = DateRange(start.plus(DatePeriod(days = days)), endInclusive.plus(DatePeriod(days = days)))

/** Main-category key of a report row (transfers and balance changes get their own keys). */
private fun mainKey(r: ReportRow, catById: Map<String, Category>): String = when {
    r.kind == TxnKind.TRANSFER_OUT -> TRANSFERS
    r.categoryId == MODIFIED_BALANCE_CATEGORY -> MODIFIED_BALANCE_CATEGORY
    else -> r.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } ?: UNCATEGORIZED
}

private fun keyName(key: String, catById: Map<String, Category>) = when (key) {
    TRANSFERS -> tr("Saved / invested")
    UNCATEGORIZED -> tr("Uncategorized")
    MODIFIED_BALANCE_CATEGORY -> tr(MODIFIED_BALANCE_NAME)
    else -> catById[key]?.name ?: tr("Deleted category")
}

/** A part of the breakdown with the entries behind it; [seeAll] opens the full list when there is one. */
private class Part(val key: String, val name: String, val amount: Long, val previous: Long, val refunded: Long, val entries: List<ReportRow>, val seeAll: (() -> Unit)?)

/** A bar of the trend: its period, and what tapping it shows. */
private class TrendSlot(val label: String, val range: DateRange, val open: (() -> Unit)?)

@Composable
internal fun InsightsTab() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val backup = LocalBackup.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val today = repo.today()
    var periodKey by rememberPeriod()
    var span by rememberSaveable { mutableStateOf(Span.MONTH) }
    var side by rememberSaveable { mutableStateOf(Side.SPENDING) }
    var groupBy by rememberSaveable { mutableStateOf(GroupBy.CATEGORY) }
    var weekDay by rememberSaveable { mutableStateOf(today.toEpochDays()) }
    var customStart by rememberSaveable { mutableStateOf(ps.month(ps.labelFor(today)).start.toEpochDays()) }
    var customEnd by rememberSaveable { mutableStateOf(today.toEpochDays()) }
    var periodSheet by remember { mutableStateOf(false) }
    var monthPicker by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val ym = YearMonth.fromKey(periodKey)

    fun weekOf(d: LocalDate): DateRange {
        val back = (d.dayOfWeek.ordinal - ps.weekStart.ordinal + 7) % 7
        val start = d.minus(DatePeriod(days = back))
        return DateRange(start, start.plus(DatePeriod(days = 6)))
    }
    fun rangeOf(s: Span): DateRange = when (s) {
        Span.WEEK -> weekOf(LocalDate.fromEpochDays(weekDay))
        Span.MONTH -> ps.month(ym)
        Span.YEAR -> ps.year(ym.year)
        Span.CUSTOM -> DateRange(LocalDate.fromEpochDays(minOf(customStart, customEnd)), LocalDate.fromEpochDays(maxOf(customStart, customEnd)))
    }
    val range = rangeOf(span)
    val previousRange = when (span) {
        Span.WEEK -> range.shift(-7)
        Span.MONTH -> ps.month(ym.plus(-1))
        Span.YEAR -> ps.year(ym.year - 1)
        Span.CUSTOM -> range.shift(-range.days())
    }
    val slots = trendSlots(span, range, ym, ps, onWeek = { weekDay = it.start.toEpochDays() }, onMonth = { span = Span.MONTH; periodKey = it.key },
        onYear = { periodKey = YearMonth(it, ym.month).key })
    val trendRange = DateRange(minOf(slots.first().range.start, range.start), maxOf(slots.last().range.endInclusive, range.endInclusive))

    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val previousRows by remember(previousRange) { repo.reportRows(previousRange) }.collectAsState(emptyList())
    val trendRows by remember(trendRange) { repo.reportRows(trendRange) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accounts by repo.accounts.collectAsState(emptyList())
    val tagNames by repo.txnTagNames.collectAsState(emptyMap())
    val accountName = accounts.associate { it.id to it.name }

    val t = totals(rows)
    val p = totals(previousRows)
    fun pick(income: Long, expense: Long) = when (side) { Side.SPENDING -> expense; Side.INCOME -> income; Side.NET -> income - expense }
    val total = pick(t.income, t.expense)
    val previousTotal = pick(p.income, p.expense)
    val flow = if (side == Side.INCOME) Flow.INCOME else Flow.EXPENSE

    val phrase = when (span) {
        Span.WEEK -> if (today in range) tr("this week") else range.label()
        Span.MONTH -> tr("in {0}", ym.label())
        Span.YEAR -> tr("in {0}", ym.year.toString())
        Span.CUSTOM -> range.label()
    }
    val against = when (span) {
        Span.WEEK -> tr("last week")
        Span.MONTH -> monthShort(ym.plus(-1).month)
        Span.YEAR -> (ym.year - 1).toString()
        Span.CUSTOM -> tr("previous period")
    }
    val elapsed = when {
        today < range.start -> 0
        today > range.endInclusive -> range.days()
        else -> today.toEpochDays() - range.start.toEpochDays() + 1
    }
    val dailyAverage = if (elapsed > 0) total / elapsed else 0L
    val projected = if (elapsed in 1 until range.days()) total * range.days() / elapsed else null

    if (monthPicker) MonthPickerDialog(ym, ps.labelFor(today), { monthPicker = false }) { periodKey = it.key; monthPicker = false }
    DatePickerHost(pickStart, range.start, { pickStart = false }, { it?.let { d -> customStart = d.toEpochDays() } })
    DatePickerHost(pickEnd, range.endInclusive, { pickEnd = false }, { it?.let { d -> customEnd = d.toEpochDays() } })

    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("Insights")) {
            IconTile(Icons.Outlined.FileDownload, tr("Export to Excel"), { backup.exportExcel() })
            Pill(when (span) {
                Span.WEEK -> tr("Week")
                Span.MONTH -> monthShort(ym.month) + " " + ym.year
                Span.YEAR -> ym.year.toString()
                Span.CUSTOM -> tr("Custom")
            }, selected = false, onClick = { periodSheet = true }, modifier = Modifier.padding(top = 4.dp), leading = Icons.Outlined.DateRange)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fun move(dir: Int) = when (span) {
                Span.WEEK -> weekDay += 7 * dir
                Span.MONTH -> periodKey = ym.plus(dir).key
                Span.YEAR -> periodKey = ym.plus(12 * dir).key
                Span.CUSTOM -> { val len = range.days(); customStart = range.start.toEpochDays() + len * dir; customEnd = range.endInclusive.toEpochDays() + len * dir }
            }
            PeriodStepper(
                when (span) { Span.WEEK, Span.CUSTOM -> range.label(); Span.MONTH -> ym.label(); Span.YEAR -> ym.year.toString() },
                { move(-1) }, { move(1) },
                onLabel = when (span) { Span.MONTH, Span.YEAR -> ({ monthPicker = true }); Span.CUSTOM -> ({ pickStart = true }); else -> null },
                sub = if (span == Span.MONTH && ps.startDay != 1) range.label() else null,
            )
            if (span == Span.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(tr("From {0}", range.start.long()), false, { pickStart = true }, Modifier.weight(1f))
                Pill(tr("To {0}", range.endInclusive.long()), false, { pickEnd = true }, Modifier.weight(1f))
            }
            Segmented(Side.entries.map { tr(it.label) }, side.ordinal, { side = Side.entries[it]; openKey = null })

            // Hero: total, change, trend, daily average.
            FCard {
                Text(when (side) { Side.SPENDING -> tr("Spent {0}", phrase); Side.INCOME -> tr("Earned {0}", phrase); Side.NET -> tr("Net {0}", phrase) },
                    color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(currency.format(total), color = if (side == Side.NET) (if (total < 0) F.expense else F.income) else F.ink,
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
                Gap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChangeBadge(total, previousTotal, upIsGood = side != Side.SPENDING, against = against, signedValues = side == Side.NET)
                    HGap(8.dp)
                    Text(currency.format(previousTotal), color = F.muted, fontSize = 12.sp, style = F.money, maxLines = 1)
                }
                Gap(16.dp)
                val values = slots.map { s ->
                    val inSlot = trendRows.filter { r -> r.date != null && r.date!! in s.range }
                    val tt = totals(inSlot)
                    pick(tt.income, tt.expense)
                }
                val highlight = slots.indexOfFirst { it.range.start == range.start }.takeIf { it >= 0 } ?: slots.lastIndex
                MoneyBars(slots.mapIndexed { i, s -> MoneyBar(s.label, values[i]) }, currency.decimals, highlight = highlight,
                    signed = side == Side.NET, onTap = { i -> slots[i].open?.invoke() })
                Gap(14.dp)
                Divider()
                Gap(12.dp)
                Row {
                    Stat(tr("Daily average"), currency.format(dailyAverage), Modifier.weight(1f))
                    when {
                        side == Side.NET -> Stat(tr("Saved of income"), if (t.income > 0) "${t.net * 100 / t.income}%" else "–", Modifier.weight(1f))
                        projected != null -> Stat(tr("Projected"), currency.format(projected), Modifier.weight(1f))
                        span == Span.CUSTOM && range.days() > 31 -> Stat(tr("Monthly average"), currency.format(total * 30 / range.days()), Modifier.weight(1f))
                        else -> Stat(tr("Net"), currency.format(t.net), Modifier.weight(1f), if (t.net < 0) F.expense else F.income)
                    }
                }
            }

            BudgetSummaryCard(if (span == Span.MONTH) ym else ps.labelFor(today))

            // Breakdown by category, account or tag.
            val parts = parts(groupBy, rows, previousRows, flow, catById, accountName, tagNames, range, side == Side.INCOME, nav)
            val partsTotal = parts.sumOf { it.amount.coerceAtLeast(0) }
            FCard {
                Text(if (side == Side.INCOME) tr("Income by") else tr("Spending by"), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Gap(10.dp)
                PillRow { GroupBy.entries.forEach { g -> Pill(tr(g.label), g == groupBy, { groupBy = g; openKey = null }) } }
                Gap(6.dp)
                if (parts.isEmpty()) Text(
                    if (side == Side.INCOME) tr("No income recorded {0}.", phrase) else tr("Nothing spent {0} yet. Entries you add will show up here.", phrase),
                    Modifier.padding(vertical = 16.dp), color = F.muted, fontSize = 14.sp,
                )
                val top = parts.maxOfOrNull { it.amount }?.coerceAtLeast(1) ?: 1
                parts.forEachIndexed { i, part ->
                    if (i > 0) Divider()
                    PartRow(part, partsTotal, top, groupBy, side == Side.INCOME, openKey == part.key, accountName, catById) {
                        openKey = if (openKey == part.key) null else part.key
                    }
                }
                if (groupBy == GroupBy.TAG && parts.isNotEmpty()) Text(tr("An entry with several tags counts once for each tag."),
                    Modifier.padding(top = 8.dp), color = F.muted, fontSize = 12.sp)
            }

            // The biggest single entries of the period.
            val biggest = rows.filter { classify(it) == flow && it.amount > 0 }.sortedByDescending { it.amount }.take(5)
            if (biggest.isNotEmpty()) FCard {
                CardTitle(tr("Top entries"))
                biggest.forEach { r -> EntryLine(r, accountName, catById) }
            }
            Gap(24.dp)
        }
    }

    if (periodSheet) Sheet(tr("Period"), { periodSheet = false }) {
        Span.entries.forEach { s ->
            val r = rangeOf(s)
            SheetOption(tr(s.label), s == span, subtitle = when (s) { Span.MONTH -> ym.label(); Span.YEAR -> ym.year.toString(); else -> r.label() }) {
                span = s; periodSheet = false; openKey = null
                if (s == Span.CUSTOM) pickStart = true
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = F.ink) {
    Column(modifier) {
        Text(label, color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

private fun trendSlots(
    span: Span, range: DateRange, ym: YearMonth, ps: PeriodSettings,
    onWeek: (DateRange) -> Unit, onMonth: (YearMonth) -> Unit, onYear: (Int) -> Unit,
): List<TrendSlot> = when (span) {
    Span.WEEK -> (5 downTo 0).map { back -> range.shift(-7 * back).let { r -> TrendSlot(r.start.short(), r) { onWeek(r) } } }
    Span.MONTH -> (5 downTo 0).map { back -> ym.plus(-back).let { m -> TrendSlot(monthShort(m.month), ps.month(m)) { onMonth(m) } } }
    Span.YEAR -> (5 downTo 0).map { back -> (ym.year - back).let { y -> TrendSlot(y.toString(), ps.year(y)) { onYear(y) } } }
    Span.CUSTOM -> {
        if (range.days() <= 42) {
            // Weekly chunks from the start.
            generateSequence(range.start) { it.plus(DatePeriod(days = 7)) }.takeWhile { it <= range.endInclusive }.map { s ->
                val e = minOf(s.plus(DatePeriod(days = 6)), range.endInclusive)
                TrendSlot(s.short(), DateRange(s, e), null)
            }.toList()
        } else {
            val first = ps.labelFor(range.start); val last = ps.labelFor(range.endInclusive)
            val months = generateSequence(first) { it.plus(1) }.takeWhile { it <= last }.toList()
            if (months.size <= 12) months.map { m -> TrendSlot(monthShort(m.month), ps.month(m)) { onMonth(m) } }
            else (first.year..last.year).map { y -> TrendSlot(y.toString(), ps.year(y), null) }
        }
    }
}

private fun parts(
    groupBy: GroupBy, rows: List<ReportRow>, previousRows: List<ReportRow>, flow: Flow, catById: Map<String, Category>,
    accountName: Map<String, String>, tagNames: Map<String, List<String>>, range: DateRange, income: Boolean, nav: app.moneytracker.ui.Navigator,
): List<Part> {
    val current = rows.filter { classify(it) == flow }
    val previous = previousRows.filter { classify(it) == flow }
    // Rows under each key; a tagged entry appears under each of its tags.
    val keysOf: (ReportRow) -> List<String> = when (groupBy) {
        GroupBy.CATEGORY -> { r -> listOf(mainKey(r, catById)) }
        GroupBy.ACCOUNT -> { r -> listOf(r.accountId) }
        GroupBy.TAG -> { r -> tagNames[r.txnId].orEmpty().ifEmpty { listOf(NO_TAG) }.distinct() }
    }
    fun group(list: List<ReportRow>): Map<String, List<ReportRow>> {
        val m = LinkedHashMap<String, MutableList<ReportRow>>()
        list.forEach { r -> keysOf(r).forEach { k -> m.getOrPut(k) { mutableListOf() } += r } }
        return m
    }
    val before = group(previous).mapValues { (_, v) -> v.sumOf { it.amount } }
    return group(current).map { (key, list) ->
        val name = when (groupBy) {
            GroupBy.CATEGORY -> keyName(key, catById)
            GroupBy.ACCOUNT -> accountName[key] ?: tr("Deleted account")
            GroupBy.TAG -> if (key == NO_TAG) tr("No tag") else key
        }
        val seeAll: (() -> Unit)? = when {
            groupBy == GroupBy.CATEGORY && catById[key] != null -> ({ nav.push(Screen.StatsDetail(key, income, range.start, range.endInclusive, name)) })
            groupBy == GroupBy.ACCOUNT && accountName[key] != null -> ({ nav.push(Screen.AccountDetail(key)) })
            else -> null
        }
        Part(key, name, list.sumOf { it.amount }, before[key] ?: 0L,
            -list.filter { app.moneytracker.domain.isRefund(it.kind, it.amount) }.sumOf { it.amount }, list, seeAll)
    }.sortedByDescending { it.amount }
}

@Composable
private fun PartRow(
    part: Part, total: Long, top: Long, groupBy: GroupBy, income: Boolean, open: Boolean,
    accountName: Map<String, String>, catById: Map<String, Category>, onTap: () -> Unit,
) {
    val currency = LocalCurrency.current
    val pct = if (total > 0 && part.amount > 0) (part.amount * 100.0 / total).roundToInt() else null
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onTap).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mark(if (groupBy == GroupBy.TAG && part.key != NO_TAG) "# ${part.name}" else part.name, 34.dp)
            HGap(12.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(part.name, Modifier.weight(1f, fill = false), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pct != null) Text(if (pct == 0) "<1%" else "$pct%", Modifier.padding(start = 6.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                val notes = listOfNotNull(
                    if (part.previous > 0) changeText(part.amount, part.previous) else null,
                    if (part.refunded > 0) tr("incl. {0} refunded", currency.format(part.refunded)) else null,
                )
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = F.muted, fontSize = 12.sp, maxLines = 1)
            }
            Text(currency.format(part.amount), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = F.muted, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        Gap(8.dp)
        Bar(part.amount.coerceAtLeast(0).toFloat() / top, Modifier.padding(start = 46.dp), fill = if (income) F.income else F.accent, height = 6.dp)
    }
    if (open) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(F.fill).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(tr("Top entries").uppercase(), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
        part.entries.sortedByDescending { it.amount }.take(3).forEach { r -> EntryLine(r, accountName, catById) }
        if (part.seeAll != null) Text(
            if (groupBy == GroupBy.ACCOUNT) tr("Open account ›") else tr("See all {0} entries ›", part.entries.size),
            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = part.seeAll).padding(vertical = 6.dp),
            color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
    }
}

private fun changeText(current: Long, previous: Long): String {
    val pct = ((current - previous) * 100.0 / previous).roundToInt()
    return (if (pct > 0) "▲ " else if (pct < 0) "▼ " else "= ") + kotlin.math.abs(pct) + "%"
}

@Composable
private fun EntryLine(r: ReportRow, accountName: Map<String, String>, catById: Map<String, Category>) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val refund = r.amount < 0
    val title = r.note.ifBlank { if (r.kind == TxnKind.TRANSFER_OUT) tr("Saved / invested") else app.moneytracker.ui.categoryPath(r.categoryId, catById) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav.push(Screen.EditTxn(txnId = r.txnId)) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.date?.short(), accountName[r.accountId]).joinToString(" · "), color = F.muted, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text((if (refund) "+" else "") + currency.format(kotlin.math.abs(r.amount)), color = if (refund) F.income else F.ink,
            fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
    }
}

@Composable internal fun FreshBudget() = BudgetBody()
