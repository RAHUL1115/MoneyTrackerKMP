package app.moneytracker.activity.fresh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.ActivityModel
import app.moneytracker.activity.rememberMonthsModel
import app.moneytracker.app.LocalBackups
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.tr
import app.moneytracker.data.MonthRollup
import app.moneytracker.data.MonthTotals
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.designsystem.fresh.Bar
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.domain.Totals
import app.moneytracker.domain.YearMonth
import kotlinx.datetime.LocalDate

/** Side padding of a month row's content and of the dividers between months. */
private val MONTH_INSET = 14.dp

/** Where a week row's content and its dashed divider start: indented under the month's name. */
private val WEEK_START = MONTH_INSET + 16.dp

/** The year's months (each opening into weeks) under a TOTAL card for the picked month. */
@Composable
internal fun ActivityMonthsView(activity: ActivityModel, onOpenList: (YearMonth) -> Unit, onPickMonth: () -> Unit) {
    val today = LocalRepo.current.today()
    val model = rememberMonthsModel(activity)
    val period by activity.state.collectAsState()
    val rollup by model.year.collectAsState()
    val month by model.month.collectAsState()
    val ym = period.period
    var open by rememberSaveable(ym.year) { mutableStateOf(ym.month) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        YearHeader(ym, rollup.totals, onStep = activity::stepMonths, onPickMonth = onPickMonth)
        MonthTotal(ym, month)
        FListCard {
            rollup.months.forEachIndexed { i, mr ->
                if (i > 0) Divider(MONTH_INSET)
                val m = mr.month.month
                MonthLine(mr, picked = m == ym.month, now = mr.month == period.periods.labelFor(today), isOpen = open == m) {
                    activity.showMonth(mr.month); open = if (open == m) 0 else m
                }
                if (open == m) Weeks(mr, today) { onOpenList(mr.month) }
            }
        }
    }
}

@Composable
private fun YearHeader(ym: YearMonth, yt: Totals, onStep: (Int) -> Unit, onPickMonth: () -> Unit) {
    val currency = LocalCurrency.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ActPeriodSwitch(ym.year.toString(), { onStep(-12) }, { onStep(12) }, onPickMonth, minWidth = 48)
        Text(buildAnnotatedString {
            append(tr("Out") + " ")
            withStyle(SpanStyle(color = F.ink, fontWeight = FontWeight.SemiBold)) { append(currency.format(yt.expense)) }
            append(" · " + tr("In") + " ")
            withStyle(SpanStyle(color = F.income, fontWeight = FontWeight.SemiBold)) { append(currency.format(yt.income)) }
        }, Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money,
            textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun MonthLine(mr: MonthRollup, picked: Boolean, now: Boolean, isOpen: Boolean, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val mt = mr.totals
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = MONTH_INSET),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(6.dp).height(32.dp).clip(RoundedCornerShape(999.dp)).background(if (picked) F.accent else Color.Transparent))
        Column(Modifier.weight(1f)) {
            Text(tr(actMonthNames[mr.month.month - 1]) + if (now) " · " + tr("now") else "", color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(if (mt.income > 0) tr("In {0}", currency.format(mt.income)) else tr("No income"),
                color = if (mt.income > 0) F.income else F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(currency.format(-(mt.expense)), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money)
            Text(tr("Net {0}", currency.format(mt.net)), color = F.muted, fontSize = 11.sp, style = F.money)
        }
        Icon(if (isOpen) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted, modifier = Modifier.size(20.dp))
    }
}

/** An open month's weeks, indented under it; the space under the last week sits inside its tap highlight. */
@Composable
private fun Weeks(mr: MonthRollup, today: LocalDate, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    Column {
        mr.weeks.forEachIndexed { i, (w, wt) ->
            val ahead = w.start > today
            DashedDivider(Modifier.padding(start = WEEK_START, end = MONTH_INSET))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = if (i == mr.weeks.lastIndex) 10.dp else 0.dp)
                    .heightIn(min = 36.dp).padding(start = WEEK_START, end = MONTH_INSET),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val label = if (w.start == w.endInclusive) "${w.start.dayOfMonth} ${monthShort(w.start.monthNumber)}"
                    else "${w.start.dayOfMonth} ${monthShort(w.start.monthNumber)} – ${w.endInclusive.dayOfMonth} ${monthShort(w.endInclusive.monthNumber)}"
                Text(label, Modifier.weight(1f), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (wt.income > 0) Text("+" + currency.format(wt.income), color = F.income, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money)
                Text(
                    if (ahead && wt.expense == 0L) tr("Upcoming") else currency.format(-(wt.expense)),
                    // Red, or blue for a week that brought in more than went out.
                    color = when { ahead -> F.muted; wt.income > wt.expense -> F.income; else -> F.expense },
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money,
                )
            }
        }
    }
}

