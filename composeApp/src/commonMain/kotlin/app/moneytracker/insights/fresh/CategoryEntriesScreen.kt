package app.moneytracker.insights.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.fresh.ActEntryRow
import app.moneytracker.activity.fresh.ENTRY_INSET
import app.moneytracker.activity.fresh.actDayLabel
import app.moneytracker.activity.fresh.rememberActEntryData
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.Privacy
import app.moneytracker.core.tr
import app.moneytracker.data.PeriodSettings
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.Bar
import app.moneytracker.designsystem.fresh.ChangeBadge
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.MoneyBar
import app.moneytracker.designsystem.fresh.MoneyBars
import app.moneytracker.designsystem.fresh.SubScreen
import app.moneytracker.insights.classic.StatsDetailModel
import app.moneytracker.insights.classic.StatsDetailState

/** How many months the trend shows, ending with the month the range ends in. */
private const val TREND_MONTHS = 6

/**
 * One main category over the range Insights showed (Insights › category › See all, or a budget's entries): its total
 * and change, the last six months, the split by sub-category, then every entry by day.
 */
@Composable
internal fun FreshCategoryEntries(s: Screen.StatsDetail) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val periods = remember(repo) { repo.settingsStore.periodSettingsNow() }
    val model = remember(repo, s) { StatsDetailModel(s, repo.txnStore, repo.categoryStore, repo.accountStore, periods, repo.reportStore.reportRules, scope) }
    val state by model.state.collectAsState()
    val nav = LocalNavigator.current
    val range = rangeLabel(s, periods)
    val count = state.txns.size
    SubScreen(
        s.title, nav::pop,
        subtitle = listOf(if (s.income) tr("Income") else tr("Spending"), range,
            if (count == 1) tr("1 entry") else tr("{0} entries", count)).joinToString(" · "),
    ) {
        TotalCard(s, state, range, periods)
        if (state.bySub.size > 1) SubCategoryCard(s, state)
        EntriesByDay(s, state)
    }
}

/** "Nov 2026" when the range is one whole month, otherwise "1 Nov – 15 Nov 2026". */
private fun rangeLabel(s: Screen.StatsDetail, periods: PeriodSettings): String {
    val ym = periods.labelFor(s.to)
    val month = periods.month(ym)
    return if (month.start == s.from && month.endInclusive == s.to) "${monthShort(ym.month)} ${ym.year}"
    else "${s.from.short()} – ${s.to.short()} ${s.to.year}"
}

/** The total, its change from the month before (when the range is one month), and the last six months as bars. */
@Composable
private fun TotalCard(s: Screen.StatsDetail, state: StatsDetailState, range: String, periods: PeriodSettings) {
    val currency = LocalCurrency.current
    val months = state.months.takeLast(TREND_MONTHS)
    val trend = state.trend.takeLast(TREND_MONTHS)
    val wholeMonth = periods.month(periods.labelFor(s.to)).let { it.start == s.from && it.endInclusive == s.to }
    FCard(onClick = if (Privacy.masked) ({ Privacy.peek() }) else null) {
        Text(if (s.income) tr("Earned from {0} in {1}", s.title, range) else tr("Spent on {0} in {1}", s.title, range),
            color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(currency.format(state.total), color = F.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
        if (wholeMonth && trend.size >= 2) {
            Gap(6.dp)
            ChangeBadge(state.total, trend[trend.lastIndex - 1], upIsGood = s.income, against = monthShort(months[months.lastIndex - 1].month))
        }
        Gap(16.dp)
        MoneyBars(months.mapIndexed { i, m -> MoneyBar(monthShort(m.month), trend[i]) }, currency.decimals,
            color = if (s.income) F.income else F.accent)
    }
}

/** Each sub-category's share as a thin bar, biggest first; entries without one show as "No sub-category". */
@Composable
private fun SubCategoryCard(s: Screen.StatsDetail, state: StatsDetailState) {
    val currency = LocalCurrency.current
    val total = state.total.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).padding(vertical = 6.dp)) {
        state.bySub.entries.sortedByDescending { it.value }.forEachIndexed { i, (id, amount) ->
            if (i > 0) Divider(ENTRY_INSET)
            val pct = (amount * 100 / total).toInt()
            Column(Modifier.padding(horizontal = ENTRY_INSET, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (id == s.mainCategoryId) tr("No sub-category") else state.categoryById[id]?.name.orEmpty(),
                            Modifier.weight(1f, fill = false), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (pct < 1) "<1%" else "$pct%", color = F.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(currency.format(amount), Modifier.padding(start = 12.dp), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
                }
                Bar(amount.toFloat() / total, height = 5.dp)
            }
        }
    }
}

/** Every entry in the category, newest day first, each day a card under its label and total. */
@Composable
private fun EntriesByDay(s: Screen.StatsDetail, state: StatsDetailState) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val lookups = rememberActEntryData()
    val today = LocalRepo.current.today()
    state.txns.groupBy { it.date }.entries.sortedByDescending { it.key }.forEach { (date, txns) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(date.actDayLabel(today), Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                val sum = txns.sumOf { it.amount }
                // Signed like the day totals on Home: +income, −spending.
                Text(if (s.income) "+" + currency.format(sum) else currency.format(-sum), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money)
            }
            Column(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card)) {
                txns.forEachIndexed { i, txn ->
                    if (i > 0) Divider(ENTRY_INSET)
                    ActEntryRow(txn, lookups) { nav.push(Screen.EditTxn(txnId = txn.id)) }
                }
            }
        }
    }
}
