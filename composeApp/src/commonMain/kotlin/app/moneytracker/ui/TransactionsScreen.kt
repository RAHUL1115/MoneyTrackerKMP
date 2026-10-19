package app.moneytracker.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.IconButton
import app.moneytracker.data.PeriodSettings
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Periods
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.domain.totals
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Remembers the selected report month (per tab). */
@Composable
fun rememberPeriod(): MutableState<Int> {
    val repo = LocalRepo.current
    return rememberSaveable {
        mutableStateOf(repo.periodSettingsNow().labelFor(repo.today()).key)
    }
}

private enum class TransView(val title: String) { DAILY("Daily"), CALENDAR("Calendar"), MONTHLY("Monthly"), TOTAL("Total"), NOTE("Note") }

@Composable
fun TransactionsTab() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val currency = LocalCurrency.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var periodKey by rememberPeriod()
    var view by rememberSaveable {
        mutableStateOf(repo.settingNow(MoneyRepository.Keys.START_SCREEN)?.let { s -> TransView.entries.firstOrNull { it.name == s } } ?: TransView.DAILY)
    }
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val ym = YearMonth.fromKey(periodKey)
    val range = ps.month(ym)
    var filter by remember { mutableStateOf(TxnFilter()) }
    var filterSheet by remember { mutableStateOf(false) }
    var monthPicker by remember { mutableStateOf(false) }
    val filterCats by repo.categories.collectAsState(emptyList())
    val filterAccounts by repo.accountNames.collectAsState(emptyMap())

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val actions: @Composable () -> Unit = {
                if (pending.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { nav.push(Screen.Inbox) }) { Icon(Icons.Outlined.MarkEmailUnread, tr("Detected transactions")) }
                        Text(pending.size.toString(), Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).background(Accent, CircleShape)
                            .padding(horizontal = 5.dp), color = Color.White, fontSize = 10.sp)
                    }
                }
                if (platform.voiceAvailable) IconButton(onClick = {
                    platform.listen { text -> nav.push(Screen.EditTxn(preset = VoiceParser.parse(text, repo.today(), currency))) }
                }) { Icon(Icons.Outlined.Mic, tr("Voice input")) }
                IconButton(onClick = { nav.push(Screen.Templates) }) { Icon(Icons.Outlined.StarOutline, tr("Bookmarks")) }
                IconButton(onClick = { nav.push(Screen.Search) }) { Icon(Icons.Outlined.Search, tr("Search")) }
                IconButton(onClick = { filterSheet = true }) {
                    Icon(Icons.Outlined.FilterList, tr("Filter"), tint = if (filter.isActive) Accent else androidx.compose.material3.LocalContentColor.current)
                }
            }
            if (view == TransView.MONTHLY) {
                MonthTopBar(ym.year.toString(), { periodKey = ym.plus(-12).key }, { periodKey = ym.plus(12).key }, onLabelClick = { monthPicker = true }, actions = actions)
            } else {
                MonthTopBar(ym.label(), { periodKey = ym.plus(-1).key }, { periodKey = ym.plus(1).key }, onLabelClick = { monthPicker = true }, actions = actions)
            }
            if (monthPicker) MonthPickerDialog(ym, ps.labelFor(repo.today()), { monthPicker = false }) { periodKey = it.key; monthPicker = false }
            UnderlineTabs(TransView.entries.map { tr(it.title) }, view.ordinal) { view = TransView.entries[it] }
            if (filter.isActive && view != TransView.TOTAL && view != TransView.NOTE) {
                FilterBar(filter, filterCats.associateBy { it.id }, filterAccounts, onEdit = { filterSheet = true }, onClear = { filter = TxnFilter() })
            }
            val swipe by remember(repo) { repo.setting(MoneyRepository.Keys.SWIPE) }.collectAsState(null)
            // Horizontal swipe: next/previous month (default) or tab, per Configuration.
            val swipeModifier = if (swipe == "NONE") Modifier else Modifier.pointerInput(swipe, view, periodKey) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                    val dir = when { dx < -120f -> 1; dx > 120f -> -1; else -> 0 }
                    if (dir != 0) {
                        if (swipe == "TAB") view = TransView.entries[(view.ordinal + dir).coerceIn(0, TransView.entries.lastIndex)]
                        else periodKey = ym.plus(dir * if (view == TransView.MONTHLY) 12 else 1).key
                    }
                }) { _, amount -> dx += amount }
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalTxnFilter provides filter) {
              Column(Modifier.fillMaxSize().then(swipeModifier)) {
                when (view) {
                    TransView.DAILY -> DailyView(range)
                    TransView.CALENDAR -> CalendarView(range, ps.weekStart)
                    TransView.MONTHLY -> MonthlyView(ym, ps)
                    TransView.TOTAL -> androidx.compose.runtime.CompositionLocalProvider(LocalTxnFilter provides TxnFilter()) { TotalView(ym, range, ps) }
                    TransView.NOTE -> NotesView()
                }
              }
            }
        }
        if (filterSheet) FilterSheet(range, filter, onDismiss = { filterSheet = false }) { filter = it; filterSheet = false }
        // Note button beside + on the Daily view (Configuration › Note button).
        val noteButton by remember(repo) { repo.setting(app.moneytracker.data.MoneyRepository.Keys.NOTE_BUTTON) }.collectAsState(null)
        Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.CenterVertically) {
            if (view == TransView.DAILY && noteButton != "0") {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { nav.push(Screen.EditNote()) }, modifier = Modifier.padding(end = 0.dp, bottom = 20.dp),
                    containerColor = SurfaceColor, contentColor = Accent, shape = RoundedCornerShape(14.dp),
                ) { Icon(Icons.Outlined.EditNote, tr("Add note")) }
            }
            AddFab {
                if (view == TransView.NOTE) nav.push(Screen.EditNote()) else nav.push(Screen.EditTxn())
            }
        }
    }
}

