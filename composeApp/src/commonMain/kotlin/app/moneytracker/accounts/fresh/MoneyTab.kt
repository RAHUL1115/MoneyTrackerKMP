package app.moneytracker.accounts.fresh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.accounts.NetWorthPoint
import app.moneytracker.accounts.billDue
import app.moneytracker.accounts.billDueDate
import app.moneytracker.accounts.mainTotal
import app.moneytracker.accounts.rememberAccountsModel
import app.moneytracker.accounts.shownBalance
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrencies
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.tr
import app.moneytracker.data.AccountSummary
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.Bar
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.IconTile
import app.moneytracker.designsystem.fresh.TabHeader
import app.moneytracker.designsystem.fresh.expandState
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.CardStatus
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import kotlinx.datetime.LocalDate

@Composable
internal fun MoneyTab() {
    val today = LocalRepo.current.today()
    val model = rememberAccountsModel()
    val summary by model.summary.collectAsState()
    var trendOn by rememberSaveable { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    // The trend is only worked out while it's open.
    val trend = if (trendOn) remember(model) { model.netWorthTrend(6) }.collectAsState(emptyList()).value else emptyList()
    MoneyTabContent(summary, today, NetWorthTrend(trendOn, trend) { trendOn = !trendOn }) { manage = true }
    if (manage) ManageAccountsSheet(summary?.lines.orEmpty()) { manage = false }
}

/** The net-worth card's trend: whether it's open, and its points once worked out. */
internal class NetWorthTrend(val open: Boolean, val points: List<NetWorthPoint>, val onToggle: () -> Unit)

@Composable
private fun MoneyTabContent(summary: AccountSummary?, today: LocalDate, trend: NetWorthTrend, onManage: () -> Unit) {
    val nav = LocalNavigator.current
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("Accounts")) {
            // Groups gets its own icon (stacked layers): a gear means app Settings everywhere.
            IconTile(Glyph.Groups, tr("Account groups"), onManage)
            IconTile(Glyph.Add, tr("Add account"), { nav.push(Screen.EditAccount()) }, tint = F.accent, background = F.accentTint)
        }
        val (lines, worth) = summary ?: return@Column
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetWorthHero(worth.net, worth.assets, worth.liabilities, trend)
            // Card bills waiting to be paid.
            lines.filter { it.billDue > 0 && !it.account.hidden }
                .sortedBy { billDueDate(it.account, today) ?: today }
                .forEach { BillCard(it, today) }
            // Groups, each with its total; hidden accounts only when asked for.
            lines.groupBy { it.group }.entries.sortedBy { it.key.sortOrder }.forEach { (group, groupLines) ->
                val open = group.id !in collapsed
                GroupCard(group, groupLines, showHidden, open, today) { collapsed = if (open) collapsed + group.id else collapsed - group.id }
            }
            HiddenAccountsButton(lines, showHidden) { showHidden = !showHidden }
            Gap(24.dp)
        }
    }
}

