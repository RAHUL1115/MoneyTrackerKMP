package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.classic.rememberPeriod
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.app.Screen
import app.moneytracker.core.tr
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.designsystem.classic.LineChart
import app.moneytracker.designsystem.classic.MonthPickerDialog
import app.moneytracker.designsystem.classic.PairBarChart
import app.moneytracker.designsystem.classic.label
import app.moneytracker.designsystem.classic.long
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.EmptyCard
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.HeaderTextTile
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.PeriodStepper
import app.moneytracker.designsystem.fresh.SectionLabel
import app.moneytracker.designsystem.fresh.Segmented
import app.moneytracker.designsystem.fresh.SubScreen
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.Category
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.ui.accounts.AccountHistory
import app.moneytracker.ui.accounts.PeriodSum
import app.moneytracker.ui.accounts.billDue
import app.moneytracker.ui.accounts.billDueDate
import app.moneytracker.ui.accounts.cycles
import app.moneytracker.ui.accounts.depositTotal
import app.moneytracker.ui.accounts.owed
import app.moneytracker.ui.accounts.paySources
import app.moneytracker.ui.accounts.periodSums
import app.moneytracker.ui.accounts.rememberAccountActionsModel
import app.moneytracker.ui.accounts.rememberAccountDetailModel
import app.moneytracker.ui.accounts.withdrawalTotal
import kotlinx.datetime.LocalDate

/** Side padding of the entry and period rows inside their cards; the dividers between rows use the same inset. */
private val ROW_INSET = 16.dp

private enum class DetailTab(val label: String) { ENTRIES("Entries"), MONTHLY("Monthly"), YEARLY("Yearly") }

private enum class AccountDetailSheet { PAY, ADJUST }

/** The shown period and how to change it. */
private class AccountPeriodChoice(val ym: YearMonth, val range: DateRange, val startDay: Int, val today: LocalDate, val show: (YearMonth) -> Unit)

/** Names to show an account's entries with. */
private class AccountEntryNames(val accounts: Map<String, String>, val categories: Map<String, Category>)

@Composable
internal fun AccountDetailBody(accountId: String) {
    val today = LocalRepo.current.today()
    val model = rememberAccountDetailModel(accountId)
    val actions = rememberAccountActionsModel(accountId)
    val summary by model.summary.collectAsState()
    val startDay by model.monthStartDay.collectAsState()
    val accountNames by model.accountNames.collectAsState()
    val catById by model.categoryById.collectAsState()
    val accounts by model.accounts.collectAsState()
    val groups by model.groups.collectAsState()
    var periodKey by rememberPeriod()
    val ym = YearMonth.fromKey(periodKey)
    val range = Periods.month(ym, startDay)
    val history by remember(model, range) { model.history(range) }.collectAsState(AccountHistory.Empty)
    val line = model.lineIn(summary)
    val fmt = line?.account?.currency?.let { LocalCurrencies.current[it]?.format } ?: LocalCurrency.current
    var sheet by remember { mutableStateOf<AccountDetailSheet?>(null) }

    val period = AccountPeriodChoice(ym, range, startDay, today) { periodKey = it.key }
    AccountDetailContent(accountId, line, loaded = summary != null, history, period, AccountEntryNames(accountNames, catById), fmt) { sheet = it }

    if (line != null) when (sheet) {
        AccountDetailSheet.PAY -> PaySheet(line, fmt, today, paySources(line.account, accounts, groups), actions) { sheet = null }
        AccountDetailSheet.ADJUST -> AdjustSheet(line, fmt, actions) { sheet = null }
        null -> {}
    }
}

@Composable
private fun AccountDetailContent(
    accountId: String, line: AccountLine?, loaded: Boolean, history: AccountHistory, period: AccountPeriodChoice, names: AccountEntryNames,
    fmt: CurrencyFormat, onSheet: (AccountDetailSheet) -> Unit,
) {
    val nav = LocalNavigator.current
    var tab by rememberSaveable { mutableStateOf(DetailTab.ENTRIES) }
    var statement by rememberSaveable { mutableStateOf(false) }
    val acct = line?.account
    SubScreen(acct?.name ?: tr("Account"), nav::pop, actions = {
        if (acct != null) HeaderTextTile(tr("Edit")) { nav.push(Screen.EditAccount(accountId)) }
    }) {
        if (line == null) {
            if (loaded) EmptyCard(tr("This account was deleted or can't be found."))
            return@SubScreen
        }
        val isCredit = line.group.type.isCreditCard
        AccountHero(line, fmt, history, period.today)
        ActionRow(line, period.today, statement, onStatement = { statement = !statement }, onSheet)
        if (isCredit && statement) StatementsCard(line, history, fmt, period.today)
        NotesCard(line, names.accounts, period.today) { nav.push(Screen.EditAccount(accountId)) }
        Segmented(DetailTab.entries.map { tr(it.label) }, tab.ordinal, { tab = DetailTab.entries[it] })
        when (tab) {
            DetailTab.ENTRIES -> EntriesTab(accountId, history, period, names, fmt)
            DetailTab.MONTHLY, DetailTab.YEARLY -> PeriodsTab(history, period, yearly = tab == DetailTab.YEARLY, fmt) { tab = it }
        }
    }
}

