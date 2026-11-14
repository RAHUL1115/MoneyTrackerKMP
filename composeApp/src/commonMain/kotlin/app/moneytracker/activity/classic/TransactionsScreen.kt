package app.moneytracker.activity.classic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.moneytracker.activity.ActivityModel
import app.moneytracker.activity.ActivityState
import app.moneytracker.activity.EntryListState
import app.moneytracker.activity.rememberActivityModel
import app.moneytracker.activity.rememberEntryListModel
import app.moneytracker.activity.swipeSteps
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.app.Screen
import app.moneytracker.core.PrivacyEyeButton
import app.moneytracker.core.collectAsState
import app.moneytracker.core.rememberSetting
import app.moneytracker.core.tr
import app.moneytracker.data.Prefs
import app.moneytracker.data.Swipe
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.CardShape
import app.moneytracker.designsystem.classic.DayHeader
import app.moneytracker.designsystem.classic.EmptyState
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.IncomeExpenseStrip
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.ListCardMargin
import app.moneytracker.designsystem.classic.ListRowInset
import app.moneytracker.designsystem.classic.MonthPickerDialog
import app.moneytracker.designsystem.classic.MonthTopBar
import app.moneytracker.designsystem.classic.SummaryStrip
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.UnderlineTabs
import app.moneytracker.designsystem.classic.label
import app.moneytracker.domain.classify
import app.moneytracker.entry.classic.ListenSheet
import app.moneytracker.lists.classic.NotesView

/** Remembers the selected report month (per tab). */
@Composable
fun rememberPeriod(): MutableState<Int> {
    val repo = LocalRepo.current
    return rememberSaveable {
        mutableStateOf(repo.settingsStore.periodSettingsNow().labelFor(repo.today()).key)
    }
}

private enum class TransView(val title: String) { DAILY("Daily"), CALENDAR("Calendar"), MONTHLY("Monthly"), TOTAL("Total"), NOTE("Note") }

/** Which of the tab's sheets and dialogs is open. */
private enum class TransSheet { FILTER, MONTH, LISTEN }

