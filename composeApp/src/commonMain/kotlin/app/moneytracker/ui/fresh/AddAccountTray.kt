package app.moneytracker.ui.fresh

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.core.tr
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.Txn
import app.moneytracker.ui.EntryChoices
import app.moneytracker.ui.EntryForm

private const val RECENT_TAB = "recent"
private const val OTHER_TAB = "other"

/** What the account tray draws besides the accounts: recently used entries and each account's balance line. */
internal class AccountTrayData(val recentTxns: List<Txn>, val lines: Map<String, AccountLine>)

/**
 * The account tray for one row: Recent (usual and recently used) first, then one tab per account group.
 * [current] is the row's account, [usual] the one usually used, [shown] the accounts of the open [tab].
 */
internal class AccountTray(
    val forTo: Boolean,
    val current: String?,
    val usual: String?,
    val tabs: List<Pair<String, String>>,
    val tab: String?,
    val shown: List<Account>,
)

internal fun accountTray(f: EntryForm, forTo: Boolean, usual: String?, choices: EntryChoices, recentTxns: List<Txn>, openTab: String?): AccountTray {
    val current = if (forTo) f.toAccountId else f.accountId
    val pickable = choices.accounts.filter { (!it.hidden || it.id == current) && (!forTo || it.id != f.accountId) }
    val recent = (listOfNotNull(usual, current) + recentTxns.sortedByDescending { it.createdAt }
        .map { if (forTo) it.toAccountId else it.accountId }).filterNotNull().distinct()
        .mapNotNull { id -> pickable.firstOrNull { it.id == id } }.take(6)
    val knownGroups = choices.groups.map { it.id }.toSet()
    val tabs = buildList {
        if (recent.isNotEmpty()) add(RECENT_TAB to tr("Recent"))
        choices.groups.sortedBy { it.sortOrder }.filter { g -> pickable.any { it.groupId == g.id } }.forEach { add(it.id to it.name) }
        if (pickable.any { it.groupId !in knownGroups }) add(OTHER_TAB to tr("Other"))
    }
    val tab = openTab?.takeIf { t -> tabs.any { it.first == t } } ?: tabs.firstOrNull()?.first
    val shown = when (tab) {
        RECENT_TAB -> recent
        OTHER_TAB -> pickable.filter { it.groupId !in knownGroups }
        else -> pickable.filter { it.groupId == tab }
    }
    return AccountTray(forTo, current, usual, tabs, tab, shown)
}

/** What an account shows on the right of its row: a card's debt (or credit), else its balance. */
@Composable
internal fun balanceOf(a: Account, lines: Map<String, AccountLine>): Pair<String, Color> {
    val l = lines[a.id] ?: return "" to F.muted
    val af = a.currency?.let { LocalCurrencies.current[it]?.format } ?: LocalCurrency.current
    val c = l.card
    return when {
        c != null && c.outstanding < 0 && c.totalOwed == 0L -> tr("{0} credit", af.format(-c.outstanding)) to F.income
        c != null && c.totalOwed + c.upcoming > 0 -> tr("{0} owed", af.format(c.totalOwed + c.upcoming)) to F.muted
        c != null -> tr("Nothing owed") to F.muted
        else -> {
            val v = if (l.group.type.isDebitCard) l.balance else l.effectiveBalance
            af.format(v) to when { v > 0 -> F.income; v < 0 -> F.expense; else -> F.muted }
        }
    }
}