/** The classic Total view in one card: in / out / net, budget, where the money went, and the change on last month. */
@Composable
private fun MonthTotal(ym: YearMonth, m: MonthTotals) {
    val nav = LocalNavigator.current
    val fileTransfer = LocalBackups.current.files
    val currency = LocalCurrency.current
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("TOTAL"), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(ym.actLongLabel(), color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.height(36.dp).clip(F.controlShape).background(F.page)
                    .clickable(onClickLabel = tr("Export data to Excel"), role = Role.Button) { fileTransfer.exportExcel() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Glyph.Note, null, tint = F.ink, modifier = Modifier.size(16.dp))
                Text(tr("Excel"), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Gap(16.dp)
        ActSummary(m.totals.income, m.totals.expense, background = F.page)
        Gap(16.dp)
        BudgetLine(m.budget.total) { if (m.budget.total != null) nav.push(Screen.BudgetDetail(ym.key)) else nav.push(Screen.Budget) }
        Gap(16.dp)
        SplitRow(tr("Cash & bank"), tr("Spent from accounts"), currency.format(m.otherExpense))
        SplitRow(tr("Cards"), tr("Spent on credit cards"), currency.format(m.cardExpense))
        SplitRow(tr("Saved / set aside"), tr("Moved to savings and investments"), currency.format(m.saved))
        Gap(16.dp)
        LastMonthLine(ym, m.totals.expense, m.previousExpense)
    }
}

@Composable
private fun BudgetLine(budget: app.moneytracker.domain.BudgetLine?, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            Text(
                if (budget == null) tr("No budget for this month")
                else if (budget.remaining >= 0) tr("Budget · {0} left", currency.format(budget.remaining))
                else tr("Budget · {0} over", currency.format(-budget.remaining)),
                Modifier.weight(1f), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                if (budget == null) tr("Set one ›") else tr("{0}% of {1} ›", (budget.ratio * 100).toInt(), currency.format(budget.budget)),
                color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
        Bar(budget?.ratio ?: 0f, fill = if ((budget?.ratio ?: 0f) > 1f) F.warn else F.accent, height = 6.dp)
    }
}

/** "▼ 1,200 less than September (8%)": this month's spending against last month's. */
@Composable
private fun LastMonthLine(ym: YearMonth, expense: Long, prevExpense: Long) {
    val currency = LocalCurrency.current
    val diff = expense - prevExpense
    val prevName = tr(actMonthNames[ym.plus(-1).month - 1])
    val (text, good) = when {
        prevExpense == 0L -> tr("Nothing was spent in {0}", prevName) to null
        diff <= 0 -> tr("▼ {0} less than {1} ({2}%)", currency.format(-diff), prevName, (-diff * 100 / prevExpense)) to true
        else -> tr("▲ {0} more than {1} ({2}%)", currency.format(diff), prevName, (diff * 100 / prevExpense)) to false
    }
    Text(
        text, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(when (good) { true -> F.income.copy(alpha = 0.12f); false -> F.expense.copy(alpha = 0.12f); null -> F.page })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        color = when (good) { true -> F.income; false -> F.expense; null -> F.muted }, fontSize = 12.sp, fontWeight = FontWeight.Medium,
    )
}

/** Hairline between weeks, dashed so it reads lighter than the month dividers. */
@Composable
private fun DashedDivider(modifier: Modifier) {
    val color = F.border
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    }
}

@Composable
private fun SplitRow(title: String, sub: String, value: String) {
    Divider()
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = F.muted, fontSize = 11.sp)
        }
        Text(value, color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money)
    }
}