@Composable
fun TransactionsTab() {
    val nav = LocalNavigator.current
    val model = rememberActivityModel()
    val state by model.state.collectAsState()
    var view by rememberSaveable { mutableStateOf(TransView.entries.first { it.name == model.startScreen.name }) }
    var sheet by remember { mutableStateOf<TransSheet?>(null) }
    var listenTyping by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TransTopBar(state, yearly = view == TransView.MONTHLY, onStep = model::stepMonths, onSheet = { sheet = it })
            UnderlineTabs(TransView.entries.map { tr(it.title) }, view.ordinal) { view = TransView.entries[it] }
            if (state.filter.isActive && view != TransView.TOTAL && view != TransView.NOTE) {
                // The classic bar names only categories that still exist.
                FilterBar(state.filterNames(includeDeletedCategories = false), onEdit = { sheet = TransSheet.FILTER }, onClear = model::clearFilter)
            }
            TransBody(model, view, onView = { view = it }, onListen = { typing -> listenTyping = typing; sheet = TransSheet.LISTEN })
        }
        TransSheets(model, sheet, listenTyping, onClose = { sheet = null })
        TransFabs(view, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun TransTopBar(state: ActivityState, yearly: Boolean, onStep: (Int) -> Unit, onSheet: (TransSheet) -> Unit) {
    val nav = LocalNavigator.current
    val ym = state.period
    val actions: @Composable () -> Unit = {
        // Detected SMS open from the card at the top of Daily; no separate icon up here.
        // No mic here: the Smart add bar under the tabs has it, next to typing.
        PrivacyEyeButton()
        IconButton(onClick = { nav.push(Screen.Templates) }) { Icon(Icons.Outlined.StarOutline, tr("Bookmarks")) }
        IconButton(onClick = { nav.push(Screen.Search) }) { Icon(Icons.Outlined.Search, tr("Search")) }
        IconButton(onClick = { onSheet(TransSheet.FILTER) }) {
            Icon(Icons.Outlined.FilterList, tr("Filter"), tint = if (state.filter.isActive) Accent else LocalContentColor.current)
        }
    }
    val step = if (yearly) 12 else 1
    MonthTopBar(if (yearly) ym.year.toString() else ym.label(), { onStep(-step) }, { onStep(step) }, onLabelClick = { onSheet(TransSheet.MONTH) }, actions = actions)
}

/** The picked view; a horizontal swipe changes the month (a year on Monthly) or the view, per Configuration. */
@Composable
private fun TransBody(model: ActivityModel, view: TransView, onView: (TransView) -> Unit, onListen: (typing: Boolean) -> Unit) {
    val state by model.state.collectAsState()
    val swipe by rememberSetting(Prefs.SWIPE)
    val swipeModifier = if (swipe == Swipe.NONE) Modifier else Modifier.swipeSteps(swipe, view, state.period) { dir ->
        if (swipe == Swipe.TAB) onView(TransView.entries[(view.ordinal + dir).coerceIn(0, TransView.entries.lastIndex)])
        else model.stepMonths(dir * if (view == TransView.MONTHLY) 12 else 1)
    }
    Column(Modifier.fillMaxSize().then(swipeModifier)) {
        when (view) {
            TransView.DAILY -> DailyView(model, onType = { onListen(true) }, onSpeak = { onListen(false) })
            TransView.CALENDAR -> CalendarView(model)
            TransView.MONTHLY -> MonthlyView(model)
            TransView.TOTAL -> TotalView(model)
            TransView.NOTE -> NotesView()
        }
    }
}

@Composable
private fun TransSheets(model: ActivityModel, sheet: TransSheet?, listenTyping: Boolean, onClose: () -> Unit) {
    val nav = LocalNavigator.current
    val repo = LocalRepo.current
    val state by model.state.collectAsState()
    when (sheet) {
        TransSheet.MONTH -> MonthPickerDialog(state.period, state.periods.labelFor(repo.today()), onClose) { model.showMonth(it); onClose() }
        TransSheet.LISTEN -> ListenSheet(onDismiss = onClose, startTyping = listenTyping) { g -> nav.push(Screen.EditTxn(preset = g?.draft)) }
        TransSheet.FILTER -> FilterSheet(state.range, state.filter, onDismiss = onClose) { model.setFilter(it); onClose() }
        null -> {}
    }
}

/** + (a note on Notes), with the Note button beside it on Daily (Configuration › Note button). */
@Composable
private fun TransFabs(view: TransView, modifier: Modifier) {
    val nav = LocalNavigator.current
    val noteButton by rememberSetting(Prefs.NOTE_BUTTON)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (view == TransView.DAILY && noteButton) {
            SmallFloatingActionButton(
                onClick = { nav.push(Screen.EditNote()) }, modifier = Modifier.padding(end = 0.dp, bottom = 20.dp),
                containerColor = SurfaceColor, contentColor = Accent, shape = RoundedCornerShape(14.dp),
            ) { Icon(Icons.Outlined.EditNote, tr("Add note")) }
        }
        AddFab {
            if (view == TransView.NOTE) nav.push(Screen.EditNote()) else nav.push(Screen.EditTxn())
        }
    }
}

@Composable
fun AddFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick, modifier = modifier.padding(20.dp), shape = RoundedCornerShape(18.dp),
        containerColor = Accent, contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(3.dp, 3.dp, 3.dp, 3.dp),
    ) { Icon(Icons.Filled.Add, tr("Add"), Modifier.size(28.dp)) }
}

// ───────────────────────── Daily

@Composable
private fun DailyView(activity: ActivityModel, onType: () -> Unit, onSpeak: () -> Unit) {
    val nav = LocalNavigator.current
    val model = rememberEntryListModel(activity)
    val period by activity.state.collectAsState()
    val list by model.state.collectAsState()
    // Multi-select: long-press starts it, taps toggle, Back clears it.
    var selection by remember(period.range) { mutableStateOf(emptySet<String>()) }
    DisposableEffect(selection.isEmpty()) {
        val remove = if (selection.isEmpty()) ({}) else nav.interceptBack { selection = emptySet(); true }
        onDispose { remove() }
    }
    if (selection.isNotEmpty()) SelectionBar(list.entries.filter { it.id in selection }, onClear = { selection = emptySet() })
    DailyContent(list, selection, onType, onSpeak, onToggle = { id -> selection = if (id in selection) selection - id else selection + id }) {
        nav.push(Screen.EditTxn(txnId = it))
    }
}

