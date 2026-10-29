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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberFilteredRows
import app.moneytracker.ui.rememberFilteredTxns
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

private val weekdayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Month grid with each day's spending; the picked day's entries below it. */
@Composable
internal fun ActivityCalendarView(
    ym: YearMonth,
    range: DateRange,
    weekStart: DayOfWeek,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickMonth: () -> Unit,
) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val today = repo.today()
    val rows by rememberFilteredRows(range)
    val t = totals(rows)
    val byDay = rows.groupBy { it.date }.mapValues { totals(it.value) }
    val counts = rows.groupingBy { it.date }.eachCount()
    var picked by rememberSaveable(ym.key) { mutableStateOf((if (today in range) today else range.start).toEpochDays()) }
    val day = LocalDate.fromEpochDays(picked)

    var first = range.start
    while (first.dayOfWeek != weekStart) first = first.minus(DatePeriod(days = 1))
    val cells = generateSequence(first) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= range.endInclusive }.toList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FCard(padding = 16.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    ActPeriodSwitch(ym.actLongLabel(), onPrev, onNext, onPickMonth, tile = true, minWidth = 150)
                    Text(buildAnnotatedString {
                        append(tr("Out") + " ")
                        withStyle(SpanStyle(color = F.expense, fontWeight = FontWeight.ExtraBold)) { append(currency.format(t.expense)) }
                        append(" · " + tr("In") + " ")
                        withStyle(SpanStyle(color = F.income, fontWeight = FontWeight.ExtraBold)) { append(currency.format(t.income)) }
                    }, color = F.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, style = F.money)
                }
            }
            Gap(8.dp)
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).map { DayOfWeek.entries[(weekStart.ordinal + it) % 7] }.forEach { dow ->
                    Text(tr(weekdayShort[dow.ordinal]), Modifier.weight(1f), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            Gap(6.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { d ->
                            if (d !in range) Box(Modifier.weight(1f).height(46.dp))
                            else {
                                val on = d == day
                                val dt = byDay[d]
                                val out = dt?.let { it.expense - it.income } ?: 0L
                                val bg = when { on -> F.accent; d == today -> F.accentTint; else -> Color.Transparent }
                                Column(
                                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                                        .clickable(onClickLabel = "${d.dayOfMonth} ${monthShort(d.monthNumber)}") { picked = d.toEpochDays() }
                                        .padding(top = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    val label = if (d.dayOfMonth == 1 && range.start.dayOfMonth != 1) "${d.dayOfMonth}/${d.monthNumber}" else d.dayOfMonth.toString()
                                    Text(label, color = when { on -> F.onAccent; d > today -> F.muted; else -> F.ink }, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    val amt = when {
                                        out > 0 -> compact(out, currency.decimals)
                                        out < 0 -> "+" + compact(-out, currency.decimals)
                                        (counts[d] ?: 0) > 0 -> "•"
                                        else -> ""
                                    }
                                    Text(amt, color = when { on -> F.onAccent.copy(alpha = 0.8f); out < 0 -> F.income; out > 0 -> F.expense; else -> F.muted },
                                        fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, style = F.money)
                                }
                            }
                        }
                        repeat(7 - week.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        DayCard(day, today, onAdd = { nav.push(Screen.EditTxn(date = day)) })
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
private fun DayCard(day: LocalDate, today: LocalDate, onAdd: () -> Unit) {
    val nav = LocalNavigator.current
    val txns by rememberFilteredTxns(DateRange(day, day))
    val data = rememberActEntryData()
    val visible = txns.filter { it.kind != TxnKind.TRANSFER_IN }
    FCard(padding = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = (if (day == today) tr("Today") + ", " else day.weekday() + " ") + "${day.dayOfMonth} ${tr(actMonthNames[day.monthNumber - 1])}"
                Text(label, Modifier.weight(1f), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(tr("+ Add on this day"), Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAdd).padding(6.dp),
                    color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (visible.isEmpty()) Text(tr("Nothing recorded on this day."), Modifier.padding(top = 6.dp, bottom = 4.dp), color = F.muted, fontSize = 13.sp)
            visible.forEach { t ->
                Divider()
                ActEntryRow(t, data, compact = true) { nav.push(Screen.EditTxn(txnId = t.id)) }
            }
        }
    }
}
