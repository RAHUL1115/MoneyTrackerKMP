package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.Prefs
import app.moneytracker.data.StartScreen
import app.moneytracker.data.Swipe
import app.moneytracker.domain.YearMonth
import app.moneytracker.ui.ActivityFilterRequest
import app.moneytracker.ui.ActivityModel
import app.moneytracker.ui.ActivityState
import app.moneytracker.ui.FilterSheet
import app.moneytracker.ui.ListenSheet
import app.moneytracker.ui.LocalBackups
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.MonthPickerDialog
import app.moneytracker.ui.Screen
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberActivityModel
import app.moneytracker.ui.rememberSetting
import app.moneytracker.ui.swipeSteps
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.datetime.LocalDate

// Activity tab: every entry by day, as a calendar, by month, and the notes. Replaces the classic Trans. tab.

internal enum class ActView(val title: String) { LIST("List"), CALENDAR("Calendar"), MONTHS("Months"), NOTES("Notes") }

/** Search › "Apply filter to Activity" leaves its filter for the tab under this navigator result key. */
internal const val ACTIVITY_FILTER = "activityFilter"

internal val actMonthNames = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
internal fun YearMonth.actLongLabel() = "${tr(actMonthNames[month - 1])} $year"
/** "Wed 21 Oct", with "Today · " in front for today. */
internal fun LocalDate.actDayLabel(today: LocalDate) =
    (if (this == today) tr("Today") + " · " else "") + "${weekday()} $dayOfMonth ${monthShort(monthNumber)}"

private fun startView(s: StartScreen) = when (s) {
    StartScreen.CALENDAR -> ActView.CALENDAR
    StartScreen.MONTHLY, StartScreen.TOTAL -> ActView.MONTHS
    StartScreen.NOTE -> ActView.NOTES
    StartScreen.DAILY -> ActView.LIST
}

/** Multi-select on the list: on while [active], with the picked entries' ids. */
internal class ActSelection {
    var active by mutableStateOf(false)
    var ids by mutableStateOf(emptySet<String>())

    fun toggle(id: String) { active = true; ids = if (id in ids) ids - id else ids + id }

    fun end() { active = false; ids = emptySet() }
}

/** Which of the tab's sheets and dialogs is open. */
private enum class ActSheet { FILTER, MORE, LISTEN, MONTH }

@Composable
internal fun ActivityTab() {
    val nav = LocalNavigator.current
    val model = rememberActivityModel()
    val state by model.state.collectAsState()
    var view by rememberSaveable { mutableStateOf(startView(model.startScreen)) }
    val selection = remember { ActSelection() }
    var sheet by remember { mutableStateOf<ActSheet?>(null) }

    val filterArrived = nav.hasResult(ACTIVITY_FILTER)
    LaunchedEffect(filterArrived) {
        val h = nav.takeResult<ActivityFilterRequest>(ACTIVITY_FILTER) ?: return@LaunchedEffect
        model.apply(h); view = ActView.LIST
    }
    LaunchedEffect(view, state.period) { if (selection.active) selection.end() }
    DisposableEffect(selection.active) {
        val remove = if (!selection.active) ({}) else nav.interceptBack { selection.end(); true }
        onDispose { remove() }
    }

    Column(Modifier.fillMaxSize()) {
        ActivityHeader(state, view, selection, onSheet = { sheet = it })
        Segmented(ActView.entries.map { tr(it.title) }, view.ordinal, { view = ActView.entries[it] }, Modifier.padding(horizontal = F.gutter))
        if (state.filter.isActive && view != ActView.NOTES) {
            ActFilterBar(state.filterNames(), onEdit = { sheet = ActSheet.FILTER }, onClear = model::clearFilter)
        }
        ActivityBody(model, state, view, selection, onView = { view = it }, onPickMonth = { sheet = ActSheet.MONTH }, modifier = Modifier.weight(1f))
    }
    ActivitySheets(model, state, sheet, onSheet = { sheet = it }, onSelect = if (view == ActView.LIST) ({ sheet = null; selection.active = true }) else null)
}

@Composable
private fun ActivityHeader(state: ActivityState, view: ActView, selection: ActSelection, onSheet: (ActSheet) -> Unit) {
    val nav = LocalNavigator.current
    TabHeader(if (selection.active) tr("Select") else tr("Activity")) {
        if (selection.active) HeaderButton(tr("Done"), filled = true) { selection.end() }
        else {
            IconTile(Glyph.Search, tr("Search"), { nav.push(Screen.Search) })
            if (view == ActView.NOTES) HeaderButton(tr("Note"), filled = true, leading = Glyph.Add) { nav.push(Screen.EditNote()) }
            else IconTile(Glyph.Filter, tr("Filter by category or account"), { onSheet(ActSheet.FILTER) },
                tint = if (state.filter.isActive) F.accent else F.ink, background = if (state.filter.isActive) F.accentTint else F.card)
            // At most four icons in the header (hide amounts, search, filter, more); everything else is under More.
            IconTile(Glyph.More, tr("More"), { onSheet(ActSheet.MORE) }, badge = state.pendingCaptures)
        }
    }
}