@Composable
private fun DailyContent(
    list: EntryListState, selection: Set<String>, onType: () -> Unit, onSpeak: () -> Unit, onToggle: (String) -> Unit, onOpen: (String) -> Unit,
) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val t = list.totals
    val carry = list.carryOver
    if (carry != null) {
        SummaryStrip(listOf(
            Triple(tr("Carried over"), carry, InkSoft), Triple(tr("Income"), t.income, IncomeColor),
            Triple(tr("Expenses"), t.expense, ExpenseColor), Triple(tr("Balance"), carry + t.net, InkSoft),
        ), currency)
    } else IncomeExpenseStrip(t.income, t.expense, currency)
    // Repeats not added yet that fall in this period, summed in one row (shown on future months).
    list.upcoming?.let { UpcomingRepeatsRow(it) { nav.push(Screen.Recurring) } }
    if (list.visible.isEmpty()) { ClassicSmartAddBar(onType, onSpeak); ClassicDetectedCard(); EmptyState(icon = Icons.Outlined.Inbox); return }
    DailyList(list, selection, onType, onSpeak, onToggle, onOpen)
}

@Composable
private fun DailyList(
    list: EntryListState, selection: Set<String>, onType: () -> Unit, onSpeak: () -> Unit, onToggle: (String) -> Unit, onOpen: (String) -> Unit,
) {
    val currency = LocalCurrency.current
    val listState = rememberLazyListState()
    val barShown = rememberSmartAddLatch(listState)
    val lookups = list.lookups
    LazyColumn(Modifier.fillMaxSize().nestedScroll(barShown), state = listState) {
        item(key = "smartAdd") {
            AnimatedVisibility(barShown.shown, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { ClassicSmartAddBar(onType, onSpeak) }
        }
        // The newest bank SMS payment, ready to add in one tap; stays put while scrolling back up.
        item(key = "detected") { ClassicDetectedCard() }
        // One card per day: its header, then one lazy item per entry, each drawing its slice of the card.
        list.days.forEach { (date, dayTxns) ->
            item(key = "h$date", contentType = "day") {
                val dt = list.dayTotals[date]
                Box(Modifier.padding(top = 6.dp).daySlice(first = true, last = false)) {
                    DayHeader(date, dt?.income ?: 0, dt?.expense ?: 0, currency, inset = ListRowInset)
                }
            }
            itemsIndexed(dayTxns, key = { _, t -> "e${t.id}" }, contentType = { _, _ -> "entry" }) { i, txn ->
                val last = i == dayTxns.lastIndex
                Box(Modifier.daySlice(first = false, last = last)) {
                    TxnLine(
                        txn, lookups.accountName, lookups.catById, classify(lookups.rules.row(txn)), tags = lookups.tags[txn.id].orEmpty(),
                        hasPhoto = txn.id in lookups.photos, inset = ListRowInset, selecting = selection.isNotEmpty(), selected = txn.id in selection,
                        extraBottom = if (last) DAY_CARD_BOTTOM else 0.dp, onLongClick = { if (txn.id !in selection) onToggle(txn.id) },
                    ) { if (selection.isNotEmpty()) onToggle(txn.id) else onOpen(txn.id) }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** Space under a day card's last entry. */
private val DAY_CARD_BOTTOM = 4.dp

/**
 * One lazy item's slice of a Daily day card (same margin and colour as [card]): the top corners on the day's header,
 * the bottom corners on its last entry, so header and entries look like one card.
 */
private fun Modifier.daySlice(first: Boolean, last: Boolean): Modifier {
    val square = CornerSize(0)
    val shape = when {
        first -> CardShape.copy(bottomStart = square, bottomEnd = square)
        last -> CardShape.copy(topStart = square, topEnd = square)
        else -> RectangleShape
    }
    return padding(horizontal = ListCardMargin).clip(shape).background(SurfaceColor)
}

/**
 * Smart add latches: shown when the list opens, gone once it scrolls out of view (coming back to the top doesn't
 * bring it back), and revealed again by a second pull down while already at the top.
 */
private class SmartAddLatch(private val revealPx: Float) : NestedScrollConnection {
    var shown by mutableStateOf(true)
    private var pulled = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Downward drag the list couldn't use: it's already at the top.
        if (!shown && available.y > 0f && source == NestedScrollSource.UserInput) {
            pulled += available.y
            if (pulled > revealPx) { shown = true; pulled = 0f }
        } else if (available.y <= 0f) pulled = 0f
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }
}

@Composable
private fun rememberSmartAddLatch(listState: LazyListState): SmartAddLatch {
    val revealPx = with(LocalDensity.current) { 56.dp.toPx() }
    val latch = remember { SmartAddLatch(revealPx) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { if (it >= 1) latch.shown = false }
    }
    return latch
}
