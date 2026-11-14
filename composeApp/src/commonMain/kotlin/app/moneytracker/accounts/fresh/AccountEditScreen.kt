package app.moneytracker.accounts.fresh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.accounts.AccountForm
import app.moneytracker.accounts.rememberAccountEditModel
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.tr
import app.moneytracker.designsystem.fresh.Confirm
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.FRow
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.MoneySwitchRow
import app.moneytracker.designsystem.fresh.MoneyTextField
import app.moneytracker.designsystem.fresh.Pill
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.SectionLabel
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SheetOption
import app.moneytracker.designsystem.fresh.SheetOptionGroup
import app.moneytracker.designsystem.fresh.SubScreen
import app.moneytracker.designsystem.fresh.pick
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.entry.fresh.accountName

/** The form's pick-one sheets. */
private enum class AccountEditSheet { CURRENCY, PAY_FROM, BASIS }

/** Add or edit an account: name, group, currency, card billing, totals, bank-message matching; delete with confirm. */
@Composable
internal fun AccountEditBody(accountId: String?) {
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val mainFmt = LocalCurrency.current
    val model = rememberAccountEditModel(accountId)
    val groups by model.groups.collectAsState()
    val accounts by model.accounts.collectAsState()
    val currencies by model.currencies.collectAsState()
    var form by rememberSaveable(stateSaver = AccountForm.Saver) { mutableStateOf(model.blankForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(accounts, accountId) { model.filled(form, mainFmt)?.let { form = it } }

    fun save() {
        error = model.save(form, mainFmt) { account ->
            nav.pop()
            toaster.show(if (accountId == null) tr("{0} added", account.name) else tr("{0} saved", account.name))
        }
    }
    val choices = AccountFormChoices(groups, accounts.filter { it.id != accountId }, currencies, mainFmt)
    AccountEditContent(accountId == null, form, { form = it }, choices, error, { error = null }, ::save) { confirmDelete = true }

    if (confirmDelete && accountId != null) Confirm(
        tr("Delete {0}?", form.name.ifBlank { tr("account") }),
        tr("The account is hidden. Its transactions stay in your history and reports. You can restore it from Money › Manage accounts › Deleted accounts."),
        tr("Delete"), { confirmDelete = false },
    ) {
        val name = form.name
        model.delete {
            nav.pop()
            if (nav.stack.lastOrNull() == Screen.AccountDetail(accountId)) nav.pop()
            toaster.show(tr("{0} deleted", name)) { model.restore() }
        }
    }
}

/** What the form's pickers offer: groups, other accounts (to pay a card from) and currencies. */
private class AccountFormChoices(val groups: List<AccountGroup>, val others: List<Account>, val currencies: List<Currency>, val mainFmt: CurrencyFormat) {
    fun currencyLabel(code: String?) = code?.let { c -> currencies.firstOrNull { it.code == c }?.let { "${it.code} ${it.symbol}" } ?: c }
        ?: tr("Main currency ({0})", mainFmt.symbol)

    fun accountName(id: String?): String = others.firstOrNull { it.id == id }?.name ?: tr("Choose")
}

@Composable
private fun AccountEditContent(
    isNew: Boolean, form: AccountForm, onChange: (AccountForm) -> Unit, choices: AccountFormChoices, error: String?, clearError: () -> Unit,
    onSave: () -> Unit, onDelete: () -> Unit,
) {
    val nav = LocalNavigator.current
    var sheet by remember { mutableStateOf<AccountEditSheet?>(null) }
    val type = choices.groups.firstOrNull { it.id == form.groupId }?.type
    val credit = type?.isCreditCard == true
    SubScreen(if (isNew) tr("Add account") else tr("Edit account"), nav::pop, bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = F.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            PrimaryButton(if (isNew) tr("Add account") else tr("Save account"), onSave)
        }
    }) {
        BasicsCard(form, onChange, choices, clearError) { sheet = AccountEditSheet.CURRENCY }
        if (credit) CardBillingCard(form, onChange, choices) { sheet = it }
        if (type?.isDebitCard == true) FListCard {
            FRow(tr("Linked bank account"), tr("Card spends come out of this account"), value = choices.accountName(form.paymentAccountId),
                valueColor = F.accent, onClick = { sheet = AccountEditSheet.PAY_FROM }, inset = LIST_INSET, extraTop = 4.dp, extraBottom = 4.dp)
        }
        SwitchesCard(form, onChange, credit)
        FCard {
            SectionLabel(tr("Bank messages · helps SMS detection pick this account"), Modifier.padding(bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyTextField(tr("Text in message"), form.smsMatch, { onChange(form.copy(smsMatch = it)) }, placeholder = tr("e.g. card digits x1234"))
                MoneyTextField(tr("Sender ID"), form.smsSender, { onChange(form.copy(smsSender = it)) }, placeholder = tr("Optional"))
            }
        }
        // Destructive, so red text alone rather than a button.
        if (!isNew) Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(F.iconShape).clickable(role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Delete account"), color = F.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
    }
    sheet?.let { s -> EditSheetFor(s, form, onChange, choices, credit) { sheet = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasicsCard(form: AccountForm, onChange: (AccountForm) -> Unit, choices: AccountFormChoices, clearError: () -> Unit, onCurrency: () -> Unit) {
    // The fields keep the card's padding; the Currency row spans the card so its highlight does too.
    FListCard {
        Column(Modifier.fillMaxWidth().padding(start = F.cardPadding, end = F.cardPadding, top = F.cardPadding), verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
            MoneyTextField(tr("Name"), form.name, { onChange(form.copy(name = it)); clearError() })
            Column {
                Text(tr("Group").uppercase(), Modifier.padding(start = 4.dp, bottom = 8.dp), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.groups.forEach { g ->
                        Pill(g.name, g.id == form.groupId, { onChange(form.copy(groupId = g.id)); clearError() }, role = Role.RadioButton)
                    }
                }
            }
        }
        if (choices.currencies.isNotEmpty()) {
            Gap(FIELD_GAP)
            FRow(tr("Currency"), value = choices.currencyLabel(form.currency), onClick = onCurrency, inset = F.cardPadding)
        }
        Gap(FIELD_GAP)
        Column(Modifier.fillMaxWidth().padding(start = F.cardPadding, end = F.cardPadding, bottom = F.cardPadding)) {
            MoneyTextField(tr("Memo (optional)"), form.memo, { onChange(form.copy(memo = it)) }, placeholder = tr("e.g. Lifetime free, 5% on food"), singleLine = false)
        }
    }
}

@Composable
private fun CardBillingCard(form: AccountForm, onChange: (AccountForm) -> Unit, choices: AccountFormChoices, onSheet: (AccountEditSheet) -> Unit) {
    // The fields keep the card's padding; the rows under them span the card so their highlight does too.
    FListCard {
        Column(Modifier.fillMaxWidth().padding(start = F.cardPadding, end = F.cardPadding, top = F.cardPadding)) {
            SectionLabel(tr("Card billing"), Modifier.padding(bottom = 8.dp))
            CardBillingFields(form, onChange)
            Gap(4.dp)
        }
        val targetRow = form.targetText.isNotBlank()
        FRow(tr("Bill paid from"), value = choices.accountName(form.paymentAccountId), valueColor = F.accent, onClick = { onSheet(AccountEditSheet.PAY_FROM) },
            inset = F.cardPadding, extraBottom = if (targetRow) 0.dp else F.cardPadding)
        if (targetRow) FRow(tr("Target compared with"), value = if (form.targetType == 2) tr("3 months average") else tr("Previous month"),
            valueColor = F.accent, onClick = { onSheet(AccountEditSheet.BASIS) }, inset = F.cardPadding, extraBottom = F.cardPadding)
    }
}

/** Statement and payment days, monthly limit and usage target, two to a row. */
@Composable
private fun CardBillingFields(form: AccountForm, onChange: (AccountForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MoneyTextField(tr("Statement day"), form.settlementDay, { onChange(form.copy(settlementDay = it.filter(Char::isDigit).take(2))) },
                Modifier.weight(1f), keyboard = KeyboardType.Number)
            MoneyTextField(tr("Payment day"), form.paymentDay, { onChange(form.copy(paymentDay = it.filter(Char::isDigit).take(2))) }, Modifier.weight(1f),
                keyboard = KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MoneyTextField(tr("Monthly limit"), form.limitText, { onChange(form.copy(limitText = it)) }, Modifier.weight(1f),
                placeholder = tr("Optional"), keyboard = KeyboardType.Decimal)
            MoneyTextField(tr("Usage target"), form.targetText, { onChange(form.copy(targetText = it)) }, Modifier.weight(1f),
                placeholder = tr("Optional"), keyboard = KeyboardType.Decimal)
        }
    }
}

/** Switches as a list: at least 58 tall each, a line between them; each row pads itself so its highlight spans the card. */
@Composable
private fun SwitchesCard(form: AccountForm, onChange: (AccountForm) -> Unit, credit: Boolean) {
    val slot = Modifier.heightIn(min = 58.dp)
    FListCard(verticalPadding = 4.dp) {
        MoneySwitchRow(tr("Include in totals"), tr("Counts toward net worth"), form.includeInTotals, slot, LIST_INSET) { onChange(form.copy(includeInTotals = it)) }
        Divider(LIST_INSET)
        MoneySwitchRow(tr("Transfers in count as expense"),
            tr("Turn this on for savings, investments or loans so money moved there shows in your spending."), form.transferAsExpense, slot, LIST_INSET) {
            onChange(form.copy(transferAsExpense = it))
        }
        Divider(LIST_INSET)
        MoneySwitchRow(tr("Hide from lists"), tr("History and balance stay"), form.hidden, slot, LIST_INSET) { onChange(form.copy(hidden = it)) }
        if (credit) {
            Divider(LIST_INSET)
            MoneySwitchRow(tr("Bill is auto-paid"), null, form.autoPay, slot, LIST_INSET) { onChange(form.copy(autoPay = it)) }
        }
    }
}

@Composable
private fun EditSheetFor(sheet: AccountEditSheet, form: AccountForm, onChange: (AccountForm) -> Unit, choices: AccountFormChoices, credit: Boolean, onClose: () -> Unit) {
    fun pick(f: AccountForm) { onChange(f); onClose() }
    when (sheet) {
        AccountEditSheet.CURRENCY -> Sheet(tr("Currency"), onClose) {
            SheetOptionGroup {
                SheetOption(tr("Main currency ({0})", choices.mainFmt.symbol), form.currency == null) { pick(form.copy(currency = null)) }
                choices.currencies.forEach { c -> SheetOption("${c.code} ${c.symbol}", form.currency == c.code) { pick(form.copy(currency = c.code)) } }
            }
        }
        AccountEditSheet.PAY_FROM -> Sheet(if (credit) tr("Bill paid from") else tr("Linked bank account"), onClose) {
            SheetOptionGroup {
                SheetOption(tr("None"), form.paymentAccountId == null) { pick(form.copy(paymentAccountId = null)) }
                choices.others.filter { !it.hidden || it.id == form.paymentAccountId }.forEach { a ->
                    SheetOption(a.name, a.id == form.paymentAccountId, subtitle = choices.groups.firstOrNull { it.id == a.groupId }?.name) {
                        pick(form.copy(paymentAccountId = a.id))
                    }
                }
            }
        }
        AccountEditSheet.BASIS -> Sheet(tr("Target compared with"), onClose) {
            SheetOptionGroup {
                SheetOption(tr("Previous month"), form.targetType == 1, subtitle = tr("Last billing cycle's spending")) { pick(form.copy(targetType = 1)) }
                SheetOption(tr("3 months average"), form.targetType == 2, subtitle = tr("Average of the last three cycles")) { pick(form.copy(targetType = 2)) }
            }
        }
    }
}

/** Side padding of the list rows (switches, linked account) inside their cards; dividers use the same inset. */
private val LIST_INSET = 16.dp

/** Space between the fields of the basics card. */
private val FIELD_GAP = 14.dp