/** The picked view under the tabs; a horizontal swipe changes the month (or year), or the view. */
@Composable
private fun ActivityBody(
    model: ActivityModel, state: ActivityState, view: ActView, selection: ActSelection,
    onView: (ActView) -> Unit, onPickMonth: () -> Unit, modifier: Modifier,
) {
    val swipe by rememberSetting(Prefs.SWIPE)
    val swipeModifier = if (swipe == Swipe.NONE || selection.active) Modifier else Modifier.swipeSteps(swipe, view, state.period) { dir ->
        if (swipe == Swipe.TAB) onView(ActView.entries[(view.ordinal + dir).coerceIn(0, ActView.entries.lastIndex)])
        else if (view != ActView.NOTES) model.stepMonths(dir * if (view == ActView.MONTHS) 12 else 1)
    }
    Box(modifier.fillMaxWidth().padding(top = 12.dp).then(swipeModifier)) {
        when (view) {
            ActView.LIST -> ActList(model, selection, onPickMonth)
            ActView.CALENDAR -> ActivityCalendarView(model, onPickMonth)
            ActView.MONTHS -> ActivityMonthsView(model, onOpenList = { model.showMonth(it); onView(ActView.LIST) }, onPickMonth = onPickMonth)
            ActView.NOTES -> ActivityNotesView()
        }
    }
}

@Composable
private fun ActivitySheets(model: ActivityModel, state: ActivityState, sheet: ActSheet?, onSheet: (ActSheet?) -> Unit, onSelect: (() -> Unit)?) {
    val nav = LocalNavigator.current
    val repo = LocalRepo.current
    val close = { onSheet(null) }
    when (sheet) {
        ActSheet.MONTH -> MonthPickerDialog(state.period, state.periods.labelFor(repo.today()), close) { model.showMonth(it); close() }
        ActSheet.FILTER -> FilterSheet(state.range, state.filter, onDismiss = close) { model.setFilter(it); close() }
        ActSheet.MORE -> MoreSheet(state.pendingCaptures, onDismiss = close, onSelect = onSelect, onVoice = { onSheet(ActSheet.LISTEN) })
        ActSheet.LISTEN -> ListenSheet(onDismiss = close, look = newListenLook()) { g -> nav.push(Screen.EditTxn(preset = g?.draft)) }
        null -> {}
    }
}

@Composable
internal fun HeaderButton(text: String, filled: Boolean, leading: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(F.iconShape).background(if (filled) F.accent else F.card).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = if (filled) F.onAccent else F.ink, modifier = Modifier.size(18.dp))
        Text(text, color = if (filled) F.onAccent else F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActFilterBar(names: List<String>, onEdit: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.padding(horizontal = F.gutter).padding(top = 10.dp).fillMaxWidth().clip(F.iconShape).background(F.accentTint)
            .clickable(onClick = onEdit).padding(start = 14.dp, end = 4.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Filter: {0}", names.joinToString(", ")), Modifier.weight(1f), color = F.accent, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val clearLabel = tr("Clear filter")
        Box(
            Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = clearLabel }.clickable(role = Role.Button, onClick = onClear),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Close, null, tint = F.accent, modifier = Modifier.size(18.dp)) }
    }
}

/** Select, and the shortcuts the classic tab had in its top bar. */
@Composable
private fun MoreSheet(pending: Int, onDismiss: () -> Unit, onSelect: (() -> Unit)?, onVoice: () -> Unit) {
    val nav = LocalNavigator.current
    val voice = LocalPlatform.current.voice
    val fileTransfer = LocalBackups.current.files
    Sheet(tr("Activity"), onDismiss) {
        if (onSelect != null) SheetOption(tr("Select entries"), subtitle = tr("Change or delete several at once"), onClick = onSelect)
        SheetOption(tr("Detected transactions"), subtitle = if (pending > 0) tr("{0} to review", pending) else null) { onDismiss(); nav.push(Screen.Inbox) }
        if (voice.speechAvailable) SheetOption(tr("Smart add"), subtitle = tr("Say or type it, e.g. “1,200 for new shoes”")) { onDismiss(); onVoice() }
        // Bookmarks are saved entries: pick one to open it filled in, then check and save.
        SheetOption(tr("Bookmarks"), subtitle = tr("Saved entries you add again and again")) { onDismiss(); nav.push(Screen.Templates) }
        SheetOption(tr("Add note")) { onDismiss(); nav.push(Screen.EditNote()) }
        SheetOption(tr("Repeat entries")) { onDismiss(); nav.push(Screen.Recurring) }
        SheetOption(tr("Export data to Excel")) { onDismiss(); fileTransfer.exportExcel() }
    }
}