/** Actions: cards pay their bill; every account can add, move or correct money. */
@Composable
private fun ActionRow(line: AccountLine, today: LocalDate, statement: Boolean, onStatement: () -> Unit, onSheet: (AccountDetailSheet) -> Unit) {
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val accountId = line.account.id
    val addEntry = { nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.EXPENSE, today, accountId, amount = 0))) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (line.group.type.isCreditCard) {
            AccountActionTile(Glyph.Card, if (line.billDue > 0) tr("Pay bill") else tr("Paid ✓"), primary = line.billDue > 0) {
                if (line.owed > 0) onSheet(AccountDetailSheet.PAY)
                else toaster.show(tr("Nothing to pay. The next statement closes on {0}.", Periods.cardCycle(today, line.account.statementDay).endInclusive.short()))
            }
            AccountActionTile(Glyph.Add, tr("Add entry"), onClick = addEntry)
            AccountActionTile(Glyph.Edit, tr("Adjust"), onClick = { onSheet(AccountDetailSheet.ADJUST) })
            AccountActionTile(Glyph.Statement, tr("Statement"), selected = statement, onClick = onStatement)
        } else {
            AccountActionTile(Glyph.Add, tr("Add entry"), primary = true, onClick = addEntry)
            AccountActionTile(Glyph.Swap, tr("Transfer"), onClick = {
                nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.TRANSFER, today, accountId, amount = 0)))
            })
            AccountActionTile(Glyph.Edit, tr("Adjust balance"), onClick = { onSheet(AccountDetailSheet.ADJUST) })
        }
    }
}

