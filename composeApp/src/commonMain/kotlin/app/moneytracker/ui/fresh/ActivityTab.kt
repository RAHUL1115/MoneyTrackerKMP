package app.moneytracker.ui.fresh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.classify
import app.moneytracker.domain.totals
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.FilterSheet
import app.moneytracker.ui.LocalBackup
import app.moneytracker.ui.LocalCurrencies
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalListPrefs
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.LocalTxnFilter
import app.moneytracker.ui.MonthPickerDialog
import app.moneytracker.ui.Screen
import app.moneytracker.ui.TxnFilter
import app.moneytracker.ui.VoiceParser
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.flattenCategories
import app.moneytracker.ui.formatTime
import app.moneytracker.ui.mainCategoryName
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberCarryOver
import app.moneytracker.ui.rememberFilteredRows
import app.moneytracker.ui.rememberFilteredTxns
import app.moneytracker.ui.rememberPeriod
import app.moneytracker.ui.toReportRow
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.math.abs

// Activity tab: every entry by day, as a calendar, by month, and the notes. Replaces the classic Trans. tab.

internal enum class ActView(val title: String) { LIST("List"), CALENDAR("Calendar"), MONTHS("Months"), NOTES("Notes") }

/** Quick filter on the list: spending (and refunds) only, or only transfers. */
internal enum class ActKind(val title: String) { ALL("All"), OUT("Out"), MOVED("Moved") }

/** Search › "Apply filter to Activity" hands its filter to the tab through this. */
internal object ActivityHandoff {
    var pending by mutableStateOf<ActivityFilterRequest?>(null)
}

internal data class ActivityFilterRequest(val filter: TxnFilter, val kind: ActKind, val periodKey: Int?)

/** Work that must outlive the screen that started it (Undo after the screen closed). */
internal fun activityLaunch(block: suspend () -> Unit) = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { block() }

internal val actMonthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
internal fun YearMonth.actLongLabel() = "${tr(actMonthNames[month - 1])} $year"
/** "Wed 21 Oct", with "Today · " in front for today. */
internal fun LocalDate.actDayLabel(today: LocalDate) =
    (if (this == today) tr("Today") + " · " else "") + "${weekday()} $dayOfMonth ${monthShort(monthNumber)}"

private fun startView(s: String?) = when (s) {
    "CALENDAR" -> ActView.CALENDAR
    "MONTHLY", "TOTAL" -> ActView.MONTHS
    "NOTE" -> ActView.NOTES
    else -> ActView.LIST
}

