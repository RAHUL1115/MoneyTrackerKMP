package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.ui.ActivityModel
import app.moneytracker.ui.CalendarState
import app.moneytracker.ui.EntryLookups
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberCalendarModel
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

private val weekdayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Month grid with each day's spending; the picked day's entries below it. */
@Composable
internal fun ActivityCalendarView(activity: ActivityModel, onPickMonth: () -> Unit) {
    val nav = LocalNavigator.current
    val today = LocalRepo.current.today()
    val model = rememberCalendarModel(activity)
    val period by activity.state.collectAsState()
    val cal by model.state.collectAsState()
    val range = period.range
    var picked by rememberSaveable(period.period.key) { mutableStateOf((if (today in range) today else range.start).toEpochDays()) }
    val day = LocalDate.fromEpochDays(picked)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FCard(padding = 0.dp) { Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp)) {
            // Arrows sit at the card's edges; the month and its totals in the middle.
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                ActPeriodSwitch(period.period.actLongLabel(), { activity.stepMonths(-1) }, { activity.stepMonths(1) }, onPickMonth, tile = true, minWidth = 150) {
                    OutIn(cal.totals)
                }
            }
            Gap(8.dp)
            WeekdayRow(cal.weekStart)
            Gap(6.dp)
            MonthGrid(cal, day, today) { picked = it.toEpochDays() }
        } }
        DayCard(day, today, cal.entriesOn(day), cal.lookups, onAdd = { nav.push(Screen.EditTxn(date = day)) }) { nav.push(Screen.EditTxn(txnId = it.id)) }
    }
}

@Composable
private fun OutIn(t: Totals) {
    val currency = LocalCurrency.current
    Text(buildAnnotatedString {
        append(tr("Out") + " ")
        withStyle(SpanStyle(color = F.ink, fontWeight = FontWeight.SemiBold)) { append(currency.format(t.expense)) }
        append(" · " + tr("In") + " ")
        withStyle(SpanStyle(color = F.income, fontWeight = FontWeight.SemiBold)) { append(currency.format(t.income)) }
    }, color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, style = F.money)
}

@Composable
private fun WeekdayRow(weekStart: DayOfWeek) {
    Row(Modifier.fillMaxWidth()) {
        List(7) { DayOfWeek.entries[(weekStart.ordinal + it) % 7] }.forEach { dow ->
            Text(tr(weekdayShort[dow.ordinal]), Modifier.weight(1f), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

/** The month's days in weeks from the week-start day; days outside the month stay empty. */
@Composable
private fun MonthGrid(cal: CalendarState, picked: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val range = cal.range
    val cells = generateSequence(cal.firstDay) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= range.endInclusive }.toList()
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { d ->
                    if (d !in range) Box(Modifier.weight(1f).height(46.dp))
                    else DayCell(d, cal, on = d == picked, today = today) { onPick(d) }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(d: LocalDate, cal: CalendarState, on: Boolean, today: LocalDate, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val dt = cal.dayTotals[d]
    val out = dt?.let { it.expense - it.income } ?: 0L
    val bg = when {
        on -> F.accent
        d == today -> F.accentTint
        else -> Color.Transparent
    }
    val label = if (d.dayOfMonth == 1 && cal.range.start.dayOfMonth != 1) "${d.dayOfMonth}/${d.monthNumber}" else d.dayOfMonth.toString()
    val amt = when {
        out > 0 -> compact(out, currency.decimals)
        out < 0 -> "+" + compact(-out, currency.decimals)
        (cal.dayCounts[d] ?: 0) > 0 -> "•"
        else -> ""
    }
    // TalkBack reads the date and the day's amount; the dot (entries that cancel out) is left unsaid.
    val spoken = listOf("${d.dayOfMonth} ${monthShort(d.monthNumber)}", amt).filter { it.isNotEmpty() && it != "•" }.joinToString(", ")
    Column(
        Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .selectable(selected = on, onClick = onClick).semantics { contentDescription = spoken }
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = when { on -> F.onAccent; d > today -> F.muted; else -> F.ink }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(amt, color = when { on -> F.onAccent.copy(alpha = 0.8f); out < 0 -> F.income; out > 0 -> F.expense; else -> F.muted },
            fontSize = 9.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, style = F.money)
    }
}

/** "1.2k", "850" in whole currency units. */
private fun compact(minor: Long, decimals: Int): String {
    var div = 1L
    repeat(decimals) { div *= 10 }
    val v = minor / div
    return when {
        v >= 100_000 -> "${v / 1000}k"
        v >= 1000 -> {
            val tenths = (v + 50) / 100
            if (tenths % 10 == 0L) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
        }
        else -> v.toString()
    }
}

@Composable
private fun DayCard(day: LocalDate, today: LocalDate, entries: List<Txn>, lookups: EntryLookups, onAdd: () -> Unit, onOpen: (Txn) -> Unit) {
    // The card's bottom space goes inside the last row, so that row's tap highlight reaches the card's edge.
    FListCard {
        Gap(6.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = DAY_INSET).height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = (if (day == today) tr("Today") + ", " else day.weekday() + " ") + "${day.dayOfMonth} ${tr(actMonthNames[day.monthNumber - 1])}"
            Text(label, Modifier.weight(1f), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("+ Add on this day"), Modifier.clip(F.tagShape).clickable(role = Role.Button, onClick = onAdd).padding(6.dp),
                color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (entries.isEmpty()) {
            Text(tr("Nothing recorded on this day."), Modifier.padding(horizontal = DAY_INSET).padding(top = 6.dp, bottom = 4.dp + DAY_CARD_BOTTOM),
                color = F.muted, fontSize = 13.sp)
        }
        entries.forEachIndexed { i, t ->
            Divider(DAY_INSET)
            ActEntryRow(t, lookups, compact = true, inset = DAY_INSET, extraBottom = if (i == entries.lastIndex) DAY_CARD_BOTTOM else 0.dp) { onOpen(t) }
        }
    }
}

/** Side padding of the day card's header, rows and dividers. */
private val DAY_INSET = 16.dp

/** Space between the day card's last line and its bottom edge. */
private val DAY_CARD_BOTTOM = 10.dp
