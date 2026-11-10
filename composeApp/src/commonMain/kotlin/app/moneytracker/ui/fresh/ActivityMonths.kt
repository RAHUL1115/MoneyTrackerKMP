package app.moneytracker.ui.fresh

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
import app.moneytracker.ui.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.PeriodSettings
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Periods
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.domain.totals
import app.moneytracker.ui.LocalBackup
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberFilteredRows
import app.moneytracker.ui.tr

/** The year's months (each opening into weeks) under a TOTAL card for the picked month. */
@Composable
internal fun ActivityMonthsView(
    ym: YearMonth,
    ps: PeriodSettings,
    onPick: (YearMonth) -> Unit,
    onOpenList: (YearMonth) -> Unit,
    onPickMonth: () -> Unit,
) {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val year = ym.year
    val rows by rememberFilteredRows(ps.year(year))
    // Same year → month → week rollup as the classic Monthly view.
    val rollup = remember(rows, ps, year) { app.moneytracker.data.ReadModels.yearRollup(rows, ps, year, repo.today()) }
    val yt = rollup.totals
    val todayLabel = ps.labelFor(repo.today())
    var open by rememberSaveable(year) { mutableStateOf(ym.month) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActPeriodSwitch(year.toString(), { onPick(ym.plus(-12)) }, { onPick(ym.plus(12)) }, onPickMonth, minWidth = 48)
            Text(buildAnnotatedString {
                append(tr("Out") + " ")
                withStyle(SpanStyle(color = F.ink, fontWeight = FontWeight.SemiBold)) { append(currency.format(yt.expense)) }
                append(" · " + tr("In") + " ")
                withStyle(SpanStyle(color = F.income, fontWeight = FontWeight.SemiBold)) { append(currency.format(yt.income)) }
            }, Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
        }
        MonthTotal(ym, ps)
        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                rollup.months.forEachIndexed { i, mr ->
                    if (i > 0) Divider()
                    val month = mr.month
                    val m = month.month
                    val mt = mr.totals
                    val isOpen = open == m
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(month); open = if (isOpen) 0 else m },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.width(6.dp).height(32.dp).clip(RoundedCornerShape(999.dp)).background(if (m == ym.month) F.accent else Color.Transparent))
                        Column(Modifier.weight(1f)) {
                            Text(tr(actMonthNames[m - 1]) + if (month == todayLabel) " · " + tr("now") else "", color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(if (mt.income > 0) tr("In {0}", currency.format(mt.income)) else tr("No income"),
                                color = if (mt.income > 0) F.income else F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(currency.format(-(mt.expense)), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money)
                            Text(tr("Net {0}", currency.format(mt.net)), color = F.muted, fontSize = 11.sp, style = F.money)
                        }
                        Icon(if (isOpen) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted, modifier = Modifier.size(20.dp))
                    }
                    if (isOpen) Column(Modifier.padding(start = 16.dp, bottom = 10.dp)) {
                        mr.weeks.forEach { (w, wt) ->
                            val ahead = w.start > repo.today()
                            DashedDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { onOpenList(month) },
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
            }
        }
    }
}

/** The classic Total view in one card: in / out / net, budget, where the money went, and the change on last month. */
@Composable
private fun MonthTotal(ym: YearMonth, ps: PeriodSettings) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val backup = LocalBackup.current
    val currency = LocalCurrency.current
    val range = ps.month(ym)
    // One read model for the Total card (same numbers as the classic Total view).
    val m by remember(ym, range) { repo.views.month(ym) }.collectAsState()
    val t = m.totals
    val cardExpense = m.cardExpense
    val otherExpense = m.otherExpense
    val saved = m.saved
    val prevExpense = m.previousExpense
    val budget = m.budget.total

    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("TOTAL"), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(ym.actLongLabel(), color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(F.page).clickable(onClickLabel = tr("Export data to Excel")) { backup.exportExcel() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Glyph.Note, null, tint = F.ink, modifier = Modifier.size(16.dp))
                Text(tr("Excel"), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Gap(16.dp)
        ActSummary(t.income, t.expense, background = F.page)
        Gap(16.dp)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { if (budget != null) nav.push(Screen.BudgetDetail(ym.key)) else nav.push(Screen.Budget) }.padding(vertical = 4.dp),
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
        Gap(16.dp)
        SplitRow(tr("Cash & bank"), tr("Spent from accounts"), currency.format(otherExpense))
        SplitRow(tr("Cards"), tr("Spent on credit cards"), currency.format(cardExpense))
        SplitRow(tr("Saved / set aside"), tr("Moved to savings and investments"), currency.format(saved))
        Gap(16.dp)
        val diff = t.expense - prevExpense
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
}

/** Hairline between weeks, dashed so it reads lighter than the month dividers. */
@Composable
private fun DashedDivider() {
    val color = F.border
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
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