@Composable
internal fun ActivityTab() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var periodKey by rememberPeriod()
    var view by rememberSaveable { mutableStateOf(startView(repo.settingNow(Keys.START_SCREEN))) }
    var kind by rememberSaveable { mutableStateOf(ActKind.ALL) }
    var filter by remember { mutableStateOf(TxnFilter()) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var filterSheet by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf(false) }
    var monthPicker by remember { mutableStateOf(false) }
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val ym = YearMonth.fromKey(periodKey)
    val range = ps.month(ym)

    val handoff = ActivityHandoff.pending
    LaunchedEffect(handoff) {
        val h = handoff ?: return@LaunchedEffect
        filter = h.filter; kind = h.kind; h.periodKey?.let { periodKey = it }; view = ActView.LIST
        ActivityHandoff.pending = null
    }
    fun endSelect() { selecting = false; selection = emptySet() }
    LaunchedEffect(view, periodKey) { if (selecting) endSelect() }
    DisposableEffect(selecting) {
        val remove = if (!selecting) ({}) else nav.interceptBack { endSelect(); true }
        onDispose { remove() }
    }

    val swipe by remember(repo) { repo.setting(Keys.SWIPE) }.collectAsState(null)
    val swipeModifier = if (swipe == "NONE" || selecting) Modifier else Modifier.pointerInput(swipe, view, periodKey) {
        var dx = 0f
        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
            val dir = when { dx < -120f -> 1; dx > 120f -> -1; else -> 0 }
            if (dir != 0) {
                if (swipe == "TAB") view = ActView.entries[(view.ordinal + dir).coerceIn(0, ActView.entries.lastIndex)]
                else if (view != ActView.NOTES) periodKey = ym.plus(dir * if (view == ActView.MONTHS) 12 else 1).key
            }
        }) { _, amount -> dx += amount }
    }

    Column(Modifier.fillMaxSize()) {
        TabHeader(if (selecting) tr("Select") else tr("Activity")) {
            if (selecting) HeaderButton(tr("Done"), filled = true) { endSelect() }
            else {
                IconTile(Glyph.Search, tr("Search"), { nav.push(Screen.Search) })
                // Bookmarks are saved entries: pick one to open it filled in, then check and save.
                IconTile(Glyph.Star, tr("Bookmarks"), { nav.push(Screen.Templates) })
                if (view == ActView.NOTES) HeaderButton(tr("Note"), filled = true, leading = Glyph.Add) { nav.push(Screen.EditNote()) }
                else IconTile(Glyph.Filter, tr("Filter by category or account"), { filterSheet = true },
                    tint = if (filter.isActive) F.accent else F.ink, background = if (filter.isActive) F.accentTint else F.card)
                // Less frequent actions, Select among them, always last.
                IconTile(Glyph.More, tr("More"), { moreSheet = true }, badge = pending.size)
            }
        }
        Segmented(ActView.entries.map { tr(it.title) }, view.ordinal, { view = ActView.entries[it] }, Modifier.padding(horizontal = F.gutter))
        if (filter.isActive && view != ActView.NOTES) {
            val catById by repo.categoryById.collectAsState(emptyMap())
            val accountName by repo.accountNames.collectAsState(emptyMap())
            ActFilterBar(filter, catById, accountName, onEdit = { filterSheet = true }, onClear = { filter = TxnFilter() })
        }
        CompositionLocalProvider(LocalTxnFilter provides filter) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp).then(swipeModifier)) {
                when (view) {
                    ActView.LIST -> ActList(
                        range, ym, kind, onKind = { kind = it },
                        onPrev = { periodKey = ym.plus(-1).key }, onNext = { periodKey = ym.plus(1).key }, onPickMonth = { monthPicker = true },
                        selecting = selecting, selection = selection,
                        onSelect = { id -> selecting = true; selection = if (id in selection) selection - id else selection + id },
                        onEndSelect = ::endSelect,
                    )
                    ActView.CALENDAR -> ActivityCalendarView(
                        ym, range, ps.weekStart,
                        onPrev = { periodKey = ym.plus(-1).key }, onNext = { periodKey = ym.plus(1).key }, onPickMonth = { monthPicker = true },
                    )
                    ActView.MONTHS -> ActivityMonthsView(
                        ym, ps, onPick = { periodKey = it.key },
                        onOpenList = { periodKey = it.key; view = ActView.LIST }, onPickMonth = { monthPicker = true },
                    )
                    ActView.NOTES -> ActivityNotesView()
                }
            }
        }
    }
    if (monthPicker) MonthPickerDialog(ym, ps.labelFor(repo.today()), { monthPicker = false }) { periodKey = it.key; monthPicker = false }
    if (filterSheet) FilterSheet(range, filter, onDismiss = { filterSheet = false }) { filter = it; filterSheet = false }
    if (moreSheet) MoreSheet(pending.size, onDismiss = { moreSheet = false },
        onSelect = if (view == ActView.LIST) ({ moreSheet = false; selecting = true }) else null)
}

