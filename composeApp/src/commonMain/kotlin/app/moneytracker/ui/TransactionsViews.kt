package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalBackups
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.app.Screen
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.data.MonthRollup
import app.moneytracker.data.MonthTotals
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.AccentTint
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.IncomeExpenseStrip
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.PageBackground
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.Tabular
import app.moneytracker.designsystem.classic.ZeroColor
import app.moneytracker.designsystem.classic.dotted
import app.moneytracker.designsystem.classic.long
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.domain.BudgetLine
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.classify
import app.moneytracker.domain.totals
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The calendar's today marker: a dark slate band behind the day number, the same in light and dark themes. */
private val TodayBadge = Color(0xFF3B3F5C)

/** The spreadsheet green of the Excel export icon. */
private val ExcelGreen = Color(0xFF1E8E3E)

private val SettingButtonShape = RoundedCornerShape(4.dp)

/** The Total view's outlined boxes (the figures and the Excel export). */
private val OutlineBoxShape = RoundedCornerShape(6.dp)

// ───────────────────────── Calendar

@Composable
internal fun CalendarView(activity: ActivityModel) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val today = LocalRepo.current.today()
    val model = rememberCalendarModel(activity)
    val cal by model.state.collectAsState()
    var dayDialog by remember { mutableStateOf<LocalDate?>(null) }
    IncomeExpenseStrip(cal.totals.income, cal.totals.expense, currency)
    // Always six weeks; days of the neighbouring months show their amounts greyed.
    val first = cal.firstDay
    val weeks = (0 until 6).map { w -> List(7) { i -> first.plus(DatePeriod(days = w * 7 + i)) } }
    Column(Modifier.fillMaxSize().background(SurfaceColor)) {
        Row(Modifier.fillMaxWidth().background(PageBackground).padding(vertical = 4.dp)) {
            List(7) { DayOfWeek.entries[(cal.weekStart.ordinal + it) % 7] }.forEach { dow ->
                Text(tr(dow.name.take(3).lowercase().replaceFirstChar { it.uppercase() }), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp,
                    color = when (dow) { DayOfWeek.SUNDAY -> ExpenseColor; DayOfWeek.SATURDAY -> IncomeColor; else -> SubtleText })
            }
        }
        Column(Modifier.fillMaxSize()) {
            weeks.forEach { week ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    week.forEach { day -> CalendarCell(day, cal, today) { dayDialog = day } }
                }
            }
        }
    }
    dayDialog?.let { day ->
        DayDialog(day, cal.entriesOn(day), cal.lookups, onDismiss = { dayDialog = null }, onAdd = { nav.push(Screen.EditTxn(date = day)) }) {
            nav.push(Screen.EditTxn(txnId = it.id))
        }
    }
}

