package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.Periods
import app.moneytracker.domain.TAG_USAGE_EXCEPTION
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.cardUsage
import app.moneytracker.ui.LineChart
import app.moneytracker.ui.LocalCurrencies
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.MonthPickerDialog
import app.moneytracker.ui.PairBarChart
import app.moneytracker.ui.Screen
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.label
import app.moneytracker.ui.long
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberPeriod
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private enum class DetailTab(val label: String) { ENTRIES("Entries"), MONTHLY("Monthly"), YEARLY("Yearly") }

private class PeriodSum(val label: String, val range: DateRange, val deposit: Long, val withdrawal: Long, val balance: Long, val month: YearMonth?)

/** One billing cycle of a card: what was charged, and what of it is still unpaid (payments settle the oldest charges first). */
private class Cycle(val range: DateRange, val charged: Long, val unpaid: Long)

private fun cycles(history: List<Txn>, settlementDay: Int, today: LocalDate, count: Int): List<Cycle> {
    val ranges = generateSequence(Periods.cardCycle(today, settlementDay)) { Periods.cardCycle(it.start.minus(DatePeriod(days = 1)), settlementDay) }
        .take(count).toList().reversed()
    var payments = history.filter { it.kind.sign > 0 }.sumOf { it.accountValue }
    val charges = history.filter { it.kind.sign < 0 }
    payments -= minOf(payments, charges.filter { it.date < ranges.first().start }.sumOf { it.accountValue })
    return ranges.map { r ->
        val charged = charges.filter { it.date in r }.sumOf { it.accountValue }
        val paid = minOf(charged, payments)
        payments -= paid
        Cycle(r, charged, charged - paid)
    }.reversed()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountDetailBody(accountId: String) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val mainCurrency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val summary = rememberMoneySummary()
    val line = summary?.first?.firstOrNull { it.account.id == accountId }
    val fmt = line?.account?.currency?.let { currencies[it]?.format } ?: mainCurrency
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val startDay by repo.monthStartDay.collectAsState(1)
    var periodKey by rememberPeriod()
    val ym = YearMonth.fromKey(periodKey)
    val range = Periods.month(ym, startDay)
    val txns by remember(range, accountId) { repo.accountTxnsFlow(accountId, range) }.collectAsState(emptyList())
    val history by produceState(emptyList<Txn>(), txns, summary) { value = withContext(repo.io) { repo.accountTxns(accountId) } }
    val running = remember(history) { var acc = 0L; history.associate { t -> acc += t.signedAccountValue; t.id to acc } }
    var tab by rememberSaveable { mutableStateOf(DetailTab.ENTRIES) }
    var statement by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var monthPicker by remember { mutableStateOf(false) }
    val today = repo.today()
    val acct = line?.account
    val isCredit = line?.group?.type?.isCreditCard == true

    if (monthPicker) MonthPickerDialog(ym, YearMonth.of(today), { monthPicker = false }) { periodKey = it.key; monthPicker = false }

    SubScreen(acct?.name ?: tr("Account"), nav::pop, actions = {
        if (acct != null) Pill(tr("Edit"), false, { nav.push(Screen.EditAccount(accountId)) })
    }) {
        if (line == null || acct == null) {
            if (summary != null) EmptyCard(tr("This account was deleted or can't be found."))
            return@SubScreen
        }
        Hero(line, fmt, history, today)

        // Actions: cards pay their bill; every account can add, move or correct money.
        val addEntry = { nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.EXPENSE, today, accountId, amount = 0))) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isCredit) {
                ActionTile(Icons.Outlined.CreditCard, if (line.billDue > 0) tr("Pay bill") else tr("Paid ✓"), primary = line.billDue > 0) {
                    if (line.owed > 0) sheet = "pay"
                    else toaster.show(tr("Nothing to pay. The next statement closes on {0}.", Periods.cardCycle(today, acct.cardSettlementDay ?: 1).endInclusive.short()))
                }
                ActionTile(Icons.Outlined.Add, tr("Add entry"), onClick = addEntry)
                ActionTile(Icons.Outlined.Tune, tr("Adjust"), onClick = { sheet = "adjust" })
                ActionTile(Icons.AutoMirrored.Outlined.ReceiptLong, tr("Statement"), selected = statement, onClick = { statement = !statement })
            } else {
                ActionTile(Icons.Outlined.Add, tr("Add entry"), primary = true, onClick = addEntry)
                ActionTile(Icons.Outlined.SwapHoriz, tr("Transfer"), onClick = {
                    nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.TRANSFER, today, accountId, amount = 0)))
                })
                ActionTile(Icons.Outlined.Tune, tr("Adjust balance"), onClick = { sheet = "adjust" })
            }
        }

        if (isCredit && statement) FCard {
            val payDay = acct.cardPaymentDay
            SectionLabel(
                if (payDay != null) tr("Statements · billing day {0} · pay by day {1}", acct.cardSettlementDay ?: 1, payDay)
                else tr("Statements · billing day {0}", acct.cardSettlementDay ?: 1),
                Modifier.padding(bottom = 4.dp),
            )
            val due = billDueDate(acct, today)
            cycles(history, acct.cardSettlementDay ?: 1, today, 6).forEachIndexed { i, c ->
                if (i > 0) Divider()
                val (status, color) = when {
                    i == 0 -> tr("Open · closes {0}", c.range.endInclusive.short()) to F.muted
                    c.charged == 0L -> tr("No spending") to F.muted
                    c.unpaid == 0L -> tr("Paid") to F.income
                    i == 1 && due != null && due >= today -> tr("{0} due {1}", fmt.format(c.unpaid), due.short()) to F.danger
                    else -> tr("{0} unpaid", fmt.format(c.unpaid)) to F.danger
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.range.label(), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(status, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(fmt.format(c.charged), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = F.money)
                }
            }
        }

        // Settings worth knowing at a glance.
        val notes = buildList {
            if (isCredit) {
                val from = acct.cardPaymentAccountId?.let { accountName[it] }
                val due = billDueDate(acct, today)
                add(listOfNotNull(
                    due?.let { tr("Pay by {0}", it.short()) },
                    from?.let { tr("from {0}", it) },
                    if (acct.cardAutoPay) tr("auto-pay on") else tr("auto-pay off"),
                ).joinToString(" · "))
            }
            if (line.group.type.isDebitCard) acct.cardPaymentAccountId?.let { accountName[it] }?.let { add(tr("Spends come out of {0}", it)) }
            if (acct.smsSender.isNotBlank() || acct.smsMatch.isNotBlank()) add(tr("Bank SMS rule: {0}", listOfNotNull(
                acct.smsSender.ifBlank { null }?.let { tr("sender {0}", it) }, acct.smsMatch.ifBlank { null }?.let { tr("text {0}", it) },
            ).joinToString(", ")))
            if (!acct.includeInTotals) add(tr("Not counted in net worth"))
            if (acct.transferAsExpense) add(tr("Money moved here counts as spending"))
            if (acct.hidden) add(tr("Hidden from lists and pickers"))
        }
        if (notes.isNotEmpty()) Column(
            Modifier.fillMaxWidth().clip(F.cardShape).background(F.fill).clickable { nav.push(Screen.EditAccount(accountId)) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { notes.forEach { Text(it, color = F.muted, fontSize = 13.sp) } }

        Segmented(DetailTab.entries.map { tr(it.label) }, tab.ordinal, { tab = DetailTab.entries[it] })
        when (tab) {
            DetailTab.ENTRIES -> {
                PeriodStepper(ym.label(), { periodKey = ym.plus(-1).key }, { periodKey = ym.plus(1).key }, onLabel = { monthPicker = true },
                    sub = if (startDay != 1) range.label() else null)
                val deposit = txns.filter { it.kind.sign > 0 }.sumOf { it.accountValue }
                val withdrawal = txns.filter { it.kind.sign < 0 }.sumOf { it.accountValue }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTotal(tr("In"), fmt.format(deposit), F.income, Modifier.weight(1f))
                    MiniTotal(tr("Out"), fmt.format(withdrawal), F.expense, Modifier.weight(1f))
                    MiniTotal(tr("Change"), fmt.format(deposit - withdrawal, showSign = true), F.ink, Modifier.weight(1f))
                }
                if (txns.isEmpty()) EmptyCard(tr("No entries in {0}.", ym.label()), tr("Add entry"), addEntry)
                txns.groupBy { it.date }.forEach { (date, day) ->
                    FCard(padding = 0.dp) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(date.long(), Modifier.weight(1f), color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            val net = day.sumOf { it.signedAccountValue }
                            Text(fmt.format(net, showSign = true), color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, style = F.money)
                        }
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
                            day.forEachIndexed { i, t ->
                                if (i > 0) Divider()
                                EntryRow(t, accountName, catById, fmt, running[t.id]) { nav.push(Screen.EditTxn(txnId = t.id)) }
                            }
                        }
                    }
                }
            }
            DetailTab.MONTHLY, DetailTab.YEARLY -> {
                val yearly = tab == DetailTab.YEARLY
                if (!yearly) PeriodStepper(ym.year.toString(), { periodKey = ym.plus(-12).key }, { periodKey = ym.plus(12).key })
                val periods: List<Pair<String, DateRange>> = if (yearly) {
                    val first = history.firstOrNull()?.date?.year ?: today.year
                    (first..maxOf(first, today.year)).map { y ->
                        y.toString() to DateRange(Periods.month(YearMonth(y, 1), startDay).start, Periods.month(YearMonth(y, 12), startDay).endInclusive)
                    }
                } else (1..12).map { m -> monthShort(m) to Periods.month(YearMonth(ym.year, m), startDay) }
                val sums = periods.mapIndexed { i, (label, r) ->
                    val inRange = history.filter { it.date in r }
                    PeriodSum(label, r, inRange.filter { it.kind.sign > 0 }.sumOf { it.accountValue }, inRange.filter { it.kind.sign < 0 }.sumOf { it.accountValue },
                        history.filter { it.date <= r.endInclusive }.sumOf { it.signedAccountValue }, if (yearly) null else YearMonth(ym.year, i + 1))
                }
                FCard(padding = 0.dp) {
                    Text(tr("Balance"), Modifier.padding(start = 16.dp, top = 14.dp), color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    LineChart(sums.map { it.balance }, sums.map { if (yearly) it.label.takeLast(2) else it.label.take(1) }, color = F.accent)
                    Text(tr("In / out"), Modifier.padding(start = 16.dp, top = 4.dp), color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    PairBarChart(sums.map { it.deposit }, sums.map { it.withdrawal }, sums.map { if (yearly) it.label.takeLast(2) else it.label.take(1) },
                        colorA = F.income, colorB = F.expense)
                    Gap(8.dp)
                }
                FCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        sums.asReversed().forEachIndexed { i, p ->
                            if (i > 0) Divider()
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (p.month != null) { periodKey = p.month.key; tab = DetailTab.ENTRIES }
                                    else { periodKey = YearMonth(p.label.toInt(), 1).key; tab = DetailTab.MONTHLY }
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(p.range.label(), color = F.muted, fontSize = 12.5.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("+" + fmt.format(p.deposit), color = F.income, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = F.money)
                                    Text(fmt.format(-(p.withdrawal)), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = F.money)
                                    Text(tr("Balance {0}", fmt.format(p.balance)), color = F.muted, fontSize = 11.sp, style = F.money)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (line != null) when (sheet) {
        "pay" -> PaySheet(line, fmt, today) { sheet = null }
        "adjust" -> AdjustSheet(line, fmt) { sheet = null }
    }
}

@Composable
private fun Hero(line: AccountLine, fmt: CurrencyFormat, history: List<Txn>, today: LocalDate) {
    val repo = LocalRepo.current
    val a = line.account
    val card = line.card
    Column(Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).padding(20.dp)) {
        val on = F.ink
        val soft = F.muted
        Row {
            Text(listOf(line.group.type.moneyLabel, line.group.name).distinct().joinToString(" · ") + (a.currency?.let { " · $it" } ?: ""),
                Modifier.weight(1f), color = soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card != null) Text(tr("Cycle {0}", Periods.cardCycle(today, a.cardSettlementDay ?: 1).label()), color = soft, fontSize = 12.sp, maxLines = 1)
        }
        Gap(8.dp)
        if (card != null) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(tr("Balance payable"), color = soft, fontSize = 12.sp)
                    Text(fmt.format(line.billDue), color = on, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, style = F.money, maxLines = 1)
                    if (card.overdue > 0) Text(tr("incl. {0} overdue", fmt.format(card.overdue)), color = on, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(tr("Not yet billed"), color = soft, fontSize = 12.sp)
                    Text(fmt.format(card.currentCycle + card.upcoming), color = on, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, style = F.money, maxLines = 1)
                    Text(tr("Spent this cycle {0}", fmt.format(card.cycleSpend)), color = soft, fontSize = 11.sp, maxLines = 1)
                }
            }
            val limit = a.cardLimit
            if (limit != null && limit > 0) {
                Gap(12.dp)
                Bar(card.cycleSpend.toFloat() / limit, fill = F.accent, track = F.fill, height = 6.dp)
                Text(tr("{0} of {1} limit", fmt.format(card.cycleSpend), fmt.format(limit)), Modifier.padding(top = 4.dp), color = soft, fontSize = 12.sp)
            }
            val target = a.usageTarget
            if (target != null) {
                val excluded = remember(history) { repo.txnIdsWithTag(TAG_USAGE_EXCEPTION) }
                val usage = cardUsage(history, a.cardSettlementDay ?: 1, today, a.usageTargetType == 2, excluded)
                val met = usage.measured >= target
                Gap(12.dp)
                Bar(usage.thisCycle.toFloat() / target, fill = F.accent, track = F.fill, height = 6.dp)
                Text(
                    (if (a.usageTargetType == 2) tr("Usage target {0} · this cycle {1} · 3-month avg. {2} {3}", fmt.format(target),
                        fmt.format(usage.thisCycle), fmt.format(usage.measured), if (met) "✓" else "✗")
                    else tr("Usage target {0} · this cycle {1} · last month {2} {3}", fmt.format(target),
                        fmt.format(usage.thisCycle), fmt.format(usage.measured), if (met) "✓" else "✗")),
                    Modifier.padding(top = 4.dp), color = on, fontSize = 12.sp, fontWeight = if (met) FontWeight.Normal else FontWeight.Bold,
                )
            }
        } else {
            Text(tr("Balance"), color = soft, fontSize = 12.sp)
            Text(fmt.format(line.balance), color = on, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, style = F.money, maxLines = 1)
            if (line.effectiveBalance != line.balance && !line.group.type.isDebitCard)
                Text(tr("{0} after debit card spends", fmt.format(line.effectiveBalance)), color = soft, fontSize = 12.sp)
        }
        if (a.memo.isNotBlank()) {
            Gap(10.dp)
            Text(a.memo, color = soft, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RowScope.ActionTile(icon: ImageVector, label: String, primary: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val bg = when { primary -> F.accent; selected -> F.ink; else -> F.card }
    val fg = when { primary -> F.onAccent; selected -> F.page; else -> F.ink }
    Column(
        Modifier.weight(1f).clip(F.buttonShape).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MiniTotal(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(F.card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, style = F.money, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EntryRow(t: Txn, accountName: Map<String, String>, catById: Map<String, app.moneytracker.domain.Category>, fmt: CurrencyFormat, running: Long?, onClick: () -> Unit) {
    val title = t.note.ifBlank {
        when (t.kind) {
            TxnKind.TRANSFER_OUT -> tr("To {0}", accountName[t.toAccountId].orEmpty())
            TxnKind.TRANSFER_IN -> tr("From {0}", accountName[t.toAccountId].orEmpty())
            TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr(MODIFIED_BALANCE_NAME)
            else -> categoryPath(t.categoryId, catById)
        }
    }
    val meta = listOfNotNull(
        when (t.kind) {
            TxnKind.TRANSFER_OUT -> tr("Transfer to {0}", accountName[t.toAccountId].orEmpty())
            TxnKind.TRANSFER_IN -> tr("Transfer from {0}", accountName[t.toAccountId].orEmpty())
            TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr("Not counted")
            else -> if (t.note.isNotBlank()) categoryPath(t.categoryId, catById) else null
        },
        t.installmentLabel,
        if (t.isRefund) tr("Refund") else null,
        if (t.recurringRuleId != null) "↻" else null,
    ).joinToString(" · ")
    // Signed so a refund (negative expense) reads as money in.
    val value = t.signedAccountValue
    val incoming = value > 0
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, color = F.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt.format(value, showSign = true), color = if (incoming) F.income else F.ink,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, style = F.money, maxLines = 1)
            if (running != null) Text(fmt.format(running), color = F.muted, fontSize = 11.sp, style = F.money, maxLines = 1)
        }
    }
}

/** Pay a card bill: amount, from which account and when; saved as a transfer, with Undo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaySheet(line: AccountLine, fmt: CurrencyFormat, today: LocalDate, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val platform = LocalPlatform.current
    val accounts by repo.accounts.collectAsState(emptyList())
    val groups by repo.groups.collectAsState(emptyList())
    val a = line.account
    val cardGroups = groups.filter { it.type.isCreditCard }.map { it.id }.toSet()
    val sources = accounts.filter { it.id != a.id && !it.hidden && it.groupId !in cardGroups }
        .sortedBy { if (it.id == a.cardPaymentAccountId) 0 else 1 }
    val due = billDueDate(a, today)?.takeIf { it > today }
    var amount by remember { mutableStateOf(fmt.toInput(if (line.billDue > 0) line.billDue else line.owed)) }
    var from by remember { mutableStateOf(sources.firstOrNull()?.id) }
    var date by remember { mutableStateOf(today) }
    val value = fmt.parse(amount)?.takeIf { it > 0 }
    val fromName = sources.firstOrNull { it.id == from }?.name
    fun draft(v: Long) = TxnDraft(DraftType.TRANSFER, date, from.orEmpty(), toAccountId = a.id, amount = v, note = tr("Card payment"), currency = a.currency)

    Sheet(tr("Pay {0} bill", a.name), onDismiss, bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (value != null) tr("Pay {0}", fmt.format(value)) else tr("Enter an amount"), {
                val v = value ?: return@PrimaryButton
                if (from == null) return@PrimaryButton
                val d = draft(v)
                val name = fromName.orEmpty()
                onDismiss()
                repo.moneyIo {
                    val id = repo.create(d)
                    val left = (line.billDue - v).coerceAtLeast(0)
                    onMain {
                        platform.refreshWidgets()
                        toaster.show(tr("Paid {0} from {1}", fmt.format(v), name) + if (left > 0) " · " + tr("{0} still due", fmt.format(left)) else "") {
                            repo.moneyIo { repo.txn(id)?.let { repo.delete(it) } }
                        }
                    }
                }
            }, enabled = value != null && from != null)
            SecondaryButton(tr("More options"), { val v = value ?: 0; onDismiss(); nav.push(Screen.EditTxn(preset = draft(v).copy(accountId = from ?: a.cardPaymentAccountId.orEmpty()))) })
        }
    }) {
        MoneyTextField(tr("Amount"), amount, { amount = it }, keyboard = KeyboardType.Decimal)
        Gap(4.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (line.billDue > 0) ChoiceChip(tr("Full bill {0}", fmt.format(line.billDue)), value == line.billDue) { amount = fmt.toInput(line.billDue) }
            if (line.owed != line.billDue && line.owed > 0) ChoiceChip(tr("Everything owed {0}", fmt.format(line.owed)), value == line.owed) { amount = fmt.toInput(line.owed) }
        }
        Gap(8.dp)
        SectionLabel(tr("Pay from"))
        if (sources.isEmpty()) Text(tr("Add a bank or cash account to pay from."), color = F.muted, fontSize = 13.sp)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { s -> ChoiceChip(s.name, s.id == from) { from = s.id } }
        }
        Gap(8.dp)
        SectionLabel(tr("Date"))
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(tr("Today, {0}", today.short()), date == today) { date = today }
            if (due != null) ChoiceChip(tr("Due date, {0}", due.short()), date == due) { date = due }
        }
        Gap(8.dp)
        Text(tr("Recorded as a transfer from {0} to {1}, so it isn't counted as spending.", fromName ?: "…", a.name), color = F.muted, fontSize = 12.5.sp)
    }
}

/** Correct the balance: type the real figure, then choose whether the difference counts as income/expense; with Undo. */
@Composable
private fun AdjustSheet(line: AccountLine, fmt: CurrencyFormat, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val toaster = LocalToaster.current
    val card = line.card != null
    // Cards are adjusted by what is owed (a positive number), everything else by its balance.
    val shown = if (card) -line.balance else line.balance
    var text by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val negative = text.trim().startsWith("-") || text.trim().startsWith("−")
    val parsed = fmt.parse(text.trim().removePrefix("-").removePrefix("−"))?.let { if (negative) -it else it }
    val target = parsed?.let { if (card) -it else it }
    val diff = target?.let { it - line.balance } ?: 0L
    val increase = diff > 0
    val currentLabel = if (card) tr("balance payable") else tr("balance")

    fun finish(record: Boolean) {
        val t = target ?: return
        onDismiss()
        repo.moneyIo {
            val before = repo.accountTxns(line.account.id).map { it.id }.toSet()
            repo.adjustBalance(line.account.id, t, record)
            val added = repo.accountTxns(line.account.id).filter { it.id !in before }
            val msg = tr("Balance set to {0}", fmt.format(parsed!!)) +
                if (record) " · " + (if (increase) tr("{0} recorded as income", fmt.format(kotlin.math.abs(diff))) else tr("{0} recorded as an expense", fmt.format(kotlin.math.abs(diff)))) else ""
            onMain { toaster.show(msg) { repo.moneyIo { added.forEach { repo.delete(it) } } } }
        }
    }

    Sheet(tr("Adjust balance"), onDismiss, bottom = {
        if (!asking) PrimaryButton(tr("Continue"), { if (target != null && diff != 0L) asking = true }, enabled = target != null && diff != 0L)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("No, just fix it"), { finish(false) }, Modifier.weight(1f))
            PrimaryButton(tr("Yes, record it"), { finish(true) }, Modifier.weight(1f))
        }
    }) {
        if (!asking) {
            Text(tr("Type what the statement or bank app shows now. Current {0}: {1}.", currentLabel, fmt.format(shown)), color = F.muted, fontSize = 13.sp)
            Gap(8.dp)
            MoneyTextField(if (card) tr("New balance payable") else tr("New balance"), text, { text = it }, keyboard = KeyboardType.Decimal,
                placeholder = fmt.toInput(kotlin.math.abs(shown)))
        } else {
            Text(
                tr("The {0} changes from {1} to {2}. Would you like to record the {3} difference as {4}?", currentLabel, fmt.format(shown), fmt.format(parsed ?: 0),
                    fmt.format(kotlin.math.abs(diff)), if (increase) tr("income") else tr("an expense")),
                color = F.ink, fontSize = 15.sp,
            )
            Gap(6.dp)
            Text(tr("If not, the difference is only registered on this account and stays out of your reports."), color = F.muted, fontSize = 13.sp)
        }
    }
}
