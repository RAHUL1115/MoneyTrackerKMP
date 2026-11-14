package app.moneytracker.accounts.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.accounts.AccountForm
import app.moneytracker.accounts.rememberAccountEditModel
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DangerColor
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.Picker
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.entry.classic.save

@Composable
fun EditAccountScreen(accountId: String?) {
    val nav = LocalNavigator.current
    val mainFmt = LocalCurrency.current
    val model = rememberAccountEditModel(accountId)
    val groups by model.groups.collectAsState()
    val accounts by model.accounts.collectAsState()
    val currencies by model.currencies.collectAsState()
    var form by rememberSaveable(stateSaver = AccountForm.Saver) { mutableStateOf(model.blankForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(accounts, accountId) { model.filled(form, mainFmt)?.let { form = it } }

    Column(Modifier.fillMaxSize().background(SurfaceColor)) {
        BackTopBar(if (accountId == null) tr("Add account") else tr("Edit account"), nav::pop) {
            if (accountId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, tr("Delete account")) }
        }
        HorizontalDivider(color = DividerColor)
        AccountFormFields(form, { form = it }, groups, accounts.filter { it.id != accountId }, currencies, mainFmt, error) {
            error = model.save(form, mainFmt) { nav.pop() }
        }
    }

    if (confirmDelete && accountId != null) {
        DeleteAccountDialog(onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            model.delete {
                nav.pop()
                if (nav.stack.lastOrNull() == Screen.AccountDetail(accountId)) nav.pop()
            }
        }
    }
}

@Composable
private fun AccountFormFields(
    form: AccountForm, onChange: (AccountForm) -> Unit, groups: List<AccountGroup>, others: List<Account>, currencies: List<Currency>,
    mainFmt: CurrencyFormat, error: String?, onSave: () -> Unit,
) {
    val group = groups.firstOrNull { it.id == form.groupId }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Picker(tr("Group"), groups, group, { it.name }, { onChange(form.copy(groupId = it.id)) }, Modifier.fillMaxWidth())
        OutlinedTextField(form.name, { onChange(form.copy(name = it)) }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (group?.type?.isCreditCard == true || group?.type?.isDebitCard == true) CardLinkFields(form, onChange, group.type.isCreditCard, others)
        if (currencies.isNotEmpty()) {
            Picker(tr("Currency"), listOf<String?>(null) + currencies.map { it.code }, form.currency,
                { it ?: tr("Main currency ({0})", mainFmt.symbol) }, { onChange(form.copy(currency = it)) }, Modifier.fillMaxWidth())
        }
        if (group?.type?.isCreditCard == true) CreditCardFields(form, onChange)
        OutlinedTextField(form.memo, { onChange(form.copy(memo = it)) }, label = { Text(tr("Memo (optional)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        BankMessageFields(form, onChange)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Box(
            Modifier.fillMaxWidth().height(52.dp).background(Accent, SaveShape).clip(SaveShape).clickable(role = Role.Button, onClick = onSave),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Save"), color = Color.White, fontSize = 17.sp) }
    }
}

private val SaveShape = RoundedCornerShape(6.dp)

/** Cards: the billing day (credit cards) and the account that pays the bill or that the debit card draws on. */
@Composable
private fun CardLinkFields(form: AccountForm, onChange: (AccountForm) -> Unit, credit: Boolean, others: List<Account>) {
    if (credit) {
        OutlinedTextField(form.settlementDay, { onChange(form.copy(settlementDay = it.filter(Char::isDigit).take(2))) },
            label = { Text(tr("Statement / billing day (1–31)")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    Picker(
        if (credit) tr("Payment account") else tr("Linked bank account"),
        others, others.firstOrNull { it.id == form.paymentAccountId }, { it.name },
        { onChange(form.copy(paymentAccountId = it.id)) }, Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CreditCardFields(form: AccountForm, onChange: (AccountForm) -> Unit) {
    OutlinedTextField(form.paymentDay, { onChange(form.copy(paymentDay = it.filter(Char::isDigit).take(2))) }, label = { Text(tr("Payment day (1–31)")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    SwitchRow(tr("Auto-pay"), form.autoPay) { onChange(form.copy(autoPay = it)) }
    OutlinedTextField(form.limitText, { onChange(form.copy(limitText = it)) }, label = { Text(tr("Monthly spending limit (optional)")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
    // Monthly card usage: spend needed each month, e.g. to keep a card's benefits.
    OutlinedTextField(form.targetText, { onChange(form.copy(targetText = it)) }, label = { Text(tr("Monthly usage target (optional)")) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
    if (form.targetText.isNotBlank()) {
        Picker(tr("Usage is checked against"), listOf(1, 2), form.targetType, { if (it == 2) tr("3 months average") else tr("Previous month") },
            { onChange(form.copy(targetType = it)) }, Modifier.fillMaxWidth())
    }
}

/** SMS link for this account: detected messages that match are filed here. Then the totals switches. */
@Composable
private fun BankMessageFields(form: AccountForm, onChange: (AccountForm) -> Unit) {
    Text(tr("Bank messages"), Modifier.padding(top = 4.dp), color = SubtleText, fontSize = 13.sp)
    OutlinedTextField(form.smsMatch, { onChange(form.copy(smsMatch = it)) }, label = { Text(tr("Text in the message (e.g. card digits x1234)")) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.smsSender, { onChange(form.copy(smsSender = it)) }, label = { Text(tr("Sender number or ID (optional)")) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    SwitchRow(tr("Include in totals"), form.includeInTotals) { onChange(form.copy(includeInTotals = it)) }
    SwitchRow(tr("Transfers in count as expense"), form.transferAsExpense) { onChange(form.copy(transferAsExpense = it)) }
    Text(tr("Turn this on for savings, investments or loans so money moved there shows in your spending."),
        fontSize = 13.sp, color = SubtleText)
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Delete account?")) },
        text = { Text(tr("The account is hidden. Its transactions stay in your history and reports.")) },
        confirmButton = { TextButton(onClick = onDelete) { Text(tr("Delete"), color = DangerColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
