package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.domain.YearMonth
import app.moneytracker.ui.ActKind
import app.moneytracker.ui.ActivityModel
import app.moneytracker.ui.ActivityState
import app.moneytracker.ui.EntryListState
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.UpcomingRepeats
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberEntryListModel
import app.moneytracker.ui.tr
import kotlinx.datetime.LocalDate

/** The List view: the month's entries by day, with the month switch, quick filter, totals and repeats to come. */
@Composable
internal fun ActList(activity: ActivityModel, selection: ActSelection, onPickMonth: () -> Unit) {
    val nav = LocalNavigator.current
    val today = LocalRepo.current.today()
    val model = rememberEntryListModel(activity)
    val period by activity.state.collectAsState()
    val list by model.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        ActListContent(
            period, list, today, selection,
            onKind = activity::setKind, onStep = activity::stepMonths, onPickMonth = onPickMonth,
            onOpen = { txn -> if (selection.active) selection.toggle(txn.id) else nav.push(Screen.EditTxn(txnId = txn.id)) },
        )
        if (selection.active) SelectionPanel(list.entries.filter { it.id in selection.ids }, selection::end, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ActListContent(
    period: ActivityState,
    list: EntryListState,
    today: LocalDate,
    selection: ActSelection,
    onKind: (ActKind) -> Unit,
    onStep: (Int) -> Unit,
    onPickMonth: () -> Unit,
    onOpen: (Txn) -> Unit,
) {
    val nav = LocalNavigator.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = F.gutter, end = F.gutter, bottom = if (selection.active) 140.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "period") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActPeriodSwitch(period.period.actLongLabel(), { onStep(-1) }, { onStep(1) }, onPickMonth)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    ActKind.entries.forEach { k -> ActChip(tr(k.title), k == period.kind) { onKind(k) } }
                }
            }
        }
        item(key = "summary") { ListSummary(list.totals, list.carryOver) }
        list.upcoming?.let { u -> item(key = "repeats") { UpcomingRepeatsCard(u) { nav.push(Screen.Recurring) } } }
        if (list.visible.isEmpty()) item(key = "empty") { EmptyMonth(period.kind, period.period) }
        days(list, today, selection, onOpen)
    }
}

private fun LazyListScope.days(list: EntryListState, today: LocalDate, selection: ActSelection, onOpen: (Txn) -> Unit) {
    list.days.forEach { (date, dayTxns) ->
        item(key = "d$date") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DayLine(date.actDayLabel(today), list.dayTotals[date])
                Column(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card)) {
                    dayTxns.forEachIndexed { i, txn ->
                        if (i > 0) Divider(ENTRY_INSET)
                        ActEntryRow(
                            txn, list.lookups, selecting = selection.active, selected = txn.id in selection.ids,
                            onLongClick = { selection.toggle(txn.id) },
                        ) { onOpen(txn) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListSummary(t: Totals, carry: Long?) {
    val currency = LocalCurrency.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ActSummary(t.income, t.expense)
        if (carry != null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(tr("Carried over {0}", currency.format(carry)), Modifier.weight(1f), color = F.muted, fontSize = 12.sp, style = F.money)
            Text(tr("Balance {0}", currency.format(carry + t.net)), color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money)
        }
    }
}

@Composable
private fun EmptyMonth(kind: ActKind, ym: YearMonth) {
    FCard(padding = 24.dp) {
        Text(
            if (kind == ActKind.MOVED) tr("No transfers in {0}", tr(actMonthNames[ym.month - 1])) else tr("Nothing recorded in {0}", tr(actMonthNames[ym.month - 1])),
            Modifier.fillMaxWidth(), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Text(tr("Change the filter or add an entry with +."), Modifier.fillMaxWidth().padding(top = 6.dp), color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** A day's label with what came in and went out that day. */
@Composable
private fun DayLine(label: String, dt: Totals?) {
    val currency = LocalCurrency.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(label, Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        if (dt != null) Text(
            listOfNotNull(
                if (dt.income > 0) "+" + currency.format(dt.income) else null,
                if (dt.expense > 0) currency.format(-(dt.expense)) else null,
            ).joinToString(" · "),
            color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money,
        )
    }
}

/** "Repeat · Rent, To mom · 1 Nov · −30,000": repeating entries still to come this period (not in the totals). */
@Composable
private fun UpcomingRepeatsCard(u: UpcomingRepeats, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    FCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mark(tr("Repeat"), 36.dp, icon = Glyph.Repeat)
            Column(Modifier.weight(1f)) {
                Text(tr("Coming up · {0}", "${u.first.dayOfMonth} ${monthShort(u.first.monthNumber)}"), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(u.names.joinToString(", "), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(currency.format(u.sum, showSign = true), color = if (u.sum >= 0) F.income else F.expense, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money)
        }
    }
}
