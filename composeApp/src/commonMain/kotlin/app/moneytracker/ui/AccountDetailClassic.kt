package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.app.Screen
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DayHeader
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.EmptyState
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.LineChart
import app.moneytracker.designsystem.classic.ListCardMargin
import app.moneytracker.designsystem.classic.ListRowInset
import app.moneytracker.designsystem.classic.MonthPickerDialog
import app.moneytracker.designsystem.classic.PairBarChart
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SummaryStrip
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.UnderlineTabs
import app.moneytracker.designsystem.classic.card
import app.moneytracker.designsystem.classic.dotted
import app.moneytracker.designsystem.classic.label
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.Category
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.cardUsage
import app.moneytracker.ui.accounts.AccountHistory
import app.moneytracker.ui.accounts.PeriodSum
import app.moneytracker.ui.accounts.billDue
import app.moneytracker.ui.accounts.depositTotal
import app.moneytracker.ui.accounts.parseBalance
import app.moneytracker.ui.accounts.periodSums
import app.moneytracker.ui.accounts.rememberAccountActionsModel
import app.moneytracker.ui.accounts.rememberAccountDetailModel
import app.moneytracker.ui.accounts.withdrawalTotal
import kotlinx.datetime.LocalDate

/** Names to show an account's entries with. */
private class AccountEntryNames(val accounts: Map<String, String>, val categories: Map<String, Category>)

@Composable
fun AccountDetailScreen(accountId: String) {
    val today = LocalRepo.current.today()
    val model = rememberAccountDetailModel(accountId)
    val actions = rememberAccountActionsModel(accountId)
    val summary by model.summary.collectAsState()
    val startDay by model.monthStartDay.collectAsState()
    val accountNames by model.accountNames.collectAsState()
    val catById by model.categoryById.collectAsState()
    var periodKey by rememberPeriod()
    val ym = YearMonth.fromKey(periodKey)
    val range = Periods.month(ym, startDay)
    val history by remember(model, range) { model.history(range) }.collectAsState(AccountHistory.Empty)
    val line = model.lineIn(summary)
    val currency = line?.account?.currency?.let { LocalCurrencies.current[it]?.format } ?: LocalCurrency.current
    Box(Modifier.fillMaxSize()) {
        DetailBody(
            accountId, line, history, ym, range, startDay, today, AccountEntryNames(accountNames, catById), currency,
            onPeriod = { periodKey = it.key }, onAdjust = { target, record -> actions.adjustBalance(target, record) },
        )
        DetailFabs(accountId, line, today)
    }
}

@Composable
private fun DetailBody(
    accountId: String, line: AccountLine?, history: AccountHistory, ym: YearMonth, range: DateRange, startDay: Int, today: LocalDate,
    names: AccountEntryNames, currency: CurrencyFormat, onPeriod: (YearMonth) -> Unit, onAdjust: (target: Long, asIncomeExpense: Boolean) -> Unit,
) {
    var adjusting by remember { mutableStateOf(false) }
    var monthPicker by remember { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(0) }          // 0 Daily, 1 Monthly, 2 Annually
    var showChart by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(line?.account?.name ?: tr("Account"), ym, showChart, { showChart = !showChart }, onPeriod) { monthPicker = true }
        HorizontalDivider(color = DividerColor)
        StatementHeader(accountId, line, history, range, today, currency) { adjusting = true }
        val entries = history.entries
        val deposit = entries.depositTotal()
        val withdrawal = entries.withdrawalTotal()
        val balance = line?.balance ?: 0
        SummaryStrip(
            listOf(
                Triple(tr("Deposit"), deposit, IncomeColor), Triple(tr("Withdrawal"), withdrawal, ExpenseColor),
                Triple(tr("Total"), deposit - withdrawal, InkSoft), Triple(tr("Balance"), balance, accountAmountColor(balance)),
            ),
            currency,
        )
        if (monthPicker) MonthPickerDialog(ym, YearMonth.of(today), { monthPicker = false }) { onPeriod(it); monthPicker = false }
        UnderlineTabs(listOf(tr("Daily"), tr("Monthly"), tr("Annually")), view) { view = it }
        // Periods for the Monthly (months of the shown year) and Annually (every year with data) views.
        val summaries = periodSums(history.history, ym, view == 2, startDay, today)
        if (showChart) BalanceCharts(summaries)
        when {
            view != 0 -> PeriodList(summaries, currency) { p -> if (view == 1 && p.month != null) { onPeriod(p.month); view = 0 } }
            entries.isEmpty() -> EmptyState()
            else -> DayList(history, names, currency)
        }
    }
    if (adjusting && line != null) AdjustDialogs(line, currency, onDismiss = { adjusting = false }, onAdjust = onAdjust)
}

