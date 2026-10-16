package app.moneytracker.ui

import androidx.compose.ui.draw.clip
import app.moneytracker.domain.AccountType

import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Visibility
import app.moneytracker.domain.DateRange
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Fx
import app.moneytracker.domain.NetWorth
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.cardStatus
import app.moneytracker.domain.cardUsage
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.TAG_USAGE_EXCEPTION
import app.moneytracker.domain.summarize
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Accounts + balances + card statements, recomputed whenever balances change. */
@Composable
private fun rememberAccountSummary(): Pair<List<AccountLine>, NetWorth>? {
    val repo = LocalRepo.current
    val groups by repo.groups.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val balanceFlow = remember(repo) { repo.balances }
    val balances by balanceFlow.collectAsState(emptyMap())
    val currencies by repo.currencies.collectAsState(emptyList())
    val mainDecimals = LocalCurrency.current.decimals
    val tagged by repo.txnTagNames.collectAsState(emptyMap())     // re-run when tags change (Prepayment)
    val summary by produceState<Pair<List<AccountLine>, NetWorth>?>(null, groups, accounts, balances, currencies, tagged) {
        value = withContext(repo.io) {
            val prepaid = repo.txnIdsWithTag(TAG_PREPAYMENT)
            summarize(
                groups, accounts, balances,
                cardStatusOf = { a -> cardStatus(repo.accountTxns(a.id), a.cardSettlementDay ?: 1, repo.today(), prepaid) },
                toMain = { a, v -> Fx.toMain(v, currencies.firstOrNull { it.code == a.currency }, mainDecimals) },
            )
        }
    }
    return summary
}

private fun moneyColor(v: Long) = if (v < 0) ExpenseColor else IncomeColor