@Composable
private fun RowScope.CalendarCell(day: LocalDate, cal: CalendarState, today: LocalDate, onClick: () -> Unit) {
    val inRange = day in cal.range
    val dt = cal.dayTotals[day]
    Column(
        Modifier.weight(1f).fillMaxHeight()
            .background(if (inRange) SurfaceColor else PageBackground)
            .border(0.5.dp, DividerColor)
            .clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        val label = if (day.dayOfMonth == 1 || day == cal.firstDay) "${day.monthNumber}.${day.dayOfMonth}" else "${day.dayOfMonth}"
        val color = when {
            !inRange -> SubtleText
            day.dayOfWeek == DayOfWeek.SUNDAY -> ExpenseColor
            day.dayOfWeek == DayOfWeek.SATURDAY -> IncomeColor
            else -> InkSoft
        }
        if (day == today) {
            Text(label, Modifier.fillMaxWidth().background(TodayBadge).padding(horizontal = 2.dp), color = Color.White, fontSize = 12.sp)
        } else Text(label, color = color, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        if (dt != null && (dt.income != 0L || dt.expense != 0L)) CellAmounts(dt, fade = if (inRange) 1f else 0.45f)
    }
}

@Composable
private fun CellAmounts(dt: Totals, fade: Float) {
    val currency = LocalCurrency.current
    val small = 10.sp
    if (dt.income > 0) Text(currency.format(dt.income, withSymbol = false), Modifier.fillMaxWidth(), color = IncomeColor.copy(alpha = fade), fontSize = small, textAlign = TextAlign.End, maxLines = 1)
    if (dt.expense > 0) Text(currency.format(dt.expense, withSymbol = false), Modifier.fillMaxWidth(), color = ExpenseColor.copy(alpha = fade), fontSize = small, textAlign = TextAlign.End, maxLines = 1)
    // The day's total only when there is both income and expense (otherwise it repeats the one figure).
    if (dt.income > 0 && dt.expense > 0) Text(currency.format(dt.net, withSymbol = false), Modifier.fillMaxWidth(), color = InkSoft.copy(alpha = fade), fontSize = small, textAlign = TextAlign.End, maxLines = 1)
}

/** Transactions of one calendar day with an "Add" shortcut for that date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDialog(day: LocalDate, visible: List<Txn>, lookups: EntryLookups, onDismiss: () -> Unit, onAdd: () -> Unit, onOpen: (Txn) -> Unit) {
    val currency = LocalCurrency.current
    val tt = totals(visible.map { lookups.rules.row(it) })
    // A bottom sheet keeps rows full width (a dialog squeezed account names to "Ban…").
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceColor) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(day.long(), style = MaterialTheme.typography.titleMedium)
                if (visible.isNotEmpty()) {
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (tt.income > 0) Text(currency.format(tt.income), color = IncomeColor, fontSize = 13.sp)
                        if (tt.expense > 0) Text(currency.format(tt.expense), color = ExpenseColor, fontSize = 13.sp)
                    }
                }
            }
            TextButton(onClick = { onDismiss(); onAdd() }) { Text(tr("+ Add"), color = Accent) }
        }
        HorizontalDivider(color = DividerColor)
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (visible.isEmpty()) Text(tr("Nothing recorded on this day."), Modifier.padding(20.dp), color = SubtleText)
            visible.forEach { t ->
                TxnLine(t, lookups.accountName, lookups.catById, classify(lookups.rules.row(t))) { onDismiss(); onOpen(t) }
            }
        }
    }
}

// ───────────────────────── Monthly

@Composable
internal fun MonthlyView(activity: ActivityModel) {
    val currency = LocalCurrency.current
    val today = LocalRepo.current.today()
    val model = rememberMonthsModel(activity)
    val period by activity.state.collectAsState()
    val rollup by model.year.collectAsState()
    val selected = period.period
    var expanded by remember(selected.year) { mutableStateOf(selected.month) }
    // Same year → month → week rollup as the new layout's Months view.
    IncomeExpenseStrip(rollup.totals.income, rollup.totals.expense, currency)
    LazyColumn(Modifier.fillMaxSize().background(SurfaceColor)) {
        for (mr in rollup.months) {
            val m = mr.month.month
            item(key = "m$m") { MonthRow(mr, open = expanded == m) { expanded = if (expanded == m) 0 else m } }
            if (expanded == m) {
                items(mr.weeks, key = { "w${it.first.start}" }) { (w, wt) -> WeekRow(w, wt, isCurrent = today in w) }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun MonthRow(mr: MonthRollup, open: Boolean, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val range = mr.range
    val mt = mr.totals
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(Modifier.weight(0.8f)) {
            Text(monthShort(mr.month.month), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (open) Text("${range.start.monthNumber}.${range.start.dayOfMonth} ~ ${range.endInclusive.monthNumber}.${range.endInclusive.dayOfMonth}", fontSize = 11.sp, color = SubtleText, style = Tabular)
        }
        Text(currency.format(mt.income), color = if (mt.income == 0L) ZeroColor else IncomeColor, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, style = MonthAmountStyle)
        Column(Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            Text(currency.format(mt.expense), color = if (mt.expense == 0L) ZeroColor else ExpenseColor, maxLines = 1, style = MonthAmountStyle)
            Text(currency.format(mt.net), color = if (mt.net == 0L) ZeroColor else SubtleText, fontSize = 11.sp, style = Tabular, maxLines = 1)
        }
    }
}

@Composable
private fun WeekRow(w: DateRange, wt: Totals, isCurrent: Boolean) {
    val currency = LocalCurrency.current
    Row(
        Modifier.fillMaxWidth().background(if (isCurrent) AccentTint else PageBackground)
            .padding(start = 44.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text("${pad(w.start.monthNumber)}.${pad(w.start.dayOfMonth)} ~ ${pad(w.endInclusive.monthNumber)}.${pad(w.endInclusive.dayOfMonth)}", Modifier.weight(0.8f), fontSize = 13.sp, style = Tabular, maxLines = 1)
        Text(currency.format(wt.income), color = if (wt.income == 0L) ZeroColor else IncomeColor, fontSize = 13.sp, style = Tabular, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
        Column(Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            Text(currency.format(wt.expense), color = if (wt.expense == 0L) ZeroColor else ExpenseColor, fontSize = 13.sp, style = Tabular, maxLines = 1)
            Text(currency.format(wt.net), color = if (wt.net == 0L) ZeroColor else SubtleText, fontSize = 11.sp, style = Tabular, maxLines = 1)
        }
    }
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

/** A month's totals: same size as its weeks', set apart by weight. */
private val MonthAmountStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

// ───────────────────────── Total

@Composable
internal fun TotalView(activity: ActivityModel) {
    val currency = LocalCurrency.current
    val model = rememberMonthsModel(activity)
    val period by activity.state.collectAsState()
    // One read model for the Total card (same numbers as the new layout's Months view).
    val m by model.month.collectAsState()
    IncomeExpenseStrip(m.totals.income, m.totals.expense, currency)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BudgetCard(period.period, m.budget.total)
        Spacer(Modifier.height(10.dp))
        AccountsCard(period.range, m)
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun BudgetCard(ym: YearMonth, total: BudgetLine?) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditNote, null, Modifier.size(28.dp))
            Text(tr("Budget"), Modifier.padding(start = 10.dp).weight(1f), fontSize = 18.sp)
            Row(
                Modifier.background(PageBackground, SettingButtonShape).clip(SettingButtonShape).clickable(role = Role.Button) { nav.push(Screen.Budget) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Budget Setting"), color = SubtleText)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SubtleText, modifier = Modifier.size(18.dp))
            }
        }
        if (total == null) {
            Text(tr("No budget for this month yet. Set one to see how much is left."), Modifier.padding(top = 12.dp), color = SubtleText, fontSize = 13.sp)
        } else {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.clickable { nav.push(Screen.BudgetDetail(ym.key)) }) {
                Text(tr("Total budget"), Modifier.weight(1f), color = SubtleText)
                Text("${currency.format(total.spent)} / ${currency.format(total.budget)}")
            }
            LinearProgressIndicator(
                progress = { total.ratio.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp),
                color = if (total.ratio > 1f) ExpenseColor else IncomeColor, trackColor = PageBackground,
            )
            Text(
                if (total.remaining >= 0) tr("{0} left", currency.format(total.remaining)) else tr("{0} over budget", currency.format(-total.remaining)),
                Modifier.padding(top = 6.dp), color = SubtleText, fontSize = 13.sp,
            )
            Text(tr("See each category ›"), Modifier.padding(top = 6.dp).clickable { nav.push(Screen.BudgetDetail(ym.key)) },
                color = Accent, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AccountsCard(range: DateRange, m: MonthTotals) {
    val fileTransfer = LocalBackups.current.files
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(26.dp))
            Text(tr("Accounts"), Modifier.padding(start = 10.dp).weight(1f), fontSize = 18.sp)
            Text(range.dotted(), color = SubtleText)
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, DividerColor, OutlineBoxShape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatLine(tr("Compared expenses (last month)"), m.comparedToLastMonth?.let { "$it%" } ?: "–")
            StatLine(tr("Expenses (cash, bank)"), currency.format(m.otherExpense))
            StatLine(tr("Expenses (card)"), currency.format(m.cardExpense))
            StatLine(tr("Saved / invested"), currency.format(m.saved))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, DividerColor, OutlineBoxShape).clip(OutlineBoxShape)
                .clickable(role = Role.Button) { fileTransfer.exportExcel() }.padding(14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, null, tint = ExcelGreen)
            Text(tr("Export data to Excel"), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), color = SubtleText)
        Text(value)
    }
}