@Composable
private fun DetailTopBar(title: String, ym: YearMonth, showChart: Boolean, onToggleChart: () -> Unit, onPeriod: (YearMonth) -> Unit, onMonthPicker: () -> Unit) {
    val nav = LocalNavigator.current
    BackTopBar(title, nav::pop) {
        IconButton(onClick = onToggleChart) {
            Icon(Icons.Outlined.BarChart, tr("Charts"), tint = if (showChart) Accent else LocalContentColor.current)
        }
        IconButton(onClick = { onPeriod(ym.plus(-1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous")) }
        Text(ym.label(), Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onMonthPicker).padding(horizontal = 4.dp, vertical = 6.dp))
        IconButton(onClick = { onPeriod(ym.plus(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next")) }
    }
}

@Composable
private fun StatementHeader(accountId: String, line: AccountLine?, history: AccountHistory, range: DateRange, today: LocalDate, currency: CurrencyFormat, onAdjust: () -> Unit) {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 28.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Statement"), color = SubtleText, fontSize = 13.sp)
                Text(range.dotted(), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
            IconButton(onClick = { nav.push(Screen.EditAccount(accountId)) }) { Icon(Icons.Outlined.Edit, tr("Edit account")) }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallOutlineButton(tr("Adjust balance"), onAdjust)
        }
        val acct = line?.account
        if (acct != null && acct.memo.isNotBlank()) Text(acct.memo, Modifier.padding(top = 8.dp), color = SubtleText, fontSize = 13.sp)
        if (line != null) UsageTarget(line, history, today, currency)
    }
}

/** A card with a monthly usage target: this cycle's spend and whether the target is met. */
@Composable
private fun UsageTarget(line: AccountLine, history: AccountHistory, today: LocalDate, currency: CurrencyFormat) {
    val acct = line.account
    val target = acct.usageTarget ?: return
    val usage = cardUsage(history.history, acct.statementDay, today, acct.usageTargetType == 2, history.usageExcluded)
    val met = usage.measured >= target
    Text(
        if (acct.usageTargetType == 2) tr("Usage target {0} · this cycle {1} · 3-month avg. {2} {3}", currency.format(target),
            currency.format(usage.thisCycle), currency.format(usage.measured), if (met) "✓" else "✗")
        else tr("Usage target {0} · this cycle {1} · last month {2} {3}", currency.format(target),
            currency.format(usage.thisCycle), currency.format(usage.measured), if (met) "✓" else "✗"),
        Modifier.padding(top = 6.dp), color = if (met) IncomeColor else ExpenseColor, fontSize = 13.sp,
    )
}

@Composable
private fun BalanceCharts(summaries: List<PeriodSum>) {
    Column(Modifier.padding(top = 8.dp).card()) {
        Text(tr("Balance"), Modifier.padding(start = 16.dp, top = 12.dp), color = SubtleText, fontSize = 12.sp)
        LineChart(summaries.map { it.balance }, summaries.map { it.label })
        Text(tr("Deposit / Withdrawal"), Modifier.padding(start = 16.dp, top = 4.dp), color = SubtleText, fontSize = 12.sp)
        PairBarChart(summaries.map { it.deposit }, summaries.map { it.withdrawal }, summaries.map { it.label })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PeriodList(summaries: List<PeriodSum>, currency: CurrencyFormat, onOpen: (PeriodSum) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(6.dp)) }
        item {
            Column(Modifier.card(ListCardMargin)) {
                summaries.asReversed().forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = ListRowInset + 6.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, color = Ink, fontWeight = FontWeight.Medium)
                            Text(p.range.dotted(), color = SubtleText, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(currency.format(p.deposit), color = IncomeColor, fontSize = 13.sp)
                            Text(currency.format(p.withdrawal), color = ExpenseColor, fontSize = 13.sp)
                            Text("(${currency.format(p.balance, withSymbol = false)})", color = SubtleText, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DayList(history: AccountHistory, names: AccountEntryNames, currency: CurrencyFormat) {
    val nav = LocalNavigator.current
    LazyColumn(Modifier.fillMaxSize()) {
        history.entries.groupBy { it.date }.forEach { (date, dayTxns) ->
            item(key = "h$date") {
                Column(Modifier.padding(top = 6.dp).card(ListCardMargin).padding(bottom = 4.dp)) {
                    DayHeader(date, dayTxns.depositTotal(), dayTxns.withdrawalTotal(), currency, inset = ListRowInset)
                    dayTxns.forEach { t ->
                        val flow = if (t.kind.raisesBalance) Flow.INCOME else Flow.EXPENSE
                        TxnLine(t, names.accounts, names.categories, flow, history.running[t.id], inset = ListRowInset) { nav.push(Screen.EditTxn(txnId = t.id)) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** + adds an entry on this account; cards also get Pay (from the payment account, what's due). */
@Composable
private fun BoxScope.DetailFabs(accountId: String, line: AccountLine?, today: LocalDate) {
    val nav = LocalNavigator.current
    Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.CenterVertically) {
        val acct = line?.account
        if (acct != null && line.group.type.isCreditCard) {
            SmallFloatingActionButton(
                onClick = {
                    nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.TRANSFER, today, acct.cardPaymentAccountId.orEmpty(),
                        toAccountId = accountId, amount = line.billDue, note = tr("Card payment"))))
                },
                modifier = Modifier.padding(bottom = 20.dp), containerColor = SurfaceColor, contentColor = Accent,
                shape = RoundedCornerShape(14.dp),
            ) { Text(tr("Pay"), Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Medium) }
        }
        AddFab { nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.EXPENSE, today, accountId, amount = 0))) }
    }
}

/** Type the new balance, then say how to record the difference: as income/expense (Yes) or only on the account (No). */
@Composable
private fun AdjustDialogs(line: AccountLine, currency: CurrencyFormat, onDismiss: () -> Unit, onAdjust: (target: Long, asIncomeExpense: Boolean) -> Unit) {
    var pendingTarget by remember { mutableStateOf<Long?>(null) }
    val target = pendingTarget
    if (target == null) {
        NewBalanceDialog(line, currency, onDismiss) { typed -> if (typed != line.balance) pendingTarget = typed else onDismiss() }
        return
    }
    val increase = target > line.balance
    fun record(asIncomeExpense: Boolean) {
        onDismiss()
        onAdjust(target, asIncomeExpense)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(if (increase) tr("The difference is registered on your account details. Would you like to record the difference as an income?")
                else tr("The difference is registered on your account details. Would you like to record the difference as an expense?"))
        },
        confirmButton = { TextButton(onClick = { record(true) }) { Text(tr("Yes"), color = Accent) } },
        dismissButton = { TextButton(onClick = { record(false) }) { Text(tr("No")) } },
    )
}

@Composable
private fun NewBalanceDialog(line: AccountLine, currency: CurrencyFormat, onDismiss: () -> Unit, onTyped: (Long) -> Unit) {
    var text by remember { mutableStateOf(currency.toInput(kotlin.math.abs(line.balance)).let { if (line.balance < 0) "-$it" else it }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Adjust balance")) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(tr("New balance")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = {
            TextButton(onClick = { parseBalance(text, currency, minusSigns = listOf("-"))?.let(onTyped) }) { Text(tr("Save"), color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