@Composable
internal fun HeaderButton(text: String, filled: Boolean, leading: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(F.iconShape).background(if (filled) F.accent else F.card).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = if (filled) F.onAccent else F.ink, modifier = Modifier.size(18.dp))
        Text(text, color = if (filled) F.onAccent else F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActFilterBar(filter: TxnFilter, catById: Map<String, Category>, accountName: Map<String, String>, onEdit: () -> Unit, onClear: () -> Unit) {
    val names = filter.categoryIds.mapNotNull { catById[it]?.name } + filter.accountIds.mapNotNull { accountName[it] }
    Row(
        Modifier.padding(horizontal = F.gutter).padding(top = 10.dp).fillMaxWidth().clip(F.iconShape).background(F.accentTint)
            .clickable(onClick = onEdit).padding(start = 14.dp, end = 4.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Filter: {0}", names.joinToString(", ")), Modifier.weight(1f), color = F.accent, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = tr("Clear filter"), onClick = onClear), contentAlignment = Alignment.Center) {
            Icon(Glyph.Close, tr("Clear filter"), tint = F.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** Select, and the shortcuts the classic tab had in its top bar. */
@Composable
private fun MoreSheet(pending: Int, onDismiss: () -> Unit, onSelect: (() -> Unit)?) {
    val nav = LocalNavigator.current
    val repo = LocalRepo.current
    val platform = LocalPlatform.current
    val currency = LocalCurrency.current
    val backup = LocalBackup.current
    Sheet(tr("Activity"), onDismiss) {
        if (onSelect != null) SheetOption(tr("Select entries"), subtitle = tr("Change or delete several at once"), onClick = onSelect)
        SheetOption(tr("Detected transactions"), subtitle = if (pending > 0) tr("{0} to review", pending) else null) { onDismiss(); nav.push(Screen.Inbox) }
        if (platform.voiceAvailable) SheetOption(tr("Voice input")) {
            onDismiss()
            platform.listen { text -> nav.push(Screen.EditTxn(preset = VoiceParser.parse(text, repo.today(), currency))) }
        }
        SheetOption(tr("Add note")) { onDismiss(); nav.push(Screen.EditNote()) }
        SheetOption(tr("Repeat entries")) { onDismiss(); nav.push(Screen.Recurring) }
        SheetOption(tr("Export data to Excel")) { onDismiss(); backup.exportExcel() }
    }
}

/** ‹ October 2026 ›; tapping the label opens the month picker. */
@Composable
internal fun ActPeriodSwitch(label: String, onPrev: () -> Unit, onNext: () -> Unit, onLabel: () -> Unit, tile: Boolean = false, minWidth: Int = 112) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ArrowButton(Glyph.ChevronLeft, tr("Previous"), tile, onPrev)
        Text(label, Modifier.widthIn(min = minWidth.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onLabel).padding(horizontal = 4.dp, vertical = 8.dp),
            color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
        ArrowButton(Glyph.ChevronRight, tr("Next"), tile, onNext)
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, tile: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(if (tile) 36.dp else 32.dp, 36.dp).clip(RoundedCornerShape(10.dp)).background(if (tile) F.page else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = F.ink, modifier = Modifier.size(20.dp)) }
}

/** Money in · Money out · Net in a white strip. */
@Composable
internal fun ActSummary(income: Long, expense: Long, modifier: Modifier = Modifier, background: Color = F.card) {
    val currency = LocalCurrency.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background).padding(vertical = 10.dp, horizontal = 4.dp)) {
        SummaryCell(tr("Money in"), currency.format(income), F.income)
        Box(Modifier.width(1.dp).height(36.dp).background(F.line))
        SummaryCell(tr("Money out"), currency.format(expense), F.expense)
        Box(Modifier.width(1.dp).height(36.dp).background(F.line))
        SummaryCell(tr("Net"), currency.format(income - expense), F.ink)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SummaryCell(label: String, value: String, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = F.muted, fontSize = 12.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

// ───────────────────────── list

@Composable
private fun ActList(
    range: DateRange,
    ym: YearMonth,
    kind: ActKind,
    onKind: (ActKind) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickMonth: () -> Unit,
    selecting: Boolean,
    selection: Set<String>,
    onSelect: (String) -> Unit,
    onEndSelect: () -> Unit,
) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val txns by rememberFilteredTxns(range)
    val rows by rememberFilteredRows(range)
    val data = rememberActEntryData()
    val carry = rememberCarryOver(range)
    val rules by repo.rules.collectAsState(emptyList())
    val today = repo.today()
    val t = totals(rows)
    // TRANSFER_IN rows mirror TRANSFER_OUT; show each transfer once.
    val visible = txns.filter { it.kind != TxnKind.TRANSFER_IN }.filter {
        when (kind) {
            ActKind.ALL -> true
            ActKind.OUT -> it.kind == TxnKind.EXPENSE
            ActKind.MOVED -> it.kind == TxnKind.TRANSFER_OUT
        }
    }
    val dayTotals = rows.groupBy { it.date }.mapValues { totals(it.value) }
    val upcoming = rules.flatMap { r -> Recurrence.upcoming(r, range).map { r to it } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = F.gutter, end = F.gutter, bottom = if (selecting) 140.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "period") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActPeriodSwitch(ym.actLongLabel(), onPrev, onNext, onPickMonth)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                        ActKind.entries.forEach { k -> ActChip(tr(k.title), k == kind) { onKind(k) } }
                    }
                }
            }
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActSummary(t.income, t.expense)
                    if (carry != null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Text(tr("Carried over {0}", currency.format(carry)), Modifier.weight(1f), color = F.muted, fontSize = 12.sp, style = F.money)
                        Text(tr("Balance {0}", currency.format(carry + t.net)), color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money)
                    }
                }
            }
            if (upcoming.isNotEmpty()) item(key = "repeats") { UpcomingRepeats(upcoming, data.catById) { nav.push(Screen.Recurring) } }
            if (visible.isEmpty()) item(key = "empty") {
                FCard(padding = 24.dp) {
                    Text(
                        if (kind == ActKind.MOVED) tr("No transfers in {0}", tr(actMonthNames[ym.month - 1])) else tr("Nothing recorded in {0}", tr(actMonthNames[ym.month - 1])),
                        Modifier.fillMaxWidth(), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    Text(tr("Change the filter or add an entry with +."), Modifier.fillMaxWidth().padding(top = 6.dp), color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            visible.groupBy { it.date }.forEach { (date, dayTxns) ->
                item(key = "d$date") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            Text(date.actDayLabel(today), Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            val dt = dayTotals[date]
                            if (dt != null) Text(
                                listOfNotNull(
                                    if (dt.income > 0) "+" + currency.format(dt.income) else null,
                                    if (dt.expense > 0) currency.format(-(dt.expense)) else null,
                                ).joinToString(" · "),
                                color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money,
                            )
                        }
                        Column(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).padding(horizontal = 18.dp)) {
                            dayTxns.forEachIndexed { i, txn ->
                                if (i > 0) Divider()
                                ActEntryRow(
                                    txn, data, selecting = selecting, selected = txn.id in selection,
                                    onLongClick = { onSelect(txn.id) },
                                ) { if (selecting) onSelect(txn.id) else nav.push(Screen.EditTxn(txnId = txn.id)) }
                            }
                        }
                    }
                }
            }
        }
        if (selecting) SelectionPanel(txns.filter { it.id in selection }, onEndSelect, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun ActChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(999.dp)).background(if (selected) F.accentTint else F.card)
            .then(if (selected) Modifier else Modifier.border(1.dp, F.border, RoundedCornerShape(999.dp)))
            .clickable(onClick = onClick).padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) F.accent else F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** "Repeat · Rent, To mom · 1 Nov · −30,000": repeating entries still to come this period (not in the totals). */