@Composable
private fun StatementsCard(line: AccountLine, history: AccountHistory, fmt: CurrencyFormat, today: LocalDate) = FCard {
    val acct = line.account
    SectionLabel(tr("Statements · billing day {0} · pay by day {1}", acct.statementDay, acct.dueDay), Modifier.padding(bottom = 4.dp))
    val due = billDueDate(acct, today)
    cycles(history.history, acct.statementDay, today, 6).forEachIndexed { i, c ->
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
                Text(c.range.label(), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(status, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Text(fmt.format(c.charged), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money)
        }
    }
}

/** Settings worth knowing at a glance. */
private fun accountNotes(line: AccountLine, accountName: Map<String, String>, today: LocalDate): List<String> = buildList {
    val acct = line.account
    if (line.group.type.isCreditCard) add(cardBillNote(acct, accountName, today))
    if (line.group.type.isDebitCard) acct.cardPaymentAccountId?.let { accountName[it] }?.let { add(tr("Spends come out of {0}", it)) }
    if (acct.smsSender.isNotBlank() || acct.smsMatch.isNotBlank()) add(smsRuleNote(acct))
    if (!acct.includeInTotals) add(tr("Not counted in net worth"))
    if (acct.transferAsExpense) add(tr("Money moved here counts as spending"))
    if (acct.hidden) add(tr("Hidden from lists and pickers"))
}

/** When and from where a card's bill is paid. */
private fun cardBillNote(acct: Account, accountName: Map<String, String>, today: LocalDate): String {
    val from = acct.cardPaymentAccountId?.let { accountName[it] }
    val due = billDueDate(acct, today)
    return listOfNotNull(
        due?.let { tr("Pay by {0}", it.short()) },
        from?.let { tr("from {0}", it) },
        if (acct.cardAutoPay) tr("auto-pay on") else tr("auto-pay off"),
    ).joinToString(" · ")
}

private fun smsRuleNote(acct: Account): String = tr("Bank SMS rule: {0}", listOfNotNull(
    acct.smsSender.ifBlank { null }?.let { tr("sender {0}", it) }, acct.smsMatch.ifBlank { null }?.let { tr("text {0}", it) },
).joinToString(", "))

@Composable
private fun NotesCard(line: AccountLine, accountName: Map<String, String>, today: LocalDate, onEdit: () -> Unit) {
    val notes = accountNotes(line, accountName, today)
    if (notes.isNotEmpty()) Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(F.card).clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { notes.forEach { Text(it, color = F.ink, fontSize = 13.sp) } }
}

@Composable
private fun EntriesTab(accountId: String, history: AccountHistory, period: AccountPeriodChoice, names: AccountEntryNames, fmt: CurrencyFormat) {
    val nav = LocalNavigator.current
    var monthPicker by remember { mutableStateOf(false) }
    val ym = period.ym
    if (monthPicker) MonthPickerDialog(ym, YearMonth.of(period.today), { monthPicker = false }) { period.show(it); monthPicker = false }
    PeriodStepper(ym.label(), { period.show(ym.plus(-1)) }, { period.show(ym.plus(1)) }, onLabel = { monthPicker = true },
        sub = if (period.startDay != 1) period.range.label() else null)
    val txns = history.entries
    val deposit = txns.depositTotal()
    val withdrawal = txns.withdrawalTotal()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountMiniTotal(tr("In"), fmt.format(deposit), F.income, Modifier.weight(1f))
        AccountMiniTotal(tr("Out"), fmt.format(withdrawal), F.expense, Modifier.weight(1f))
        AccountMiniTotal(tr("Change"), fmt.format(deposit - withdrawal, showSign = true), F.ink, Modifier.weight(1f))
    }
    if (txns.isEmpty()) EmptyCard(tr("No entries in {0}.", ym.label()), tr("Add entry")) {
        nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.EXPENSE, period.today, accountId, amount = 0)))
    }
    txns.groupBy { it.date }.forEach { (date, day) ->
        FListCard {
            Row(Modifier.fillMaxWidth().padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(date.long(), Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(fmt.format(day.sumOf { it.signedAccountValue }, showSign = true), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, style = F.money)
            }
            day.forEachIndexed { i, t ->
                if (i > 0) Divider(ROW_INSET)
                AccountEntryRow(t, names.accounts, names.categories, fmt, history.running[t.id], ROW_INSET, extraBottom = if (i == day.lastIndex) 4.dp else 0.dp) {
                    nav.push(Screen.EditTxn(txnId = t.id))
                }
            }
        }
    }
}

@Composable
private fun PeriodsTab(history: AccountHistory, period: AccountPeriodChoice, yearly: Boolean, fmt: CurrencyFormat, onTab: (DetailTab) -> Unit) {
    val ym = period.ym
    if (!yearly) PeriodStepper(ym.year.toString(), { period.show(ym.plus(-12)) }, { period.show(ym.plus(12)) })
    val sums = periodSums(history.history, ym, yearly, period.startDay, period.today)
    val labels = sums.map { if (yearly) it.label.takeLast(2) else it.label.take(1) }
    FCard(padding = 0.dp) {
        Text(tr("Balance"), Modifier.padding(start = 16.dp, top = 14.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        LineChart(sums.map { it.balance }, labels, color = F.accent)
        Text(tr("In / out"), Modifier.padding(start = 16.dp, top = 4.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        PairBarChart(sums.map { it.deposit }, sums.map { it.withdrawal }, labels, colorA = F.income, colorB = F.expense)
        Gap(8.dp)
    }
    FListCard {
        sums.asReversed().forEachIndexed { i, p ->
            if (i > 0) Divider(ROW_INSET)
            PeriodRow(p, fmt) {
                // A month opens its entries; a year opens its months.
                if (p.month != null) { period.show(p.month); onTab(DetailTab.ENTRIES) }
                else { period.show(YearMonth(p.label.toInt(), 1)); onTab(DetailTab.MONTHLY) }
            }
        }
    }
}

@Composable
private fun PeriodRow(p: PeriodSum, fmt: CurrencyFormat, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ROW_INSET, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.label, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(p.range.label(), color = F.muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("+" + fmt.format(p.deposit), color = F.income, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money)
            Text(fmt.format(-(p.withdrawal)), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money)
            Text(tr("Balance {0}", fmt.format(p.balance)), color = F.muted, fontSize = 11.sp, style = F.money)
        }
    }
}
