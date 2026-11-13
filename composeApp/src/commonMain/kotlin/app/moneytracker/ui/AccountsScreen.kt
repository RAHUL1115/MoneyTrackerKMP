package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.AccountSummary
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.accounts.NetWorthPoint
import app.moneytracker.ui.accounts.billDue
import app.moneytracker.ui.accounts.mainTotal
import app.moneytracker.ui.accounts.rememberAccountsModel
import app.moneytracker.ui.accounts.shownBalance

/** Accounts + balances + card statements (one read model for both layouts); the last one shows at once. */
@Composable
internal fun rememberAccountSummary(): AccountSummary? {
    val repo = LocalRepo.current
    val flow = remember(repo) { repo.views.accounts }
    return flow.collectAsState(repo.views.latestAccounts).value
}

/** Classic layout: money in the income colour, below zero in the expense colour. */
internal fun accountAmountColor(v: Long) = if (v < 0) ExpenseColor else IncomeColor

@Composable
fun AccountsTab() {
    val model = rememberAccountsModel()
    val summary by model.summary.collectAsState()
    AccountsTabContent(summary)
}

@Composable
private fun AccountsTabContent(summary: AccountSummary?) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxSize()) {
        TitleTopBar(tr("Accounts")) {
            PrivacyEyeButton()
            IconButton(onClick = { nav.push(Screen.AccountsTrend) }) { Icon(Icons.Outlined.BarChart, tr("Total stats")) }
            IconButton(onClick = { nav.push(Screen.AccountList) }) { Icon(Icons.Outlined.Settings, tr("Manage accounts")) }
            IconButton(onClick = { nav.push(Screen.EditAccount()) }) { Icon(Icons.Outlined.Add, tr("Add account")) }
        }
        HorizontalDivider(color = DividerColor)
        val (lines, worth) = summary ?: return@Column
        SummaryStrip(
            listOf(Triple(tr("Assets"), worth.assets, IncomeColor), Triple(tr("Liabilities"), worth.liabilities, ExpenseColor), Triple(tr("Total"), worth.net, InkSoft)),
            currency,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            // Every group keeps its header and total; hidden accounts only lose their own row.
            lines.groupBy { it.group }.entries.sortedBy { it.key.sortOrder }.forEach { (group, groupLines) ->
                item(key = "g${group.id}") { GroupCard(group, groupLines) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GroupCard(group: AccountGroup, groupLines: List<AccountLine>) {
    val nav = LocalNavigator.current
    Column(Modifier.padding(top = 10.dp).card()) {
        if (group.type.isCreditCard) CardGroupHeader(group, groupLines) else GroupHeader(group, groupLines)
        groupLines.filter { !it.account.hidden }.forEach { line ->
            AccountRow(line) { nav.push(Screen.AccountDetail(line.account.id)) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Accounts with "Include in totals" off still show below, but don't add to the group's total. */
@Composable
private fun CardGroupHeader(group: AccountGroup, groupLines: List<AccountLine>) {
    val currency = LocalCurrency.current
    val counted = groupLines.filter { it.account.includeInTotals }
    val payable = counted.sumOf { it.billDue }
    // A card in credit offsets the others' outstanding.
    val outstanding = counted.sumOf { it.card?.outstanding ?: 0 }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        GroupName(group.name, Modifier.weight(1f))
        CardColumn(tr("Balance payable"), currency.format(payable))
        CardColumn(tr("Outst. balance"), currency.format(kotlin.math.abs(outstanding)), if (outstanding < 0) IncomeColor else Ink)
    }
}

/** In the main currency: foreign-currency accounts are converted at their rate. */
@Composable
private fun GroupHeader(group: AccountGroup, groupLines: List<AccountLine>) {
    val currency = LocalCurrency.current
    val sum = groupLines.mainTotal(LocalCurrencies.current, currency.decimals) { it.shownBalance }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GroupName(group.name, Modifier.weight(1f))
        Text(currency.format(sum), color = accountAmountColor(sum), style = SectionTotalStyle, maxLines = 1)
    }
}

@Composable
private fun AccountRow(line: AccountLine, onClick: () -> Unit) {
    val fmt = line.account.currency?.let { LocalCurrencies.current[it]?.format } ?: LocalCurrency.current
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.account.name + (line.account.currency?.let { "  ($it)" } ?: ""), color = Ink)
            if (!line.account.includeInTotals) Text(tr("Not in totals"), fontSize = 12.sp, color = SubtleText)
            CardLimit(line, fmt)
        }
        val card = line.card
        if (card != null) {
            Text(fmt.format(card.statementDue + card.overdue), Modifier.width(120.dp), textAlign = TextAlign.End,
                color = if (card.overdue > 0) ExpenseColor else Ink, style = RowAmountStyle)
            // In credit: the amount shows unsigned in the income colour.
            Text(fmt.format(kotlin.math.abs(card.outstanding)), Modifier.width(120.dp), textAlign = TextAlign.End,
                color = if (card.outstanding < 0) IncomeColor else Ink, style = RowAmountStyle)
        } else {
            val shown = line.shownBalance
            Text(fmt.format(shown), color = accountAmountColor(shown), style = RowAmountStyle)
        }
    }
}

/** A card with a monthly limit: how much of it this cycle has used. */
@Composable
private fun CardLimit(line: AccountLine, fmt: CurrencyFormat) {
    val limit = line.account.cardLimit
    val c = line.card
    if (limit == null || limit <= 0 || c == null) return
    val ratio = (c.cycleSpend.toFloat() / limit).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { ratio }, Modifier.padding(top = 6.dp, end = 12.dp).fillMaxWidth().height(4.dp),
        color = if (c.cycleSpend > limit) ExpenseColor else if (ratio > 0.8f) Color(0xFFF7A35C) else IncomeColor,
        trackColor = PageBackground,
    )
    Text(tr("{0} of {1} limit", fmt.format(c.cycleSpend), fmt.format(limit)), fontSize = 11.sp,
        color = if (c.cycleSpend > limit) ExpenseColor else SubtleText)
}

@Composable
private fun GroupName(name: String, modifier: Modifier) =
    Text(name, modifier, color = SubtleText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun CardColumn(label: String, value: String, color: Color = Ink) {
    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.End) {
        Text(label, fontSize = 11.sp, color = SubtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Fixed 120 dp column (it lines up with the amounts below), so a long figure steps its size down instead of clipping.
        FitAmount(value, SectionTotalStyle, color, textAlign = TextAlign.End, min = 12.sp)
    }
}

@Composable
fun SmallOutlineButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.minimumInteractiveComponentSize().border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        fontSize = 13.sp,
    )
}

/** Classic label + switch row; the whole row is the switch for taps and TalkBack. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        AppSwitch(checked, null)
    }
}

/** Assets, liabilities and net worth at the end of each of the last 12 months (main currency). */
@Composable
fun AccountsTrendScreen() {
    val model = rememberAccountsModel()
    val points by remember(model) { model.netWorthTrend(12) }.collectAsState(emptyList())
    AccountsTrendContent(points)
}

@Composable
private fun AccountsTrendContent(points: List<NetWorthPoint>) {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Total stats"), nav::pop)
        HorizontalDivider(color = DividerColor)
        if (points.isEmpty()) return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(top = 10.dp).card()) {
                    Text(tr("Net worth"), Modifier.padding(start = 16.dp, top = 12.dp), color = SubtleText, fontSize = 12.sp)
                    val labels = points.map { monthShort(it.month.month) }
                    LineChart(points.map { it.net }, labels)
                    Text(tr("Assets / Liabilities"), Modifier.padding(start = 16.dp, top = 4.dp), color = SubtleText, fontSize = 12.sp)
                    PairBarChart(points.map { it.assets }, points.map { it.liabilities }, labels)
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Column(Modifier.padding(top = 10.dp).card()) { points.asReversed().forEach { TrendRow(it) } }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TrendRow(point: NetWorthPoint) {
    val currency = LocalCurrency.current
    val (month, assets, liabilities) = point
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(monthShort(month.month), Modifier.width(48.dp), color = Ink, fontWeight = FontWeight.Medium)
        Text(currency.format(assets), Modifier.weight(1f), color = IncomeColor, fontSize = 13.sp, textAlign = TextAlign.End)
        Text(currency.format(liabilities), Modifier.weight(1f), color = ExpenseColor, fontSize = 13.sp, textAlign = TextAlign.End)
        Text(currency.format(assets - liabilities), Modifier.weight(1.1f), color = Ink, fontSize = 13.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = DividerColor)
}