@Composable
private fun UpcomingRepeats(pending: List<Pair<app.moneytracker.domain.RecurringRule, LocalDate>>, catById: Map<String, Category>, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val names = pending.map { it.first }.distinct().map { r -> r.note.ifBlank { categoryPath(r.categoryId, catById) } }
    val first = pending.minOf { it.second }
    val sum = pending.sumOf { (r, _) ->
        val main = app.moneytracker.domain.Fx.toMain(r.amount, r.currency?.let { currencies[it] }, currency.decimals)
        if (r.kind == TxnKind.INCOME) main else -main
    }
    FCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mark(tr("Repeat"), 36.dp, icon = Glyph.Repeat)
            Column(Modifier.weight(1f)) {
                Text(tr("Coming up · {0}", "${first.dayOfMonth} ${monthShort(first.monthNumber)}"), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(names.joinToString(", "), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(currency.format(sum, showSign = true), color = if (sum >= 0) F.income else F.expense, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money)
        }
    }
}

// ───────────────────────── one entry

/** Lookups every entry row needs. */
internal class ActEntryData(
    val accountName: Map<String, String>,
    val catById: Map<String, Category>,
    val flags: Map<String, Boolean>,
    val tags: Map<String, List<String>>,
    val photos: Set<String>,
)

@Composable
internal fun rememberActEntryData(): ActEntryData {
    val repo = LocalRepo.current
    val accounts by repo.accounts.collectAsState(emptyList())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val tags by repo.txnTagNames.collectAsState(emptyMap())
    val photos by repo.txnIdsWithPhotos.collectAsState(emptySet())
    return remember(accounts, accountName, catById, tags, photos) {
        ActEntryData(accountName, catById, accounts.associate { it.id to it.transferAsExpense }, tags, photos)
    }
}

/** Category mark · note and details · amount. Refunds show as +amount in the income colour. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActEntryRow(
    txn: Txn,
    data: ActEntryData,
    selecting: Boolean = false,
    selected: Boolean = false,
    showDate: Boolean = false,
    compact: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val prefs = LocalListPrefs.current
    val transfer = txn.kind == TxnKind.TRANSFER_OUT || txn.kind == TxnKind.TRANSFER_IN
    val adjust = txn.kind == TxnKind.ADJUST_UP || txn.kind == TxnKind.ADJUST_DOWN
    val catName = when {
        transfer -> tr("Transfer")
        adjust -> tr(MODIFIED_BALANCE_NAME)
        else -> categoryPath(txn.categoryId, data.catById)
    }
    val title = listOfNotNull(txn.note.ifBlank { null }, txn.installmentLabel).joinToString(" ").ifBlank { catName }
    val account = when (txn.kind) {
        TxnKind.TRANSFER_OUT -> "${data.accountName[txn.accountId].orEmpty()} → ${data.accountName[txn.toAccountId].orEmpty()}"
        TxnKind.TRANSFER_IN -> "${data.accountName[txn.toAccountId].orEmpty()} → ${data.accountName[txn.accountId].orEmpty()}"
        else -> data.accountName[txn.accountId].orEmpty()
    }
    val meta = listOfNotNull(
        if (showDate) "${txn.date.dayOfMonth} ${monthShort(txn.date.monthNumber)} ${txn.date.year}" else null,
        if (prefs.showTime) txn.timeOfDay?.let { formatTime(it) } else null,
        if (txn.isRefund) tr("Refund") else null,
        if (transfer) null else catName,
        account.ifBlank { null },
        if (txn.recurringRuleId != null) tr("repeats") else null,
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
    ) {
        if (selecting) Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) F.accent else F.card)
                .border(2.dp, if (selected) F.accent else F.border, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Text("✓", color = F.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        val markSize = if (compact) 32.dp else 40.dp
        when {
            transfer -> Mark(tr("Transfer"), markSize, icon = Glyph.Swap)
            adjust -> Mark(catName, markSize, icon = Glyph.Edit)
            else -> Mark(mainCategoryName(txn.categoryId, data.catById), markSize)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, Modifier.weight(1f, fill = false), color = F.ink, fontSize = if (compact) 14.sp else 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (txn.id in data.photos) Icon(Glyph.Camera, tr("Has a photo"), tint = F.muted, modifier = Modifier.size(14.dp))
                if (txn.lat != null) Icon(Glyph.Place, tr("Has a location"), tint = F.muted, modifier = Modifier.size(14.dp))
            }
            if (meta.isNotEmpty()) Text(meta, Modifier.padding(top = 3.dp), color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (prefs.showDescription && txn.memo.isNotBlank()) Text(txn.memo, color = F.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val tags = data.tags[txn.id].orEmpty()
            if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" }, color = F.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val flow = classify(txn.toReportRow(data.flags))
        val plus = txn.isRefund || flow == Flow.INCOME
        val color = when {
            plus -> F.income
            adjust -> F.muted
            flow == Flow.EXPENSE -> F.expense
            else -> F.ink
        }
        val prefix = when {
            plus -> "+"
            flow == Flow.EXPENSE -> "−"
            else -> ""
        }
        Column(horizontalAlignment = Alignment.End) {
            val foreign = txn.currency?.let { currencies[it] }
            if (foreign != null && txn.originalAmount != null) {
                Text(prefix + foreign.format.format(abs(txn.originalAmount)), color = color, fontSize = if (compact) 14.sp else 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
                Text(currency.format(abs(txn.amount)), color = F.muted, fontSize = 11.sp, style = F.money, maxLines = 1)
            } else Text(prefix + currency.format(abs(txn.amount)), color = color, fontSize = if (compact) 14.sp else 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
        }
    }
}

// ───────────────────────── multi-select

@Composable
private fun SelectionPanel(selected: List<Txn>, onDone: () -> Unit, modifier: Modifier) {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val platform = LocalPlatform.current
    val toaster = LocalToaster.current
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val groups by repo.groups.collectAsState(emptyList())
    var sheet by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val ids = selected.map { it.id }
    val n = ids.size
    val net = selected.filter { it.kind != TxnKind.TRANSFER_IN }.sumOf { it.signedAmount }

    fun entries(count: Int) = if (count == 1) tr("1 entry") else tr("{0} entries", count)
    /** Runs [change], then offers Undo that runs [undo]. A count below [n] means some entries could not take the change. */
    fun apply(label: String, change: () -> Int?, undo: () -> Unit) {
        sheet = null
        activityLaunch {
            val changed = withContext(repo.io) { change() }
            platform.refreshWidgets()
            val msg = if (changed != null && changed < n) tr("{0} of {1} entries changed (transfers keep their accounts and have no category).", changed, n)
                else tr("{0} for {1}", label, entries(n))
            toaster.show(msg) { activityLaunch { withContext(repo.io) { undo() }; platform.refreshWidgets() } }
        }
        onDone()
    }
    fun setDate(d: LocalDate) {
        val before = selected.groupBy({ it.date }, { it.id })
        apply(tr("Date changed to {0}", "${d.dayOfMonth} ${monthShort(d.monthNumber)}"), { repo.setDateMany(ids, d); null }) {
            before.forEach { (date, list) -> repo.setDateMany(list, date) }
        }
    }

    Column(
        modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth().clip(F.cardShape).background(F.ink).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (n == 0) tr("Tap entries to select them") else tr("{0} selected · {1}", n, currency.format(net, showSign = true)),
            Modifier.padding(start = 4.dp), color = F.page, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton(tr("Date"), n > 0) { sheet = "date" }
            PanelButton(tr("Category"), n > 0) { sheet = "category" }
            PanelButton(tr("Account"), n > 0) { sheet = "account" }
            PanelButton(tr("Delete"), n > 0, danger = true) { sheet = "delete" }
        }
    }

    when (sheet) {
        "date" -> {
            val today = repo.today()
            val first = selected.firstOrNull()?.date ?: today
            Sheet(tr("Change date"), { sheet = null }) {
                Text(tr("Moves the selected entries to another day."), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                ActOptionGrid(
                    listOf(
                        ActOption(tr("Today"), today.actDayLabel(LocalDate(1970, 1, 1)), today.dayOfMonth.toString()) { setDate(today) },
                        today.minus(DatePeriod(days = 1)).let { y -> ActOption(tr("Yesterday"), y.actDayLabel(LocalDate(1970, 1, 1)), y.dayOfMonth.toString()) { setDate(y) } },
                        LocalDate(first.year, first.monthNumber, 1).let { f -> ActOption("1 ${monthShort(f.monthNumber)}", f.actDayLabel(LocalDate(1970, 1, 1)), "1") { setDate(f) } },
                        ActOption(tr("Pick a date…"), tr("Any day"), "…") { sheet = null; pickDate = true },
                    ),
                )
            }
        }
        "category" -> Sheet(tr("Change category"), { sheet = null }) {
            Text(tr("Transfers have no category and stay as they are."), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            val byId = categories.associateBy { it.id }
            ActOptionGrid(flattenCategories(categories).map { c ->
                val main = c.parentId?.let { byId[it]?.name }
                ActOption(c.name, main ?: tr(if (c.kind == app.moneytracker.domain.CategoryKind.INCOME) "Income" else "Expense"), null, markName = main ?: c.name) {
                    val before = selected.filter { it.categoryId != null }.groupBy({ it.categoryId!! }, { it.id })
                    apply(tr("Category set to {0}", c.name), { repo.setCategoryMany(ids, c.id) }) {
                        before.forEach { (cat, list) -> repo.setCategoryMany(list, cat) }
                    }
                }
            })
        }
        "account" -> Sheet(tr("Change account"), { sheet = null }) {
            Text(tr("Transfers keep their own accounts."), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            val groupName = groups.associate { it.id to it.name }
            ActOptionGrid(accounts.filter { !it.hidden }.map { a ->
                ActOption(a.name, groupName[a.groupId].orEmpty(), null) {
                    val before = selected.groupBy({ it.accountId }, { it.id })
                    apply(tr("Account set to {0}", a.name), { repo.setAccountMany(ids, a.id) }) {
                        before.forEach { (acc, list) -> repo.setAccountMany(list, acc) }
                    }
                }
            })
        }
        "delete" -> Confirm(
            tr("Delete {0}?", entries(n)),
            if (selected.any { it.transferGroupId != null }) tr("Transfers are deleted with both sides.") else tr("This can't be undone."),
            tr("Delete"), onDismiss = { sheet = null },
        ) {
            val count = n
            activityLaunch {
                withContext(repo.io) { repo.deleteMany(ids) }
                platform.refreshWidgets()
                toaster.show(tr("{0} deleted", entries(count)))
            }
            onDone()
        }
    }
    DatePickerHost(pickDate, selected.firstOrNull()?.date, { pickDate = false }, { d -> if (d != null) setDate(d) })
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PanelButton(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (danger) F.danger else F.page.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = (if (danger) Color.White else F.page).copy(alpha = if (enabled) 1f else 0.45f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

internal class ActOption(val label: String, val sub: String, val mark: String?, val markName: String = label, val onClick: () -> Unit)

/** Two columns of bordered choice tiles with a mark (the bulk-edit sheets). */
@Composable
internal fun ActOptionGrid(options: List<ActOption>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { o ->
                    Row(
                        Modifier.weight(1f).heightIn(min = 52.dp).clip(F.iconShape).border(1.dp, F.border, F.iconShape)
                            .clickable(onClick = o.onClick).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (o.mark != null) Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(F.accentTint), contentAlignment = Alignment.Center) {
                            Text(o.mark, color = F.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        } else Mark(o.markName, 30.dp)
                        Column(Modifier.weight(1f)) {
                            Text(o.label, color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (o.sub.isNotEmpty()) Text(o.sub, color = F.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
