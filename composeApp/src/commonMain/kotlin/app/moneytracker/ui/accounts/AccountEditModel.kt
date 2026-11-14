package app.moneytracker.ui.accounts

import androidx.compose.runtime.saveable.Saver
import app.moneytracker.app.StateReader
import app.moneytracker.app.StateWriter
import app.moneytracker.core.outlivingScope
import app.moneytracker.core.tr
import app.moneytracker.data.AccountStore
import app.moneytracker.data.CurrencyStore
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The account form while it's being typed: days and amounts stay text until Save. [loaded] is false until a stored account fills it. */
data class AccountForm(
    val loaded: Boolean,
    val name: String = "",
    val groupId: String? = null,
    val includeInTotals: Boolean = true,
    val transferAsExpense: Boolean = false,
    val hidden: Boolean = false,
    val settlementDay: String = "1",
    val paymentAccountId: String? = null,
    val sortOrder: Int = 0,
    val currency: String? = null,
    val limitText: String = "",
    val paymentDay: String = "1",
    val autoPay: Boolean = false,
    val targetText: String = "",
    val targetType: Int = 1,
    val memo: String = "",
    val smsSender: String = "",
    val smsMatch: String = "",
) {
    companion object {
        /** Keeps a half-typed form when Android closes the app in the background. */
        val Saver: Saver<AccountForm, String> = Saver(save = { it.write() }, restore = { readForm(it) })
    }
}

private fun AccountForm.write(): String = StateWriter().bool(loaded).str(name).str(groupId).bool(includeInTotals).bool(transferAsExpense)
    .bool(hidden).str(settlementDay).str(paymentAccountId).int(sortOrder).str(currency).str(limitText).str(paymentDay)
    .bool(autoPay).str(targetText).int(targetType).str(memo).str(smsSender).str(smsMatch).toString()

private fun readForm(text: String): AccountForm? = StateReader.read(text) {
    AccountForm(
        loaded = bool(), name = need(), groupId = str(), includeInTotals = bool(), transferAsExpense = bool(),
        hidden = bool(), settlementDay = need(), paymentAccountId = str(), sortOrder = needInt(), currency = str(), limitText = need(),
        paymentDay = need(), autoPay = bool(), targetText = need(), targetType = needInt(), memo = need(), smsSender = need(), smsMatch = need(),
    )
}

/** Add or edit an account in both layouts: fills the form from the stored account, checks it, saves and deletes. */
class AccountEditModel(
    val accountId: String?,
    private val store: AccountStore,
    currencyStore: CurrencyStore,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    val groups: StateFlow<List<AccountGroup>> = store.groups.stateIn(scope, SharingStarted.Eagerly, store.groups.current)
    val accounts: StateFlow<List<Account>> = store.accounts.stateIn(scope, SharingStarted.Eagerly, store.accounts.current)
    val currencies: StateFlow<List<Currency>> = currencyStore.currencies.stateIn(scope, SharingStarted.Eagerly, currencyStore.currencies.current)

    /** A new account starts filled in; an existing one waits for [filled]. New accounts go to the end of the list. */
    fun blankForm(): AccountForm = AccountForm(loaded = accountId == null, sortOrder = accounts.value.size)

    /** The form filled from the stored account, or null when it's already filled or the account isn't there (yet). */
    fun filled(form: AccountForm, mainFmt: CurrencyFormat): AccountForm? {
        if (form.loaded) return null
        val a = accounts.value.firstOrNull { it.id == accountId } ?: return null
        val fmt = formatFor(a.currency, mainFmt)
        return AccountForm(
            loaded = true, name = a.name, groupId = a.groupId, includeInTotals = a.includeInTotals, transferAsExpense = a.transferAsExpense,
            hidden = a.hidden, settlementDay = a.statementDay.toString(), paymentAccountId = a.cardPaymentAccountId, sortOrder = a.sortOrder,
            currency = a.currency, limitText = a.cardLimit?.let { fmt.toInput(it) }.orEmpty(), paymentDay = a.dueDay.toString(),
            autoPay = a.cardAutoPay, targetText = a.usageTarget?.let { fmt.toInput(it) }.orEmpty(), targetType = a.usageTargetType,
            memo = a.memo, smsSender = a.smsSender, smsMatch = a.smsMatch,
        )
    }

    fun groupOf(form: AccountForm): AccountGroup? = groups.value.firstOrNull { it.id == form.groupId }

    /** Why the form can't be saved, or null when it can. Every card has a bill day and a due day. */
    fun problem(form: AccountForm): String? {
        val credit = groupOf(form)?.type?.isCreditCard == true
        val day = form.settlementDay.toIntOrNull()
        val payDay = form.paymentDay.toIntOrNull()
        return when {
            form.groupId == null -> tr("Choose a group")
            form.name.isBlank() -> tr("Enter a name")
            credit && (day == null || day !in 1..31) -> tr("Billing day must be 1–31")
            credit && (payDay == null || payDay !in 1..31) -> tr("Payment day must be 1–31")
            else -> null
        }
    }

    /** Saves the form, then calls [onSaved]; returns the [problem] instead when there is one. */
    fun save(form: AccountForm, mainFmt: CurrencyFormat, onSaved: (Account) -> Unit): String? {
        problem(form)?.let { return it }
        val groupId = form.groupId ?: return null
        val account = accountFrom(form, groupId, mainFmt)
        scope.launch {
            withContext(io) { store.saveAccount(account) }
            onSaved(account)
        }
        return null
    }

    /** Starts from the stored account so fields this form doesn't show are kept; card fields only stay on cards. */
    private fun accountFrom(form: AccountForm, groupId: String, mainFmt: CurrencyFormat): Account {
        val type = groupOf(form)?.type
        val credit = type?.isCreditCard == true
        val isCard = credit || type?.isDebitCard == true
        val fmt = formatFor(form.currency, mainFmt)
        val base = accounts.value.firstOrNull { it.id == accountId } ?: Account(newId(), groupId, "", sortOrder = form.sortOrder)
        return base.copy(
            groupId = groupId, name = form.name.trim(), includeInTotals = form.includeInTotals, transferAsExpense = form.transferAsExpense,
            hidden = form.hidden, cardSettlementDay = if (credit) form.settlementDay.toIntOrNull() else null,
            cardPaymentAccountId = if (isCard) form.paymentAccountId else null, currency = form.currency,
            cardLimit = if (credit) fmt.parse(form.limitText)?.takeIf { it > 0 } else null, memo = form.memo.trim(),
            cardPaymentDay = if (credit) form.paymentDay.toIntOrNull() else null, cardAutoPay = credit && form.autoPay,
            usageTarget = if (credit) fmt.parse(form.targetText)?.takeIf { it > 0 } else null, usageTargetType = form.targetType,
            smsSender = form.smsSender.trim(), smsMatch = form.smsMatch.trim(),
        )
    }

    /** Deletes (hides) the account, then calls [onDeleted]. */
    fun delete(onDeleted: () -> Unit) {
        val id = accountId ?: return
        scope.launch {
            withContext(io) { store.deleteAccount(id) }
            onDeleted()
        }
    }

    /** Undo for [delete]; works after the screen has closed. */
    fun restore() {
        val id = accountId ?: return
        outlivingScope(scope.coroutineContext).launch { withContext(io) { store.restoreAccount(id) } }
    }

    /** Amounts are typed in the account's own currency. */
    fun formatFor(code: String?, mainFmt: CurrencyFormat): CurrencyFormat =
        code?.let { c -> currencies.value.firstOrNull { it.code == c }?.format } ?: mainFmt
}
