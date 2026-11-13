package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Category
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One main category over a period: its entries, the split by sub-category, and the last 8 months' totals. */
data class StatsDetailState(
    val txns: List<Txn>,
    /** Total per sub-category id; entries without a sub-category count under the main category's id. */
    val bySub: Map<String, Long>,
    val months: List<YearMonth>,
    /** The category's total in each of [months]. */
    val trend: List<Long>,
    val categoryById: Map<String, Category>,
    val accountNames: Map<String, String>,
) {
    val total: Long get() = bySub.values.sum()
}

/** The drill-down behind a Stats slice, for the main category in [screen] and the period it names. */
class StatsDetailModel(
    private val screen: Screen.StatsDetail,
    txns: TxnStore,
    categories: CategoryStore,
    accounts: AccountStore,
    periods: PeriodSettings,
    scope: CoroutineScope,
) {
    private val kind = if (screen.income) TxnKind.INCOME else TxnKind.EXPENSE
    private val months = (7 downTo 0).map { periods.labelFor(screen.to).plus(-it) }
    private val monthRanges = months.map { periods.month(it) }
    private val shown = txns.txnsBetween(DateRange(screen.from, screen.to))
    private val trendTxns = txns.txnsBetween(DateRange(monthRanges.first().start, monthRanges.last().endInclusive))

    val state: StateFlow<StatsDetailState> = combine(shown, trendTxns, categories.categoryById, accounts.accountNames, ::stateOf)
        .stateIn(scope, SharingStarted.Eagerly, stateOf(shown.current, trendTxns.current, categories.categoryById.current, accounts.accountNames.current))

    private fun stateOf(list: List<Txn>, trendList: List<Txn>, catById: Map<String, Category>, names: Map<String, String>): StatsDetailState {
        fun inCategory(t: Txn) = t.kind == kind && t.categoryId?.let { catById[it] }?.let { (it.parentId ?: it.id) == screen.mainCategoryId } == true
        val inCat = list.filter(::inCategory)
        val bySub = inCat.groupBy { t -> catById[t.categoryId]?.takeIf { it.parentId != null }?.id ?: screen.mainCategoryId }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val trend = monthRanges.map { r -> trendList.filter { t -> inCategory(t) && t.date >= r.start && t.date <= r.endInclusive }.sumOf { it.amount } }
        return StatsDetailState(inCat, bySub, months, trend, catById, names)
    }
}

@Composable
fun StatsDetailScreen(s: Screen.StatsDetail) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val model = remember(repo, s) {
        StatsDetailModel(s, repo.txnStore, repo.categoryStore, repo.accountStore, repo.settingsStore.periodSettingsNow(), scope)
    }
    val state by model.state.collectAsState()
    StatsDetailContent(s, state)
}

@Composable
fun StatsDetailContent(s: Screen.StatsDetail, state: StatsDetailState) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val total = state.total
    Column(Modifier.fillMaxSize()) {
        BackTopBar(s.title, nav::pop)
        HorizontalDivider(color = DividerColor)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text("${s.from.short()} – ${s.to.short()} ${s.to.year} · ${currency.format(total)}", Modifier.padding(20.dp), color = SubtleText)
                if (total > 0 && state.bySub.size > 1) {
                    Box(Modifier.fillMaxWidth().background(SurfaceColor).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        DonutChart(state.bySub.values.sortedDescending(), Modifier.size(180.dp))
                    }
                }
            }
            item {
                Column(Modifier.padding(vertical = 8.dp).card()) {
                    Text(tr("Last 8 months"), Modifier.padding(start = 16.dp, top = 12.dp), color = SubtleText, fontSize = 12.sp)
                    LineChart(state.trend, state.months.map { monthShort(it.month) }, color = if (s.income) IncomeColor else ExpenseColor)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(state.bySub.entries.sortedByDescending { it.value }.withIndex().toList(), key = { "s" + it.value.key }) { (i, e) ->
                Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PercentBadge(((e.value * 100.0) / total).toInt().coerceAtLeast(1), ChartColors[i % ChartColors.size])
                    Text(if (e.key == s.mainCategoryId) tr("(no sub-category)") else state.categoryById[e.key]?.name.orEmpty(), Modifier.weight(1f).padding(start = 12.dp))
                    Text(currency.format(e.value))
                }
                HorizontalDivider(color = DividerColor)
            }
            item { GreyHeader(tr("Transactions")) }
            items(state.txns, key = { it.id }) { t ->
                TxnLine(t, state.accountNames, state.categoryById, if (s.income) Flow.INCOME else Flow.EXPENSE) { nav.push(Screen.EditTxn(txnId = t.id)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
