package app.moneytracker.ui.fresh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.ChoiceChip
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.MoneyTextField
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.SecondaryButton
import app.moneytracker.designsystem.fresh.SectionLabel
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.accounts.AccountActionsModel
import app.moneytracker.ui.accounts.BalanceChange
import app.moneytracker.ui.accounts.billDue
import app.moneytracker.ui.accounts.billDueDate
import app.moneytracker.ui.accounts.cardPayment
import app.moneytracker.ui.accounts.owed
import app.moneytracker.ui.accounts.parseBalance
import kotlin.math.abs
import kotlinx.datetime.LocalDate

/** Pay a card bill: amount, from which account and when; saved as a transfer, with Undo. */
@Composable
internal fun PaySheet(line: AccountLine, fmt: CurrencyFormat, today: LocalDate, sources: List<Account>, actions: AccountActionsModel, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val a = line.account
    var amount by remember { mutableStateOf(fmt.toInput(if (line.billDue > 0) line.billDue else line.owed)) }
    var from by remember { mutableStateOf(sources.firstOrNull()?.id) }
    var date by remember { mutableStateOf(today) }
    val value = fmt.parse(amount)?.takeIf { it > 0 }
    val fromName = sources.firstOrNull { it.id == from }?.name

    fun pay() {
        val v = value ?: return
        if (from == null) return
        val payment = cardPayment(a, from, date, v)
        val name = fromName.orEmpty()
        onDismiss()
        actions.pay(payment) { id ->
            val left = (line.billDue - v).coerceAtLeast(0)
            toaster.show(tr("Paid {0} from {1}", fmt.format(v), name) + if (left > 0) " · " + tr("{0} still due", fmt.format(left)) else "") {
                actions.undoPay(id)
            }
        }
    }

    Sheet(tr("Pay {0} bill", a.name), onDismiss, bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (value != null) tr("Pay {0}", fmt.format(value)) else tr("Enter an amount"), ::pay, enabled = value != null && from != null)
            SecondaryButton(tr("More options"), {
                onDismiss()
                nav.push(Screen.EditTxn(preset = cardPayment(a, from, date, value ?: 0).copy(accountId = from ?: a.cardPaymentAccountId.orEmpty())))
            })
        }
    }) {
        PayChoices(line, fmt, today, sources, PayInput(amount, value, from, date), onAmount = { amount = it }, onFrom = { from = it }) { date = it }
        Gap(8.dp)
        Text(tr("Recorded as a transfer from {0} to {1}, so it isn't counted as spending.", fromName ?: "…", a.name), color = F.muted, fontSize = 12.sp)
    }
}

/** What the Pay sheet has chosen so far: the typed amount (and its value once it reads as one), the account and the date. */
private class PayInput(val amount: String, val value: Long?, val from: String?, val date: LocalDate)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayChoices(
    line: AccountLine, fmt: CurrencyFormat, today: LocalDate, sources: List<Account>, input: PayInput,
    onAmount: (String) -> Unit, onFrom: (String) -> Unit, onDate: (LocalDate) -> Unit,
) {
    val due = billDueDate(line.account, today)?.takeIf { it > today }
    MoneyTextField(tr("Amount"), input.amount, onAmount, keyboard = KeyboardType.Decimal)
    Gap(4.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (line.billDue > 0) ChoiceChip(tr("Full bill {0}", fmt.format(line.billDue)), input.value == line.billDue) { onAmount(fmt.toInput(line.billDue)) }
        if (line.owed != line.billDue && line.owed > 0) ChoiceChip(tr("Everything owed {0}", fmt.format(line.owed)), input.value == line.owed) { onAmount(fmt.toInput(line.owed)) }
    }
    Gap(8.dp)
    SectionLabel(tr("Pay from"))
    if (sources.isEmpty()) Text(tr("Add a bank or cash account to pay from."), color = F.muted, fontSize = 13.sp)
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { s -> ChoiceChip(s.name, s.id == input.from) { onFrom(s.id) } }
    }
    Gap(8.dp)
    SectionLabel(tr("Date"))
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip(tr("Today, {0}", today.short()), input.date == today) { onDate(today) }
        if (due != null) ChoiceChip(tr("Due date, {0}", due.short()), input.date == due) { onDate(due) }
    }
}

/** Correct the balance: type the real figure, then choose whether the difference counts as income/expense; with Undo. */
@Composable
internal fun AdjustSheet(line: AccountLine, fmt: CurrencyFormat, actions: AccountActionsModel, onDismiss: () -> Unit) {
    val toaster = LocalToaster.current
    var text by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val change = BalanceChange(line, parseBalance(text, fmt))
    val currentLabel = if (change.isCard) tr("balance payable") else tr("balance")

    fun finish(record: Boolean) {
        val target = change.target ?: return
        val typed = change.typed ?: return
        onDismiss()
        val diff = fmt.format(abs(change.diff))
        val msg = tr("Balance set to {0}", fmt.format(typed)) +
            if (record) " · " + (if (change.increase) tr("{0} recorded as income", diff) else tr("{0} recorded as an expense", diff)) else ""
        actions.adjustBalance(target, record) { added -> toaster.show(msg) { actions.undoAdjust(added) } }
    }

    Sheet(tr("Adjust balance"), onDismiss, bottom = {
        if (!asking) PrimaryButton(tr("Continue"), { if (change.canContinue) asking = true }, enabled = change.canContinue)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("No, just fix it"), { finish(false) }, Modifier.weight(1f))
            PrimaryButton(tr("Yes, record it"), { finish(true) }, Modifier.weight(1f))
        }
    }) {
        if (!asking) {
            Text(tr("Type what the statement or bank app shows now. Current {0}: {1}.", currentLabel, fmt.format(change.shown)), color = F.muted, fontSize = 13.sp)
            Gap(8.dp)
            MoneyTextField(if (change.isCard) tr("New balance payable") else tr("New balance"), text, { text = it }, keyboard = KeyboardType.Decimal,
                placeholder = fmt.toInput(abs(change.shown)))
        } else {
            Text(
                tr("The {0} changes from {1} to {2}. Would you like to record the {3} difference as {4}?", currentLabel, fmt.format(change.shown),
                    fmt.format(change.typed ?: 0), fmt.format(abs(change.diff)), if (change.increase) tr("income") else tr("an expense")),
                color = F.ink, fontSize = 14.sp,
            )
            Gap(6.dp)
            Text(tr("If not, the difference is only registered on this account and stays out of your reports."), color = F.muted, fontSize = 13.sp)
        }
    }
}