@Composable
private fun BillCard(l: AccountLine, today: LocalDate) {
    val nav = LocalNavigator.current
    val fmt = l.account.currency?.let { LocalCurrencies.current[it]?.format } ?: LocalCurrency.current
    val due = billDueDate(l.account, today)
    val overdue = (l.card?.overdue ?: 0) > 0 || (due != null && due < today)
    Row(
        Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable { nav.push(Screen.AccountDetail(l.account.id)) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    overdue -> tr("{0} bill · overdue", l.account.name)
                    due != null -> tr("{0} bill · due {1}", l.account.name, due.short())
                    else -> tr("{0} bill", l.account.name)
                },
                color = if (overdue) F.danger else F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(fmt.format(l.billDue), color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
            if (l.account.cardAutoPay) Text(tr("Auto-pay is on"), color = F.muted, fontSize = 12.sp)
        }
        Box(Modifier.height(40.dp).clip(F.controlShape).background(F.accentTint).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(tr("Pay bill"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A group header's figures in the main currency: its total and, for cards, what's outstanding in the open cycles. */
private class AccountGroupTotals(val total: Long, val outstanding: Long?)

/** Cards total what's payable; under it, what's outstanding. A card in credit doesn't lower that, since its credit doesn't pay another card's charges. */
private fun groupTotals(group: AccountGroup, groupLines: List<AccountLine>, currencies: Map<String, Currency>, decimals: Int): AccountGroupTotals =
    if (group.type.isCreditCard) {
        AccountGroupTotals(groupLines.mainTotal(currencies, decimals) { it.billDue }, groupLines.mainTotal(currencies, decimals) { maxOf(0L, it.card?.outstanding ?: 0L) })
    } else {
        AccountGroupTotals(groupLines.mainTotal(currencies, decimals) { it.shownBalance }, null)
    }

@Composable
private fun GroupCard(group: AccountGroup, groupLines: List<AccountLine>, showHidden: Boolean, open: Boolean, today: LocalDate, onToggle: () -> Unit) {
    val visible = groupLines.filter { showHidden || !it.account.hidden }
    if (visible.isEmpty()) return
    FListCard {
        GroupHeader(group, groupLines, visible.size, open, onToggle)
        AnimatedVisibility(open, enter = expandVertically(tween(200)), exit = shrinkVertically(tween(200))) {
            // Each row pads itself, the last one with the card's bottom spacing too, so its tap highlight spans the card.
            Column {
                visible.forEachIndexed { i, l ->
                    if (i > 0) Divider(ACCOUNT_INSET)
                    AccountRow(l, today, extraBottom = if (i == visible.lastIndex) 6.dp else 0.dp)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: AccountGroup, groupLines: List<AccountLine>, visibleCount: Int, open: Boolean, onToggle: () -> Unit) {
    val currency = LocalCurrency.current
    val totals = groupTotals(group, groupLines, LocalCurrencies.current, currency.decimals)
    val allHidden = groupLines.all { it.account.hidden }
    Row(
        Modifier.fillMaxWidth().expandState(open).clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 40.dp),                 // room for the card group's two amounts; every group header matches
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name and count on the left, the total on the right.
        Row(Modifier.weight(1f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Text(" · $visibleCount" + if (allHidden) " · " + tr("hidden") else "", color = F.muted, fontSize = 13.sp, maxLines = 1)
        }
        // Cards: payable on top, outstanding under it, as on each card row.
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(currency.format(totals.total), color = if (allHidden) F.muted else balanceTone(totals.total, group.type.isCreditCard),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
            if (totals.outstanding != null) Text(currency.format(totals.outstanding), color = F.muted, fontSize = 12.sp, style = F.money, maxLines = 1)
        }
        Icon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

/** A quiet dashed button: hidden accounts are there, but not asking for attention. */
@Composable
private fun HiddenAccountsButton(lines: List<AccountLine>, showHidden: Boolean, onToggle: () -> Unit) {
    val hiddenCount = lines.count { it.account.hidden }
    if (hiddenCount == 0) return
    val groupsWithHidden = lines.filter { it.account.hidden }.map { it.group.name }.distinct().joinToString(", ")
    val dash = F.switchOff
    Box(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(F.iconShape)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRoundRect(dash, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
            .clickable(role = Role.Button, onClick = onToggle).padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (showHidden) tr("Hide {0} hidden accounts", hiddenCount) else tr("{0} hidden accounts ({1}) · Show", hiddenCount, groupsWithHidden),
            color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center,
        )
    }
}

/** Side padding of an account row inside its group card; the dividers between rows use the same inset. */
private val ACCOUNT_INSET = 16.dp

/** What a row says under the account's name. The bold amount on a card is the bill (payable); this only says what's next for it. */
private fun rowMeta(l: AccountLine, fmt: CurrencyFormat, today: LocalDate): List<String> = buildList {
    val a = l.account
    val card = l.card
    if (card != null) {
        val due = billDueDate(a, today)
        when {
            card.overdue > 0 -> add(tr("{0} overdue", fmt.format(card.overdue)))
            l.billDue > 0 && due != null -> add(tr("Due {0}", due.short()))
            else -> add(tr("Statement on day {0}", a.statementDay))
        }
    }
    if (l.group.type.isDebitCard) add(tr("Debit card"))
    if (l.effectiveBalance != l.balance && card == null && !l.group.type.isDebitCard) add(tr("{0} after card spends", fmt.format(l.effectiveBalance)))
    if (!a.includeInTotals) add(tr("Not in totals"))
    if (a.hidden) add(tr("Hidden"))
}

@Composable
private fun AccountRow(l: AccountLine, today: LocalDate, extraBottom: Dp) {
    val nav = LocalNavigator.current
    val a = l.account
    val fmt = a.currency?.let { LocalCurrencies.current[it]?.format } ?: LocalCurrency.current
    val card = l.card
    val meta = rowMeta(l, fmt, today)
    // A card has two balances: payable from closed statements, and outstanding in the open cycle.
    val amount = if (card != null) card.statementDue + card.overdue else l.shownBalance
    val dim = a.hidden
    val overdue = card != null && card.overdue > 0
    Column(Modifier.fillMaxWidth().clickable { nav.push(Screen.AccountDetail(a.id)) }.padding(bottom = extraBottom).padding(horizontal = ACCOUNT_INSET, vertical = 12.dp)) {
        // Every row is as tall as a two-line card row, so the list keeps one rhythm.
        Row(Modifier.heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name + (a.currency?.let { "  ($it)" } ?: ""), color = if (dim) F.muted else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = if (overdue) F.danger else F.muted, fontSize = 12.sp, maxLines = 2)
            }
            RowAmounts(card, amount, dim, fmt)
        }
        LimitBar(l, fmt)
    }
}

/** The row's amount and, for a card, what's outstanding (or the credit when overpaid). */
@Composable
private fun RowAmounts(card: CardStatus?, amount: Long, dim: Boolean, fmt: CurrencyFormat) {
    val overdue = card != null && card.overdue > 0
    Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
        Text(fmt.format(amount), color = if (dim) F.muted else if (overdue) F.danger else balanceTone(amount, card != null),
            fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
        // Overpaid: what's left after every charge is covered shows as a credit.
        if (card != null) {
            if (card.outstanding < 0) Text(tr("In credit {0}", fmt.format(-card.outstanding)), color = if (dim) F.muted else F.income, fontSize = 12.sp, maxLines = 1)
            else Text(tr("Outstanding {0}", fmt.format(card.outstanding)), color = F.muted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** A card with a monthly limit: how much this cycle has used. */
@Composable
private fun LimitBar(l: AccountLine, fmt: CurrencyFormat) {
    val limit = l.account.cardLimit
    val card = l.card
    if (card == null || limit == null || limit <= 0) return
    val ratio = card.cycleSpend.toFloat() / limit
    Gap(6.dp)
    Bar(ratio, fill = if (ratio > 1f) F.danger else if (ratio > 0.8f) F.warn else F.accent, height = 5.dp)
    Text(tr("{0} of {1} limit", fmt.format(card.cycleSpend), fmt.format(limit)), Modifier.padding(top = 4.dp),
        color = if (ratio > 1f) F.danger else F.muted, fontSize = 11.sp)
}

@Composable internal fun FreshAccountDetail(accountId: String) = AccountDetailBody(accountId)
@Composable internal fun FreshEditAccount(accountId: String?) = AccountEditBody(accountId)

/** Balances above zero are blue and below zero red. A card balance is what you owe, so it stays ink. */
private fun balanceTone(amount: Long, isCard: Boolean): Color = when {
    isCard || amount == 0L -> F.ink
    amount > 0 -> F.income
    else -> F.expense
}
