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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val yt = totals(rows)
    val todayLabel = ps.labelFor(repo.today())
    val byMonth = rows.groupBy { ps.labelFor(it.date!!).month }
    // Up to the current month, or further if future-dated rows exist.
    val lastMonth = maxOf(if (todayLabel.year == year) todayLabel.month else if (todayLabel.year > year) 12 else 1, byMonth.keys.maxOrNull() ?: 1)
    var open by rememberSaveable(year) { mutableStateOf(ym.month) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActPeriodSwitch(year.toString(), { onPick(ym.plus(-12)) }, { onPick(ym.plus(12)) }, onPickMonth, minWidth = 48)
            Text(buildAnnotatedString {
                append(tr("Out") + " ")
                withStyle(SpanStyle(color = F.expense, fontWeight = FontWeight.ExtraBold)) { append(currency.format(yt.expense)) }
                append(" · " + tr("In") + " ")
                withStyle(SpanStyle(color = F.income, fontWeight = FontWeight.ExtraBold)) { append(currency.format(yt.income)) }
            }, Modifier.weight(1f), color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, style = F.money,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
        }
        MonthTotal(ym, ps)
        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                for (m in lastMonth downTo 1) {
                    if (m != lastMonth) Divider()
                    val month = YearMonth(year, m)
                    val mt = totals(byMonth[m].orEmpty())
                    val isOpen = open == m
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(month); open = if (isOpen) 0 else m },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.width(6.dp).height(32.dp).clip(RoundedCornerShape(999.dp)).background(if (m == ym.month) F.accent else Color.Transparent))
                        Column(Modifier.weight(1f)) {
                            Text(tr(actMonthNames[m - 1]) + if (month == todayLabel) " · " + tr("now") else "", color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(if (mt.income > 0) tr("In {0}", currency.format(mt.income)) else tr("No income"),
                                color = if (mt.income > 0) F.income else F.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = F.money)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(currency.format(-(mt.expense)), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, style = F.money)
                            Text(tr("Net {0}", currency.format(mt.net)), color = F.muted, fontSize = 11.5.sp, style = F.money)
                        }
                        Icon(if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = F.muted, modifier = Modifier.size(20.dp))
                    }
                    if (isOpen) Column(Modifier.padding(start = 16.dp, bottom = 10.dp)) {
                        val range = ps.month(month)
                        Periods.weeks(range, ps.weekStart).reversed().forEach { w ->
                            val wt = totals(byMonth[m].orEmpty().filter { it.date!! in w })
                            val ahead = w.start > repo.today()
                            Divider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { onOpenList(month) },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val label = if (w.start == w.endInclusive) "${w.start.dayOfMonth} ${monthShort(w.start.monthNumber)}"
                                    else "${w.start.dayOfMonth} ${monthShort(w.start.monthNumber)} – ${w.endInclusive.dayOfMonth} ${monthShort(w.endInclusive.monthNumber)}"
                                Text(label, Modifier.weight(1f), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                if (wt.income > 0) Text("+" + currency.format(wt.income), color = F.income, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = F.money)
                                Text(
                                    if (ahead && wt.expense == 0L) tr("Upcoming") else currency.format(-(wt.expense)),
                                    color = if (ahead) F.muted else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = F.money,
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
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val prevRange = ps.month(ym.plus(-1))
    val prevRows by remember(prevRange) { repo.reportRows(prevRange) }.collectAsState(emptyList())
    val txns by remember(range) { repo.txnsBetween(range) }.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val groups by repo.groups.collectAsState(emptyList())
    val entries by remember(ym) { repo.budgetEntries(ym) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())

    val t = totals(rows)
    val groupType = groups.associate { it.id to it.type }
    val accountType = accounts.associate { it.id to groupType[it.groupId] }
    val cardExpense = txns.filter { it.kind == TxnKind.EXPENSE && accountType[it.accountId] == AccountType.CREDIT_CARD }.sumOf { it.amount }
    val otherExpense = txns.filter { it.kind == TxnKind.EXPENSE && accountType[it.accountId] != AccountType.CREDIT_CARD }.sumOf { it.amount }
    val saved = rows.filter { it.kind == TxnKind.TRANSFER_OUT && classify(it) == Flow.EXPENSE }.sumOf { it.amount }
    val prevExpense = totals(prevRows).expense
    val spentByMain = rows.filter { classify(it) == Flow.EXPENSE && it.kind == TxnKind.EXPENSE }
        .groupBy { r -> r.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } ?: "__none" }
        .mapValues { (_, v) -> v.sumOf { it.amount } }
    val budget = budgetLines(effectiveBudgets(entries, ym), spentByMain).firstOrNull { it.categoryId == TOTAL_BUDGET }

    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("TOTAL"), color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Text(ym.actLongLabel(), color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(F.page).clickable(onClickLabel = tr("Export data to Excel")) { backup.exportExcel() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Description, null, tint = F.ink, modifier = Modifier.size(16.dp))
                Text(tr("Excel"), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Gap(12.dp)
        ActSummary(t.income, t.expense, background = F.page)
        Gap(12.dp)
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
                    Modifier.weight(1f), color = F.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (budget == null) tr("Set one ›") else tr("{0}% of {1} ›", (budget.ratio * 100).toInt(), currency.format(budget.budget)),
                    color = F.accent, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                )
            }
            Bar(budget?.ratio ?: 0f, fill = if ((budget?.ratio ?: 0f) > 1f) F.danger else F.accent, height = 6.dp)
        }
        Gap(8.dp)
        SplitRow(tr("Cash & bank"), tr("Spent from accounts"), currency.format(otherExpense))
        SplitRow(tr("Cards"), tr("Spent on credit cards"), currency.format(cardExpense))
        SplitRow(tr("Saved / set aside"), tr("Moved to savings and investments"), currency.format(saved))
        Gap(8.dp)
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
            color = when (good) { true -> F.income; false -> F.expense; null -> F.muted }, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SplitRow(title: String, sub: String, value: String) {
    Divider()
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = F.muted, fontSize = 11.5.sp)
        }
        Text(value, color = F.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, style = F.money)
    }
}