/** Running total of everything before [range] (shown when carry-over is on). */
@Composable
fun rememberCarryOver(range: DateRange): Long? {
    val repo = LocalRepo.current
    val on by remember(repo) { repo.flag(MoneyRepository.Keys.CARRY_OVER) }.collectAsState(false)
    val before = DateRange(LocalDate(1970, 1, 1), range.start.minus(DatePeriod(days = 1)))
    val rows by remember(before) { repo.reportRows(before) }.collectAsState(emptyList())
    return if (on) totals(rows).net else null
}

@Composable
fun AddFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick, modifier = modifier.padding(20.dp), shape = RoundedCornerShape(18.dp),
        containerColor = Accent, contentColor = Color.White,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(3.dp, 3.dp, 3.dp, 3.dp),
    ) { Icon(Icons.Filled.Add, tr("Add"), Modifier.size(28.dp)) }
}

fun Txn.toReportRow(transferAsExpense: Map<String, Boolean>) = ReportRow(
    kind, amount, categoryId, transferAsExpense[accountId] == true, toAccountId?.let { transferAsExpense[it] } == true, date,
)

// ───────────────────────── Daily

@Composable
private fun DailyView(range: DateRange) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val txns by rememberFilteredTxns(range)
    val rows by rememberFilteredRows(range)
    val accounts by repo.accounts.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val tagNames by repo.txnTagNames.collectAsState(emptyMap())
    val photos by repo.txnIdsWithPhotos.collectAsState(emptySet())
    val carry = rememberCarryOver(range)
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val flags = accounts.associate { it.id to it.transferAsExpense }

    // Multi-select: long-press starts it, taps toggle, Back clears it.
    var selection by remember(range) { mutableStateOf(emptySet<String>()) }
    androidx.compose.runtime.DisposableEffect(selection.isEmpty()) {
        val remove = if (selection.isEmpty()) ({}) else nav.interceptBack { selection = emptySet(); true }
        onDispose { remove() }
    }
    if (selection.isNotEmpty()) {
        SelectionBar(txns.filter { it.id in selection }, onClear = { selection = emptySet() })
    }
    val t = totals(rows)
    if (carry != null) {
        SummaryStrip(listOf(
            Triple(tr("Carried over"), carry, InkSoft), Triple(tr("Income"), t.income, IncomeColor),
            Triple(tr("Expenses"), t.expense, ExpenseColor), Triple(tr("Balance"), carry + t.net, InkSoft),
        ), currency)
    } else IncomeExpenseStrip(t.income, t.expense, currency)
    // Repeats not added yet that fall in this period, summed in one row (shown on future months).
    val rules by repo.rules.collectAsState(emptyList())
    val pending = rules.flatMap { r -> app.moneytracker.domain.Recurrence.upcoming(r, range).map { r to it } }
    if (pending.isNotEmpty()) UpcomingRepeatsRow(pending, catById, currency) { nav.push(Screen.Recurring) }
    // TRANSFER_IN rows mirror TRANSFER_OUT; show each transfer once.
    val visible = txns.filter { it.kind != TxnKind.TRANSFER_IN }
    if (visible.isEmpty()) { EmptyState(icon = Icons.Outlined.Inbox); return }
    val dayTotals = rows.groupBy { it.date }.mapValues { totals(it.value) }
    LazyColumn(Modifier.fillMaxSize()) {
        // One card per day: header and its entries.
        visible.groupBy { it.date }.forEach { (date, dayTxns) ->
            item(key = "h$date") {
                val dt = dayTotals[date]
                Column(Modifier.padding(top = 6.dp).card(ListCardMargin).padding(bottom = 4.dp)) {
                    DayHeader(date, dt?.income ?: 0, dt?.expense ?: 0, currency, inset = ListRowInset)
                    dayTxns.forEach { txn ->
                        TxnLine(
                            txn, accountName, catById, classify(txn.toReportRow(flags)), tags = tagNames[txn.id].orEmpty(), hasPhoto = txn.id in photos,
                            inset = ListRowInset, selected = txn.id in selection,
                            onLongClick = { selection = selection + txn.id },
                        ) {
                            if (selection.isNotEmpty()) selection = if (txn.id in selection) selection - txn.id else selection + txn.id
                            else nav.push(Screen.EditTxn(txnId = txn.id))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** One transaction: category (left, grey) · note + account (middle) · amount (right). */
@Composable
fun TxnLine(
    txn: Txn,
    accountName: Map<String, String>,
    catById: Map<String, app.moneytracker.domain.Category>,
    flow: Flow,
    runningBalance: Long? = null,
    tags: List<String> = emptyList(),
    hasPhoto: Boolean = false,
    inset: androidx.compose.ui.unit.Dp = 16.dp,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    Row(
        Modifier.fillMaxWidth().background(if (selected) AccentTint else SurfaceColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(82.dp).padding(end = 8.dp)) {
            val (main, sub) = when (txn.kind) {
                TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN -> tr("Transfer") to null
                TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr(app.moneytracker.domain.MODIFIED_BALANCE_NAME) to null
                else -> mainCategoryName(txn.categoryId, catById) to subCategoryName(txn.categoryId, catById)
            }
            Text(main, color = SubtleText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = SubtleText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            val account = when (txn.kind) {
                TxnKind.TRANSFER_OUT -> "${accountName[txn.accountId].orEmpty()} → ${accountName[txn.toAccountId].orEmpty()}"
                TxnKind.TRANSFER_IN -> "${accountName[txn.toAccountId].orEmpty()} → ${accountName[txn.accountId].orEmpty()}"
                else -> accountName[txn.accountId].orEmpty()
            }
            val title = listOfNotNull(txn.note.ifBlank { null }, txn.installmentLabel).joinToString(" ")
            if (title.isNotEmpty()) Text(title, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val prefs = LocalListPrefs.current
            if (prefs.showDescription && txn.memo.isNotBlank()) Text(txn.memo, color = SubtleText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val marks = buildString {
                if (txn.isRefund) append("  · ").append(tr("Refund"))
                if (txn.recurringRuleId != null) append("  ↻")
                if (hasPhoto) append("  📷")
                if (txn.lat != null) append("  📍")
            }
            val time = if (LocalListPrefs.current.showTime) txn.timeOfDay?.let { formatTime(it) + "  " }.orEmpty() else ""
            Text(time + account + marks, color = SubtleText, fontSize = if (title.isEmpty()) 13.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" }, color = IncomeColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            // A refund is money back: shown as +amount in the income colour.
            val refund = txn.isRefund
            val color = when {
                refund && flow != Flow.NONE -> IncomeColor
                flow == Flow.INCOME -> IncomeColor
                flow == Flow.EXPENSE -> ExpenseColor
                else -> InkSoft
            }
            fun shown(v: Long) = if (refund) -v else v
            val foreign = txn.currency?.let { currencies[it] }
            if (foreign != null && txn.originalAmount != null) {
                Text(foreign.format.format(shown(txn.originalAmount), showSign = refund), color = color, fontWeight = FontWeight.Medium)
                Text(currency.format(shown(txn.amount), showSign = refund), color = SubtleText, fontSize = 12.sp)
            } else Text(currency.format(shown(txn.amount), showSign = refund), color = color, fontWeight = FontWeight.Medium)
            if (runningBalance != null) {
                Text("(${currency.format(runningBalance, withSymbol = false)})", color = SubtleText, fontSize = 10.sp)
            }
        }
    }
}

// ───────────────────────── Calendar

@Composable
private fun CalendarView(range: DateRange, weekStart: DayOfWeek) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val rows by rememberFilteredRows(range)
    val t = totals(rows)
    IncomeExpenseStrip(t.income, t.expense, currency)

    val today = repo.today()
    // Always six weeks; days of the neighbouring months show their amounts greyed.
    var first = range.start
    while (first.dayOfWeek != weekStart) first = first.minus(DatePeriod(days = 1))
    val grid = DateRange(first, first.plus(DatePeriod(days = 41)))
    val gridRows by rememberFilteredRows(grid)
    val byDay = gridRows.groupBy { it.date }.mapValues { totals(it.value) }
    var dayDialog by remember { mutableStateOf<LocalDate?>(null) }
    val weeks = (0 until 6).map { w -> List(7) { i -> first.plus(DatePeriod(days = w * 7 + i)) } }
    Column(Modifier.fillMaxSize().background(SurfaceColor)) {
        Row(Modifier.fillMaxWidth().background(PageBackground).padding(vertical = 4.dp)) {
            (0 until 7).map { DayOfWeek.entries[(weekStart.ordinal + it) % 7] }.forEach { dow ->
                Text(tr(dow.name.take(3).lowercase().replaceFirstChar { it.uppercase() }), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp,
                    color = when (dow) { DayOfWeek.SUNDAY -> ExpenseColor; DayOfWeek.SATURDAY -> IncomeColor; else -> SubtleText })
            }
        }
        Column(Modifier.fillMaxSize()) {
            weeks.forEach { week ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    week.forEach { day ->
                        val inRange = day in range
                        val dt = byDay[day]
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(if (inRange) SurfaceColor else PageBackground)
                                .border(0.5.dp, DividerColor)
                                .clickable { dayDialog = day }
                                .padding(3.dp),
                        ) {
                            val label = if (day.dayOfMonth == 1 || day == first) "${day.monthNumber}.${day.dayOfMonth}" else "${day.dayOfMonth}"
                            val color = when {
                                !inRange -> SubtleText
                                day.dayOfWeek == DayOfWeek.SUNDAY -> ExpenseColor
                                day.dayOfWeek == DayOfWeek.SATURDAY -> IncomeColor
                                else -> InkSoft
                            }
                            if (day == today) {
                                Text(label, Modifier.fillMaxWidth().background(Color(0xFF3B3F5C)).padding(horizontal = 2.dp), color = Color.White, fontSize = 12.sp)
                            } else Text(label, color = color, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            if (dt != null && (dt.income != 0L || dt.expense != 0L)) {
                                val small = 10.sp
                                val fade = if (inRange) 1f else 0.45f
                                if (dt.income > 0) Text(currency.format(dt.income, withSymbol = false), Modifier.fillMaxWidth(), color = IncomeColor.copy(alpha = fade), fontSize = small, textAlign = TextAlign.End, maxLines = 1)
                                if (dt.expense > 0) Text(currency.format(dt.expense, withSymbol = false), Modifier.fillMaxWidth(), color = ExpenseColor.copy(alpha = fade), fontSize = small, textAlign = TextAlign.End, maxLines = 1)
                                // The day's total only when there is both income and expense (otherwise it repeats the one figure).
                                if (dt.income > 0 && dt.expense > 0) Text(currency.format(dt.net, withSymbol = false), Modifier.fillMaxWidth(), color = InkSoft.copy(alpha = fade), fontSize = small, textAlign = TextAlign.End, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
    dayDialog?.let { day -> DayDialog(day, onDismiss = { dayDialog = null }) }
}

/** Transactions of one calendar day with an "Add" shortcut for that date. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DayDialog(day: LocalDate, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val range = DateRange(day, day)
    val txns by rememberFilteredTxns(range)
    val accounts by repo.accounts.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val flags = accounts.associate { it.id to it.transferAsExpense }
    val visible = txns.filter { it.kind != TxnKind.TRANSFER_IN }
    val tt = totals(visible.map { it.toReportRow(flags) })
    // A bottom sheet keeps rows full width (a dialog squeezed account names to "Ban…").
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceColor) {
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
            androidx.compose.material3.TextButton(onClick = { onDismiss(); nav.push(Screen.EditTxn(date = day)) }) { Text(tr("+ Add"), color = Accent) }
        }
        HorizontalDivider(color = DividerColor)
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (visible.isEmpty()) Text(tr("Nothing recorded on this day."), Modifier.padding(20.dp), color = SubtleText)
            visible.forEach { t ->
                TxnLine(t, accountName, catById, classify(t.toReportRow(flags))) { onDismiss(); nav.push(Screen.EditTxn(txnId = t.id)) }
            }
        }
    }
}

// ───────────────────────── Monthly

@Composable
private fun MonthlyView(selected: YearMonth, ps: PeriodSettings) {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val year = selected.year
    val yearRange = ps.year(year)
    val rows by rememberFilteredRows(yearRange)
    var expanded by remember(year) { mutableStateOf(selected.month) }

    val yt = totals(rows)
    IncomeExpenseStrip(yt.income, yt.expense, currency)
    val todayLabel = ps.labelFor(repo.today())
    val byMonth = rows.groupBy { ps.labelFor(it.date!!).month }
    // Up to the current month, or further if future-dated rows (installments, post-dated entries) exist.
    val lastMonth = maxOf(if (todayLabel.year == year) todayLabel.month else 12, byMonth.keys.maxOrNull() ?: 1)

    LazyColumn(Modifier.fillMaxSize().background(SurfaceColor)) {
        for (m in lastMonth downTo 1) {
            val range = ps.month(YearMonth(year, m))
            val mt = totals(byMonth[m].orEmpty())
            item(key = "m$m") {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = if (expanded == m) 0 else m }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.weight(0.8f)) {
                        Text(monthShort(m), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (expanded == m) Text("${range.start.monthNumber}.${range.start.dayOfMonth} ~ ${range.endInclusive.monthNumber}.${range.endInclusive.dayOfMonth}", fontSize = 11.sp, color = SubtleText)
                    }
                    Text(currency.format(mt.income), color = if (mt.income == 0L) ZeroColor else IncomeColor, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, fontSize = 13.sp)
                    Column(Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                        Text(currency.format(mt.expense), color = if (mt.expense == 0L) ZeroColor else ExpenseColor, maxLines = 1, fontSize = 13.sp)
                        Text(currency.format(mt.net), color = if (mt.net == 0L) ZeroColor else SubtleText, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            if (expanded == m) {
                val weeks = Periods.weeks(range, ps.weekStart)
                items(weeks.reversed(), key = { "w${it.start}" }) { w ->
                    val wt = totals(byMonth[m].orEmpty().filter { it.date!! in w })
                    val isCurrent = repo.today() in w
                    Row(
                        Modifier.fillMaxWidth().background(if (isCurrent) AccentTint else PageBackground)
                            .padding(start = 44.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        Text("${pad(w.start.monthNumber)}.${pad(w.start.dayOfMonth)} ~ ${pad(w.endInclusive.monthNumber)}.${pad(w.endInclusive.dayOfMonth)}", Modifier.weight(0.8f), fontSize = 13.sp, maxLines = 1)
                        Text(currency.format(wt.income), color = if (wt.income == 0L) ZeroColor else IncomeColor, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
                        Column(Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                            Text(currency.format(wt.expense), color = if (wt.expense == 0L) ZeroColor else ExpenseColor, fontSize = 13.sp, maxLines = 1)
                            Text(currency.format(wt.net), color = if (wt.net == 0L) ZeroColor else SubtleText, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

// ───────────────────────── Total

@Composable
private fun TotalView(ym: YearMonth, range: DateRange, ps: PeriodSettings) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val backup = LocalBackup.current
    val currency = LocalCurrency.current
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val prevRange = ps.month(ym.plus(-1))
    val prevRows by remember(prevRange) { repo.reportRows(prevRange) }.collectAsState(emptyList())
    val txns by remember(range) { repo.txnsBetween(range) }.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val groups by repo.groups.collectAsState(emptyList())
    val entries by remember(ym) { repo.budgetEntries(ym) }.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())

    val t = totals(rows)
    IncomeExpenseStrip(t.income, t.expense, currency)

    val groupType = groups.associate { it.id to it.type }
    val accountType = accounts.associate { it.id to groupType[it.groupId] }
    val cardExpense = txns.filter { it.kind == TxnKind.EXPENSE && accountType[it.accountId] == AccountType.CREDIT_CARD }.sumOf { it.amount }
    val otherExpense = txns.filter { it.kind == TxnKind.EXPENSE && accountType[it.accountId] != AccountType.CREDIT_CARD }.sumOf { it.amount }
    val savingsTransfers = rows.filter { it.kind == TxnKind.TRANSFER_OUT && classify(it) == Flow.EXPENSE }.sumOf { it.amount }
    val prevExpense = totals(prevRows).expense
    val compared = if (prevExpense == 0L) null else (t.expense * 100 / prevExpense).toInt()

    val catById by repo.categoryById.collectAsState(emptyMap())
    val spentByMain = rows.filter { classify(it) == Flow.EXPENSE && it.kind == TxnKind.EXPENSE }
        .groupBy { r -> r.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } ?: "__none" }
        .mapValues { (_, v) -> v.sumOf { it.amount } }
    val total = budgetLines(effectiveBudgets(entries, ym), spentByMain).firstOrNull { it.categoryId == TOTAL_BUDGET }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EditNote, null, Modifier.size(28.dp))
                Text(tr("Budget"), Modifier.padding(start = 10.dp).weight(1f), fontSize = 18.sp)
                Row(
                    Modifier.background(PageBackground, RoundedCornerShape(4.dp)).clickable { nav.push(Screen.Budget) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Budget Setting"), color = SubtleText)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SubtleText, modifier = Modifier.size(18.dp))
                }
            }
            if (total == null) {
                Text(tr("No budget for this month yet. Set one to see how much is left."), Modifier.padding(top = 12.dp), color = SubtleText, fontSize = 13.sp)
            }
            if (total != null) {
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
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(26.dp))
                Text(tr("Accounts"), Modifier.padding(start = 10.dp).weight(1f), fontSize = 18.sp)
                Text(range.dotted(), color = SubtleText)
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, DividerColor, RoundedCornerShape(6.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatLine(tr("Compared expenses (last month)"), compared?.let { "$it%" } ?: "–")
                StatLine(tr("Expenses (cash, bank)"), currency.format(otherExpense))
                StatLine(tr("Expenses (card)"), currency.format(cardExpense))
                StatLine(tr("Saved / invested"), currency.format(savingsTransfers))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, DividerColor, RoundedCornerShape(6.dp))
                    .clickable { backup.exportExcel() }.padding(14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, null, tint = Color(0xFF1E8E3E))
                Text(tr("Export data to Excel"), Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), color = SubtleText)
        Text(value)
    }
}

/** Shown while entries are selected: count, net sum and bulk actions. */
@Composable
private fun SelectionBar(selected: List<Txn>, onClear: () -> Unit) {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val platform = LocalPlatform.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ids = selected.map { it.id }
    fun run(block: () -> Int?) = scope.launch {
        val changed = withContext(repo.io) { block() }
        platform.refreshWidgets()
        if (changed != null && changed < ids.size) message = tr("{0} of {1} entries changed (transfers keep their accounts and have no category).", changed, ids.size)
        onClear()
    }
    val net = selected.filter { it.kind != TxnKind.TRANSFER_IN }.sumOf { it.signedAmount }
    Row(Modifier.fillMaxWidth().background(Accent).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClear) { Icon(Icons.Filled.Close, tr("Cancel selection"), tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(tr("{0} selected", selected.size), color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(currency.format(net, showSign = true), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
        IconButton(onClick = { dialog = "date" }) { Icon(Icons.Outlined.CalendarMonth, tr("Change date"), tint = Color.White) }
        IconButton(onClick = { dialog = "category" }) { Icon(Icons.Outlined.Category, tr("Change category"), tint = Color.White) }
        IconButton(onClick = { dialog = "account" }) { Icon(Icons.Outlined.AccountBalanceWallet, tr("Change account"), tint = Color.White) }
        IconButton(onClick = { dialog = "delete" }) { Icon(Icons.Outlined.Delete, tr("Delete"), tint = Color.White) }
    }
    when (dialog) {
        "date" -> DatePickerHost(true, selected.firstOrNull()?.date, { dialog = null }, { d -> if (d != null) run { repo.setDateMany(ids, d); null } })
        "category" -> ChoiceDialog(tr("Change category"), flattenCategories(categories).map { it.id to (if (it.parentId != null) "    " + it.name else it.name) },
            { dialog = null }) { id -> dialog = null; run { repo.setCategoryMany(ids, id) } }
        "account" -> ChoiceDialog(tr("Change account"), accounts.filter { !it.hidden }.map { it.id to it.name }, { dialog = null }) { id ->
            dialog = null; run { repo.setAccountMany(ids, id) }
        }
        "delete" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr("Delete {0} entries?", selected.size)) },
            text = { Text(tr("Transfers are deleted with both sides.")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { dialog = null; run { repo.deleteMany(ids); null } }) { Text(tr("Delete"), color = DangerColor) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { dialog = null }) { Text(tr("Cancel")) } },
        )
    }
    message?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { message = null }, text = { Text(m) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { message = null }) { Text(tr("OK")) } },
        )
    }
}

/** Scrollable single-choice list dialog. */
@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options, key = { it.first }) { (id, label) ->
                    Text(label, Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 12.dp), color = Ink)
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Shown before a sensitive permission prompt (SMS, location): what is read, why, and that it stays on the phone. */
@Composable
fun DisclosureDialog(title: String, body: String, onDismiss: () -> Unit, onContinue: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onContinue) { Text(tr("Continue")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Not now")) } },
    )
}

/** "Repeat · Rent, To mom, … · 11/1 · −30,000": repeating entries still to come in the shown period (not in the totals). */
@Composable
private fun UpcomingRepeatsRow(
    pending: List<Pair<app.moneytracker.domain.RecurringRule, kotlinx.datetime.LocalDate>>,
    catById: Map<String, app.moneytracker.domain.Category>,
    currency: app.moneytracker.domain.CurrencyFormat,
    onClick: () -> Unit,
) {
    val currencies = LocalCurrencies.current
    val names = pending.map { (r, _) -> r }.distinct().map { r -> r.note.ifBlank { categoryPath(r.categoryId, catById) } }
    val first = pending.minOf { it.second }
    val sum = pending.sumOf { (r, _) ->
        val main = app.moneytracker.domain.Fx.toMain(r.amount, r.currency?.let { currencies[it] }, currency.decimals)
        if (r.kind == TxnKind.INCOME) main else -main
    }
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Repeat"), Modifier.width(96.dp), color = SubtleText, fontSize = 14.sp)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(names.joinToString(",  "), color = InkSoft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${first.monthNumber}/${first.dayOfMonth}", color = SubtleText, fontSize = 11.sp)
        }
        Text(currency.format(sum), color = Ink, fontSize = 15.sp)
    }
    HorizontalDivider(color = DividerColor)
}
