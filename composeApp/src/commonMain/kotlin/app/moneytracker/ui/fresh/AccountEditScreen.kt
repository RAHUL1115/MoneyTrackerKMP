package app.moneytracker.ui.fresh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add or edit an account: name, group, currency, card billing, totals, bank-message matching; delete with confirm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountEditBody(accountId: String?) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val currencies by repo.currencies.collectAsState(emptyList())
    val mainFmt = LocalCurrency.current

    var loaded by remember { mutableStateOf(accountId == null) }
    var name by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf<String?>(null) }
    var includeInTotals by remember { mutableStateOf(true) }
    var transferAsExpense by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
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
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accounts, accountId) {
        if (loaded) return@LaunchedEffect
        val a = accounts.firstOrNull { it.id == accountId } ?: return@LaunchedEffect
        name = a.name; groupId = a.groupId; includeInTotals = a.includeInTotals; transferAsExpense = a.transferAsExpense; hidden = a.hidden
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
    val credit = group?.type?.isCreditCard == true
    val debit = group?.type?.isDebitCard == true
    val others = accounts.filter { it.id != accountId }
    fun currencyLabel(code: String?) = code?.let { c -> currencies.firstOrNull { it.code == c }?.let { "${it.code} ${it.symbol}" } ?: c }
        ?: tr("Main currency ({0})", mainFmt.symbol)

    fun save() {
        val day = settlementDay.toIntOrNull()
        val payDay = paymentDay.toIntOrNull()
        error = when {
            groupId == null -> tr("Choose a group")
            name.isBlank() -> tr("Enter a name")
            credit && (day == null || day !in 1..31) -> tr("Billing day must be 1–31")
            credit && paymentDay.isNotBlank() && (payDay == null || payDay !in 1..31) -> tr("Payment day must be 1–31")
            else -> null
        }
        if (error != null) return
        val fmt = accountCurrency?.let { c -> currencies.firstOrNull { it.code == c }?.format } ?: mainFmt
        // Start from the stored account so fields this form doesn't show are kept.
        val base = accounts.firstOrNull { it.id == accountId } ?: Account(newId(), groupId!!, "", sortOrder = sortOrder)
        val account = base.copy(
            groupId = groupId!!, name = name.trim(), includeInTotals = includeInTotals, transferAsExpense = transferAsExpense, hidden = hidden,
            cardSettlementDay = if (credit) day else null, cardPaymentAccountId = if (credit || debit) paymentAccountId else null,
            currency = accountCurrency,
            cardLimit = if (credit) fmt.parse(limitText)?.takeIf { it > 0 } else null,
            memo = memo.trim(),
            cardPaymentDay = if (credit) payDay else null, cardAutoPay = credit && autoPay,
            usageTarget = if (credit) fmt.parse(targetText)?.takeIf { it > 0 } else null, usageTargetType = targetType,
            smsSender = smsSender.trim(), smsMatch = smsMatch.trim(),
        )
        scope.launch {
            withContext(repo.io) { repo.saveAccount(account) }
            nav.pop()
            toaster.show(if (accountId == null) tr("{0} added", account.name) else tr("{0} saved", account.name))
        }
    }

    SubScreen(if (accountId == null) tr("Add account") else tr("Edit account"), nav::pop, bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = F.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            PrimaryButton(if (accountId == null) tr("Add account") else tr("Save account"), ::save)
        }
    }) {
        FCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MoneyTextField(tr("Name"), name, { name = it; error = null })
                Column {
                    Text(tr("Group").uppercase(), Modifier.padding(start = 4.dp, bottom = 8.dp), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { g -> ChoiceChip(g.name, g.id == groupId) { groupId = g.id; error = null } }
                    }
                }
                if (currencies.isNotEmpty()) FRow(tr("Currency"), value = currencyLabel(accountCurrency), onClick = { sheet = "currency" })
                MoneyTextField(tr("Memo (optional)"), memo, { memo = it }, placeholder = tr("e.g. Lifetime free, 5% on food"), singleLine = false)
            }
        }
        if (credit) FCard {
            SectionLabel(tr("Card billing"), Modifier.padding(bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoneyTextField(tr("Statement day"), settlementDay, { settlementDay = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                    MoneyTextField(tr("Payment day"), paymentDay, { paymentDay = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f),
                        placeholder = tr("Optional"), keyboard = KeyboardType.Number)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoneyTextField(tr("Monthly limit"), limitText, { limitText = it }, Modifier.weight(1f), placeholder = tr("Optional"), keyboard = KeyboardType.Decimal)
                    MoneyTextField(tr("Usage target"), targetText, { targetText = it }, Modifier.weight(1f), placeholder = tr("Optional"), keyboard = KeyboardType.Decimal)
                }
            }
            Gap(4.dp)
            FRow(tr("Bill paid from"), value = others.firstOrNull { it.id == paymentAccountId }?.name ?: tr("Choose"), onClick = { sheet = "payFrom" })
            if (targetText.isNotBlank()) FRow(tr("Target compared with"), value = if (targetType == 2) tr("3 months average") else tr("Previous month"),
                onClick = { sheet = "basis" })
        }
        if (debit) FCard(padding = 4.dp) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                FRow(tr("Linked bank account"), tr("Card spends come out of this account"), value = others.firstOrNull { it.id == paymentAccountId }?.name ?: tr("Choose"),
                    onClick = { sheet = "payFrom" })
            }
        }
        FCard {
            MoneySwitchRow(tr("Include in totals"), tr("Counts toward net worth"), includeInTotals) { includeInTotals = it }
            MoneySwitchRow(tr("Transfers in count as expense"),
                tr("Turn this on for savings, investments or loans so money moved there shows in your spending."), transferAsExpense) { transferAsExpense = it }
            MoneySwitchRow(tr("Hide from lists"), tr("History and balance stay"), hidden) { hidden = it }
            if (credit) MoneySwitchRow(tr("Bill is auto-paid"), null, autoPay) { autoPay = it }
        }
        FCard {
            SectionLabel(tr("Bank messages · helps SMS detection pick this account"), Modifier.padding(bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyTextField(tr("Text in message"), smsMatch, { smsMatch = it }, placeholder = tr("e.g. card digits x1234"))
                MoneyTextField(tr("Sender ID"), smsSender, { smsSender = it }, placeholder = tr("Optional"))
            }
        }
        if (accountId != null) SecondaryButton(tr("Delete account"), { confirmDelete = true }, danger = true)
    }

    when (sheet) {
        "currency" -> Sheet(tr("Currency"), { sheet = null }) {
            SheetOption(tr("Main currency ({0})", mainFmt.symbol), accountCurrency == null) { accountCurrency = null; sheet = null }
            currencies.forEach { c -> SheetOption("${c.code} ${c.symbol}", accountCurrency == c.code) { accountCurrency = c.code; sheet = null } }
        }
        "payFrom" -> Sheet(if (credit) tr("Bill paid from") else tr("Linked bank account"), { sheet = null }) {
            SheetOption(tr("None"), paymentAccountId == null) { paymentAccountId = null; sheet = null }
            others.filter { !it.hidden || it.id == paymentAccountId }.forEach { a ->
                SheetOption(a.name, a.id == paymentAccountId, subtitle = groups.firstOrNull { it.id == a.groupId }?.name) { paymentAccountId = a.id; sheet = null }
            }
        }
        "basis" -> Sheet(tr("Target compared with"), { sheet = null }) {
            SheetOption(tr("Previous month"), targetType == 1, subtitle = tr("Last billing cycle's spending")) { targetType = 1; sheet = null }
            SheetOption(tr("3 months average"), targetType == 2, subtitle = tr("Average of the last three cycles")) { targetType = 2; sheet = null }
        }
    }

    if (confirmDelete && accountId != null) Confirm(
        tr("Delete {0}?", name.ifBlank { tr("account") }),
        tr("The account is hidden. Its transactions stay in your history and reports. You can restore it from Money › Manage accounts › Deleted accounts."),
        tr("Delete"), { confirmDelete = false },
    ) {
        scope.launch {
            withContext(repo.io) { repo.deleteAccount(accountId) }
            nav.pop()
            if (nav.stack.lastOrNull() == Screen.AccountDetail(accountId)) nav.pop()
            toaster.show(tr("{0} deleted", name)) { repo.moneyIo { repo.restoreAccount(accountId) } }
        }
    }
}