@Composable
fun AccountsTab() {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val summary = rememberAccountSummary()

    Column(Modifier.fillMaxSize()) {
        TitleTopBar(tr("Accounts")) {
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
                val isCard = group.type.isCreditCard
                // Accounts with "Include in totals" off still show below, but don't add to the group's total.
                val counted = groupLines.filter { it.account.includeInTotals }
                item(key = "g${group.id}") {
                    Column(Modifier.padding(top = 10.dp).card()) {
                        if (isCard) {
                            val payable = counted.sumOf { (it.card?.statementDue ?: 0) + (it.card?.overdue ?: 0) }
                            val outstanding = counted.sumOf { (it.card?.currentCycle ?: 0) + (it.card?.upcoming ?: 0) }
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                                Text(group.name, Modifier.weight(1f), color = SubtleText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                CardColumn(tr("Balance payable"), currency.format(payable))
                                CardColumn(tr("Outst. balance"), currency.format(outstanding))
                            }
                        } else {
                            // In the main currency: foreign-currency accounts are converted at their rate.
                            val sum = counted.sumOf {
                                val v = if (group.type.isDebitCard) it.balance else it.effectiveBalance
                                app.moneytracker.domain.Fx.toMain(v, it.account.currency?.let { c -> currencies[c] }, currency.decimals)
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                                Text(group.name, Modifier.weight(1f), color = SubtleText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(currency.format(sum), color = moneyColor(sum), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        groupLines.filter { !it.account.hidden }.forEach { line ->
                            Row(
                                Modifier.fillMaxWidth().background(SurfaceColor).clickable { nav.push(Screen.AccountDetail(line.account.id)) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val fmt = line.account.currency?.let { currencies[it]?.format } ?: currency
                                Column(Modifier.weight(1f)) {
                                    Text(line.account.name + (line.account.currency?.let { "  ($it)" } ?: ""), color = Ink)
                                    if (!line.account.includeInTotals) Text(tr("Not in totals"), fontSize = 12.sp, color = SubtleText)
                                    val limit = line.account.cardLimit
                                    val c = line.card
                                    if (limit != null && limit > 0 && c != null) {
                                        val ratio = (c.cycleSpend.toFloat() / limit).coerceIn(0f, 1f)
                                        androidx.compose.material3.LinearProgressIndicator(
                                            progress = { ratio }, Modifier.padding(top = 6.dp, end = 12.dp).fillMaxWidth().height(4.dp),
                                            color = if (c.cycleSpend > limit) ExpenseColor else if (ratio > 0.8f) Color(0xFFF7A35C) else IncomeColor,
                                            trackColor = PageBackground,
                                        )
                                        Text(tr("{0} of {1} limit", fmt.format(c.cycleSpend), fmt.format(limit)), fontSize = 11.sp,
                                            color = if (c.cycleSpend > limit) ExpenseColor else SubtleText)
                                    }
                                }
                                val card = line.card
                                if (card != null) {
                                    Text(fmt.format(card.statementDue + card.overdue), Modifier.width(120.dp), textAlign = TextAlign.End,
                                        color = if (card.overdue > 0) ExpenseColor else Ink, fontWeight = FontWeight.Medium)
                                    Text(fmt.format(card.currentCycle + card.upcoming), Modifier.width(120.dp), textAlign = TextAlign.End, color = Ink, fontWeight = FontWeight.Medium)
                                } else {
                                    val shown = if (line.group.type.isDebitCard) line.balance else line.effectiveBalance
                                    Text(fmt.format(shown), color = moneyColor(shown), fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CardColumn(label: String, value: String) {
    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.End) {
        Text(label, fontSize = 12.sp, color = SubtleText)
        Text(value, color = Ink)
    }
}

// ───────────────────────── account detail

@Composable
fun AccountDetailScreen(accountId: String) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val mainCurrency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val scope = rememberCoroutineScope()
    val summary = rememberAccountSummary()
    val line = summary?.first?.firstOrNull { it.account.id == accountId }
    val currency = line?.account?.currency?.let { currencies[it]?.format } ?: mainCurrency
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val startDay by repo.monthStartDay.collectAsState(1)
    var periodKey by rememberPeriod()
    val ym = YearMonth.fromKey(periodKey)
    val range = Periods.month(ym, startDay)
    val txns by remember(range, accountId) { repo.accountTxnsFlow(accountId, range) }.collectAsState(emptyList())
    // Running balance after each row, computed over the account's full history.
    val running by produceState(emptyMap<String, Long>(), txns, summary) {
        value = withContext(repo.io) {
            var acc = 0L
            repo.accountTxns(accountId).associate { t -> acc += t.signedAccountValue; t.id to acc }
        }
    }
    var adjusting by remember { mutableStateOf(false) }
    var pendingTarget by remember { mutableStateOf<Long?>(null) }
    var monthPicker by remember { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(0) }          // 0 Daily, 1 Monthly, 2 Annually
    var showChart by rememberSaveable { mutableStateOf(false) }
    // Full history, for monthly / yearly summaries and charts.
    val history by produceState(emptyList<app.moneytracker.domain.Txn>(), txns, summary) {
        value = withContext(repo.io) { repo.accountTxns(accountId) }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        BackTopBar(line?.account?.name ?: tr("Account"), nav::pop) {
            IconButton(onClick = { showChart = !showChart }) {
                Icon(Icons.Outlined.BarChart, tr("Charts"), tint = if (showChart) Accent else androidx.compose.material3.LocalContentColor.current)
            }
            IconButton(onClick = { periodKey = ym.plus(-1).key }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous")) }
            Text(ym.label(), Modifier.clip(RoundedCornerShape(8.dp)).clickable { monthPicker = true }.padding(horizontal = 4.dp, vertical = 6.dp))
            IconButton(onClick = { periodKey = ym.plus(1).key }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next")) }
        }
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 28.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Statement"), color = SubtleText, fontSize = 13.sp)
                    Text(range.dotted(), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                IconButton(onClick = { nav.push(Screen.EditAccount(accountId)) }) { Icon(Icons.Outlined.Edit, tr("Edit account")) }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallOutlineButton(tr("Adjust balance")) { adjusting = true }
            }
            val acct = line?.account
            if (acct != null && acct.memo.isNotBlank()) Text(acct.memo, Modifier.padding(top = 8.dp), color = SubtleText, fontSize = 13.sp)
            val target = acct?.usageTarget
            if (acct != null && target != null) {
                val usage = cardUsage(history, acct.cardSettlementDay ?: 1, repo.today(), acct.usageTargetType == 2,
                    remember(history) { repo.txnIdsWithTag(TAG_USAGE_EXCEPTION) })
                val met = usage.measured >= target
                Text(
                    if (acct.usageTargetType == 2) tr("Usage target {0} · this cycle {1} · 3-month avg. {2} {3}", currency.format(target),
                        currency.format(usage.thisCycle), currency.format(usage.measured), if (met) "✓" else "✗")
                    else tr("Usage target {0} · this cycle {1} · last month {2} {3}", currency.format(target),
                        currency.format(usage.thisCycle), currency.format(usage.measured), if (met) "✓" else "✗"),
                    Modifier.padding(top = 6.dp), color = if (met) IncomeColor else ExpenseColor, fontSize = 13.sp,
                )
            }
        }
        val deposit = txns.filter { it.kind.sign > 0 }.sumOf { it.accountValue }
        val withdrawal = txns.filter { it.kind.sign < 0 }.sumOf { it.accountValue }
        val balance = line?.balance ?: 0
        SummaryStrip(
            listOf(
                Triple(tr("Deposit"), deposit, IncomeColor), Triple(tr("Withdrawal"), withdrawal, ExpenseColor),
                Triple(tr("Total"), deposit - withdrawal, InkSoft), Triple(tr("Balance"), balance, moneyColor(balance)),
            ),
            currency,
        )
        if (monthPicker) MonthPickerDialog(ym, YearMonth.of(repo.today()), { monthPicker = false }) { periodKey = it.key; monthPicker = false }
        UnderlineTabs(listOf(tr("Daily"), tr("Monthly"), tr("Annually")), view) { view = it }
        // Periods for the Monthly (months of the shown year) and Annually (every year with data) views.
        val periods: List<Pair<String, DateRange>> = when (view) {
            2 -> {
                val first = history.firstOrNull()?.date?.year ?: repo.today().year
                (first..maxOf(first, repo.today().year)).map { y -> y.toString() to DateRange(Periods.month(YearMonth(y, 1), startDay).start, Periods.month(YearMonth(y, 12), startDay).endInclusive) }
            }
            else -> (1..12).map { m -> monthShort(m) to Periods.month(YearMonth(ym.year, m), startDay) }
        }
        val summaries = periods.map { (label, r) ->
            val inRange = history.filter { it.date >= r.start && it.date <= r.endInclusive }
            PeriodSummary(label, r, inRange.filter { it.kind.sign > 0 }.sumOf { it.accountValue }, inRange.filter { it.kind.sign < 0 }.sumOf { it.accountValue },
                history.filter { it.date <= r.endInclusive }.sumOf { it.signedAccountValue })
        }
        if (showChart) {
            Column(Modifier.padding(top = 8.dp).card()) {
                Text(tr("Balance"), Modifier.padding(start = 16.dp, top = 12.dp), color = SubtleText, fontSize = 12.sp)
                LineChart(summaries.map { it.balance }, summaries.map { it.label })
                Text(tr("Deposit / Withdrawal"), Modifier.padding(start = 16.dp, top = 4.dp), color = SubtleText, fontSize = 12.sp)
                PairBarChart(summaries.map { it.deposit }, summaries.map { it.withdrawal }, summaries.map { it.label })
                Spacer(Modifier.height(8.dp))
            }
        }
        if (view != 0) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(6.dp)) }
                item {
                    Column(Modifier.card(ListCardMargin)) {
                        summaries.asReversed().forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (view == 1) { periodKey = YearMonth(ym.year, monthIndex(p.label)).key; view = 0 }
                                }.padding(horizontal = ListRowInset + 6.dp, vertical = 12.dp),
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
            return@Column
        }
        if (txns.isEmpty()) { EmptyState(); return@Column }
        LazyColumn(Modifier.fillMaxSize()) {
            txns.groupBy { it.date }.forEach { (date, dayTxns) ->
                item(key = "h$date") {
                    Column(Modifier.padding(top = 6.dp).card(ListCardMargin).padding(bottom = 4.dp)) {
                        DayHeader(date, dayTxns.filter { it.kind.sign > 0 }.sumOf { it.accountValue }, dayTxns.filter { it.kind.sign < 0 }.sumOf { it.accountValue }, currency, inset = ListRowInset)
                        dayTxns.forEach { t ->
                            val flow = if (t.kind.sign > 0) Flow.INCOME else Flow.EXPENSE
                            TxnLine(t, accountName, catById, flow, running[t.id], inset = ListRowInset) { nav.push(Screen.EditTxn(txnId = t.id)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

        // + adds an entry on this account; cards also get Pay (from the payment account, what's due).
        Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.CenterVertically) {
            val acct = line?.account
            val isCredit = acct != null && summary?.first?.firstOrNull { it.account.id == accountId }?.group?.type?.isCreditCard == true
            if (isCredit) {
                val due = line?.card?.let { it.statementDue + it.overdue } ?: 0
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.TRANSFER, repo.today(), acct!!.cardPaymentAccountId.orEmpty(),
                            toAccountId = accountId, amount = due, note = tr("Card payment"))))
                    },
                    modifier = Modifier.padding(bottom = 20.dp), containerColor = SurfaceColor, contentColor = Accent,
                    shape = RoundedCornerShape(14.dp),
                ) { Text(tr("Pay"), Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.Medium) }
            }
            AddFab { nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.EXPENSE, repo.today(), accountId, amount = 0))) }
        }
    }

    if (adjusting && line != null) {
        var text by remember { mutableStateOf(currency.toInput(kotlin.math.abs(line.balance)).let { if (line.balance < 0) "-$it" else it }) }
        AlertDialog(
            onDismissRequest = { adjusting = false },
            title = { Text(tr("Adjust balance")) },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(tr("New balance")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = {
                TextButton(onClick = {
                    val negative = text.trim().startsWith("-")
                    val value = currency.parse(text.trim().removePrefix("-"))
                    if (value != null) {
                        val target = if (negative) -value else value
                        adjusting = false
                        if (target != line.balance) pendingTarget = target
                    }
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { adjusting = false }) { Text(tr("Cancel")) } },
        )
    }
    // Ask how to record the difference: as income/expense (Yes) or only on the account (No).
    pendingTarget?.let { target ->
        val increase = target > (line?.balance ?: 0)
        fun record(asIncomeExpense: Boolean) {
            pendingTarget = null
            scope.launch { withContext(repo.io) { repo.adjustBalance(accountId, target, asIncomeExpense) } }
        }
        AlertDialog(
            onDismissRequest = { pendingTarget = null },
            text = {
                Text(if (increase) tr("The difference is registered on your account details. Would you like to record the difference as an income?")
                    else tr("The difference is registered on your account details. Would you like to record the difference as an expense?"))
            },
            confirmButton = { TextButton(onClick = { record(true) }) { Text(tr("Yes"), color = Accent) } },
            dismissButton = { TextButton(onClick = { record(false) }) { Text(tr("No")) } },
        )
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

// ───────────────────────── manage accounts

@Composable
fun AccountListScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val groups by repo.groups.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val deleted by repo.deletedAccounts.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Accounts"), nav::pop) {
                TextButton(onClick = { nav.push(Screen.AccountGroups) }) { Text(tr("Groups"), color = Accent) }
            }
            HorizontalDivider(color = DividerColor)
            Text(tr("👁 hides an account from lists and pickers (history and balance stay). Arrows change the order."),
                Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = SubtleText)
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { g ->
                    val inGroup = accounts.filter { it.groupId == g.id }
                    if (inGroup.isEmpty()) return@forEach
                    item(key = "g${g.id}") { GreyHeader(g.name) }
                    items(inGroup, key = { it.id }) { a ->
                        val i = inGroup.indexOf(a)
                        fun move(delta: Int) {
                            val order = accounts.map { it.id }.toMutableList()
                            val other = inGroup.getOrNull(i + delta) ?: return
                            val x = order.indexOf(a.id); val y = order.indexOf(other.id)
                            order[x] = other.id; order[y] = a.id
                            scope.launch { withContext(repo.io) { repo.reorderAccounts(order) } }
                        }
                        Row(
                            Modifier.fillMaxWidth().background(SurfaceColor).clickable { nav.push(Screen.EditAccount(a.id)) }.padding(start = 24.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.name, Modifier.weight(1f).padding(vertical = 14.dp), color = if (a.hidden) SubtleText else Ink)
                            IconButton(onClick = { scope.launch { withContext(repo.io) { repo.setAccountHidden(a.id, !a.hidden) } } }) {
                                Icon(if (a.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (a.hidden) tr("Show") else tr("Hide"),
                                    tint = if (a.hidden) SubtleText else Accent)
                            }
                            IconButton(onClick = { move(-1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, tr("Move up")) }
                            IconButton(onClick = { move(1) }, enabled = i < inGroup.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, tr("Move down")) }
                        }
                        HorizontalDivider(color = DividerColor)
                    }
                }
                if (deleted.isNotEmpty()) {
                    item(key = "deleted") { GreyHeader(tr("Deleted accounts")) }
                    items(deleted, key = { "d" + it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 28.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.name, Modifier.weight(1f), color = SubtleText)
                            TextButton(onClick = { scope.launch { withContext(repo.io) { repo.restoreAccount(a.id) } } }) { Text(tr("Restore"), color = Accent) }
                        }
                        HorizontalDivider(color = DividerColor)
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { nav.push(Screen.EditAccount()) }
    }
}

@Composable
fun EditAccountScreen(accountId: String?) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())

    var loaded by remember { mutableStateOf(accountId == null) }
    var name by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf<String?>(null) }
    var includeInTotals by remember { mutableStateOf(true) }
    var transferAsExpense by remember { mutableStateOf(false) }
    var settlementDay by remember { mutableStateOf("1") }
    var paymentAccountId by remember { mutableStateOf<String?>(null) }
    var sortOrder by remember { mutableStateOf(accounts.size) }
    var accountCurrency by remember { mutableStateOf<String?>(null) }
    var limitText by remember { mutableStateOf("") }
    var paymentDay by remember { mutableStateOf("") }
    var autoPay by remember { mutableStateOf(false) }
    var targetText by remember { mutableStateOf("") }
    var targetType by remember { mutableStateOf(1) }
    var memo by remember { mutableStateOf("") }
    var smsSender by remember { mutableStateOf("") }
    var smsMatch by remember { mutableStateOf("") }
    val currencies by repo.currencies.collectAsState(emptyList())
    val mainFmt = LocalCurrency.current
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(accounts, accountId) {
        if (loaded) return@LaunchedEffect
        val a = accounts.firstOrNull { it.id == accountId } ?: return@LaunchedEffect
        name = a.name; groupId = a.groupId; includeInTotals = a.includeInTotals; transferAsExpense = a.transferAsExpense
        settlementDay = (a.cardSettlementDay ?: 1).toString(); paymentAccountId = a.cardPaymentAccountId; sortOrder = a.sortOrder
        accountCurrency = a.currency
        val fmt = a.currency?.let { c -> currencies.firstOrNull { it.code == c }?.format } ?: mainFmt
        limitText = a.cardLimit?.let { fmt.toInput(it) }.orEmpty()
        paymentDay = a.cardPaymentDay?.toString().orEmpty(); autoPay = a.cardAutoPay
        targetText = a.usageTarget?.let { fmt.toInput(it) }.orEmpty(); targetType = a.usageTargetType
        memo = a.memo; smsSender = a.smsSender; smsMatch = a.smsMatch
        loaded = true
    }
    val group = groups.firstOrNull { it.id == groupId }
    val isCard = group?.type?.isCreditCard == true || group?.type?.isDebitCard == true

    Column(Modifier.fillMaxSize().background(SurfaceColor)) {
        BackTopBar(if (accountId == null) tr("Add account") else tr("Edit account"), nav::pop) {
            if (accountId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, tr("Delete account")) }
        }
        HorizontalDivider(color = DividerColor)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Picker(tr("Group"), groups, group, { it.name }, { groupId = it.id }, Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (isCard) {
                if (group?.type?.isCreditCard == true) {
                    OutlinedTextField(settlementDay, { settlementDay = it.filter(Char::isDigit).take(2) }, label = { Text(tr("Statement / billing day (1–31)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Picker(
                    if (group?.type?.isCreditCard == true) tr("Payment account") else tr("Linked bank account"),
                    accounts.filter { it.id != accountId }, accounts.firstOrNull { it.id == paymentAccountId }, { it.name },
                    { paymentAccountId = it.id }, Modifier.fillMaxWidth(),
                )
            }
            if (currencies.isNotEmpty()) {
                Picker(tr("Currency"), listOf<String?>(null) + currencies.map { it.code }, accountCurrency,
                    { it ?: tr("Main currency ({0})", mainFmt.symbol) }, { accountCurrency = it }, Modifier.fillMaxWidth())
            }
            if (group?.type?.isCreditCard == true) {
                OutlinedTextField(paymentDay, { paymentDay = it.filter(Char::isDigit).take(2) }, label = { Text(tr("Payment day (1–31, optional)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow(tr("Auto-pay"), autoPay) { autoPay = it }
                OutlinedTextField(limitText, { limitText = it }, label = { Text(tr("Monthly spending limit (optional)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                // Monthly card usage: spend needed each month, e.g. to keep a card's benefits.
                OutlinedTextField(targetText, { targetText = it }, label = { Text(tr("Monthly usage target (optional)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (targetText.isNotBlank()) {
                    Picker(tr("Usage is checked against"), listOf(1, 2), targetType, { if (it == 2) tr("3 months average") else tr("Previous month") },
                        { targetType = it }, Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(memo, { memo = it }, label = { Text(tr("Memo (optional)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            // SMS link for this account: detected messages that match are filed here.
            Text(tr("Bank messages"), Modifier.padding(top = 4.dp), color = SubtleText, fontSize = 13.sp)
            OutlinedTextField(smsMatch, { smsMatch = it }, label = { Text(tr("Text in the message (e.g. card digits x1234)")) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(smsSender, { smsSender = it }, label = { Text(tr("Sender number or ID (optional)")) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow(tr("Include in totals"), includeInTotals) { includeInTotals = it }
            SwitchRow(tr("Transfers in count as expense"), transferAsExpense) { transferAsExpense = it }
            Text(tr("Turn this on for savings, investments or loans so money moved there shows in your spending."),
                fontSize = 13.sp, color = SubtleText)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Box(
                Modifier.fillMaxWidth().height(52.dp).background(Accent, RoundedCornerShape(6.dp)).clickable {
                    val day = settlementDay.toIntOrNull()
                    val payDay = paymentDay.toIntOrNull()
                    error = when {
                        groupId == null -> tr("Choose a group")
                        name.isBlank() -> tr("Enter a name")
                        group?.type?.isCreditCard == true && (day == null || day !in 1..31) -> tr("Billing day must be 1–31")
                        group?.type?.isCreditCard == true && paymentDay.isNotBlank() && (payDay == null || payDay !in 1..31) -> tr("Payment day must be 1–31")
                        else -> null
                    }
                    if (error != null) return@clickable
                    val fmt = accountCurrency?.let { c -> currencies.firstOrNull { it.code == c }?.format } ?: mainFmt
                    val credit = group?.type?.isCreditCard == true
                    // Start from the stored account so fields this form doesn't show (hidden, …) are kept.
                    val base = accounts.firstOrNull { it.id == accountId } ?: Account(newId(), groupId!!, "", sortOrder = sortOrder)
                    val account = base.copy(
                        groupId = groupId!!, name = name.trim(), includeInTotals = includeInTotals, transferAsExpense = transferAsExpense,
                        cardSettlementDay = if (credit) day else null, cardPaymentAccountId = if (isCard) paymentAccountId else null,
                        currency = accountCurrency,
                        cardLimit = if (credit) fmt.parse(limitText)?.takeIf { it > 0 } else null,
                        memo = memo.trim(),
                        cardPaymentDay = if (credit) payDay else null, cardAutoPay = credit && autoPay,
                        usageTarget = if (credit) fmt.parse(targetText)?.takeIf { it > 0 } else null, usageTargetType = targetType,
                        smsSender = smsSender.trim(), smsMatch = smsMatch.trim(),
                    )
                    scope.launch { withContext(repo.io) { repo.saveAccount(account) }; nav.pop() }
                },
                contentAlignment = Alignment.Center,
            ) { Text(tr("Save"), color = Color.White, fontSize = 17.sp) }
        }
    }

    if (confirmDelete && accountId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete account?")) },
            text = { Text(tr("The account is hidden. Its transactions stay in your history and reports.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(repo.io) { repo.deleteAccount(accountId) }
                        nav.pop()
                        if (nav.stack.lastOrNull() == Screen.AccountDetail(accountId)) nav.pop()
                    }
                }) { Text(tr("Delete"), color = DangerColor) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        AppSwitch(checked, onChange)
    }
}

private data class PeriodSummary(val label: String, val range: DateRange, val deposit: Long, val withdrawal: Long, val balance: Long)

private fun monthIndex(short: String) = (1..12).first { monthShort(it) == short }

/** Assets, liabilities and net worth at the end of each of the last 12 months (main currency). */
@Composable
fun AccountsTrendScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val accounts by repo.accounts.collectAsState(emptyList())
    val points by produceState(emptyList<Triple<String, Long, Long>>(), accounts, ps) {
        value = withContext(repo.io) {
            val all = repo.allTxns()
            val included = accounts.filter { it.includeInTotals }.map { it.id }.toSet()
            val current = ps.labelFor(repo.today())
            (11 downTo 0).map { back ->
                val ym = current.plus(-back)
                val end = ps.month(ym).endInclusive
                val perAccount = all.filter { it.date <= end && it.accountId in included }.groupBy { it.accountId }.mapValues { (_, v) -> v.sumOf { it.signedAmount } }
                Triple(monthShort(ym.month), perAccount.values.filter { it > 0 }.sum(), -perAccount.values.filter { it < 0 }.sum())
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Total stats"), nav::pop)
        HorizontalDivider(color = DividerColor)
        if (points.isEmpty()) return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(top = 10.dp).card()) {
                    Text(tr("Net worth"), Modifier.padding(start = 16.dp, top = 12.dp), color = SubtleText, fontSize = 12.sp)
                    LineChart(points.map { it.second - it.third }, points.map { it.first })
                    Text(tr("Assets / Liabilities"), Modifier.padding(start = 16.dp, top = 4.dp), color = SubtleText, fontSize = 12.sp)
                    PairBarChart(points.map { it.second }, points.map { it.third }, points.map { it.first })
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Column(Modifier.padding(top = 10.dp).card()) {
                    points.asReversed().forEach { (label, assets, liabilities) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.width(48.dp), color = Ink, fontWeight = FontWeight.Medium)
                            Text(currency.format(assets), Modifier.weight(1f), color = IncomeColor, fontSize = 13.sp, textAlign = TextAlign.End)
                            Text(currency.format(liabilities), Modifier.weight(1f), color = ExpenseColor, fontSize = 13.sp, textAlign = TextAlign.End)
                            Text(currency.format(assets - liabilities), Modifier.weight(1.1f), color = Ink, fontSize = 13.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
                        }
                        HorizontalDivider(color = DividerColor)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Account groups: rename, type (default / credit card / debit card), order, add, delete empty ones. */
@Composable
fun AccountGroupsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsState(emptyList())
    val deletedGroups by repo.deletedGroups.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    var editing by remember { mutableStateOf<app.moneytracker.domain.AccountGroup?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Account groups"), nav::pop)
            HorizontalDivider(color = DividerColor)
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.id }) { g ->
                    val i = groups.indexOf(g)
                    fun move(delta: Int) {
                        val order = groups.map { it.id }.toMutableList()
                        val j = i + delta
                        if (j !in order.indices) return
                        order[i] = order[j].also { order[j] = order[i] }
                        scope.launch { withContext(repo.io) { repo.reorderGroups(order) } }
                    }
                    Row(Modifier.fillMaxWidth().background(SurfaceColor).clickable { editing = g }.padding(start = 24.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(g.name, color = Ink)
                            Text(tr("{0} · {1} accounts", tr(g.type.label), accounts.count { it.groupId == g.id }), fontSize = 12.sp, color = SubtleText)
                        }
                        IconButton(onClick = { move(-1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, tr("Move up")) }
                        IconButton(onClick = { move(1) }, enabled = i < groups.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, tr("Move down")) }
                    }
                    HorizontalDivider(color = DividerColor)
                }
                if (deletedGroups.isNotEmpty()) {
                    item(key = "deletedGroups") { GreyHeader(tr("Deleted groups")) }
                    items(deletedGroups, key = { "d" + it.id }) { g ->
                        Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name, Modifier.weight(1f).padding(vertical = 14.dp), color = SubtleText)
                            TextButton(onClick = { scope.launch { withContext(repo.io) { repo.restoreGroup(g.id) } } }) { Text(tr("Restore"), color = Accent) }
                        }
                        HorizontalDivider(color = DividerColor)
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) {
            editing = app.moneytracker.domain.AccountGroup(app.moneytracker.data.newId(), "", app.moneytracker.domain.AccountType.OTHER, groups.size)
        }
    }
    editing?.let { g ->
        var name by remember(g.id) { mutableStateOf(g.name) }
        var type by remember(g.id) { mutableStateOf(g.type) }
        val isNew = groups.none { it.id == g.id }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) tr("New group") else tr("Edit group")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true)
                    Picker(tr("Type"), AccountType.entries, type, { tr(it.label) }, { type = it })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { withContext(repo.io) { repo.saveGroup(g.copy(name = name, type = type)) } }
                    editing = null
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = {
                Row {
                    if (!isNew) TextButton(onClick = {
                        scope.launch {
                            val ok = withContext(repo.io) { repo.deleteGroup(g.id) }
                            if (!ok) notice = tr("Move or delete the accounts in “{0}” first.", g.name)
                        }
                        editing = null
                    }) { Text(tr("Delete"), color = DangerColor) }
                    TextButton(onClick = { editing = null }) { Text(tr("Cancel")) }
                }
            },
        )
    }
    notice?.let { m -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { notice = null }) { Text(tr("OK")) } }) }
}

private val AccountType.label: String get() = when (this) {
    AccountType.CREDIT_CARD -> "Credit card"
    AccountType.DEBIT_CARD -> "Debit card"
    AccountType.OVERDRAFT -> "Overdraft"
    AccountType.PREPAID -> "Top-up / prepaid"
    else -> name.lowercase().replaceFirstChar { it.uppercase() }
}
