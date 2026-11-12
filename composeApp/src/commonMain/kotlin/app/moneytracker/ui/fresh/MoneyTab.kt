package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.Fx
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.ui.LocalCurrencies
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** What a credit card owes now: last statement plus anything older. */
internal val AccountLine.billDue: Long get() = card?.let { it.statementDue + it.overdue } ?: 0L
/** Everything charged to a card and not paid yet, including the open cycle and later instalments. */
internal val AccountLine.owed: Long get() = card?.let { it.totalOwed + it.upcoming } ?: 0L

/** The day the last closed statement has to be paid, from the card's payment day; null when no payment day is set. */
internal fun billDueDate(a: Account, today: LocalDate): LocalDate? {
    val payDay = a.dueDay
    val closed = Periods.cardCycle(today, a.statementDay).start.minus(DatePeriod(days = 1))
    var ym = YearMonth.of(closed)
    repeat(3) {
        val d = dayIn(ym, payDay)
        if (d > closed) return d
        ym = ym.plus(1)
    }
    return null
}

internal fun dayIn(ym: YearMonth, day: Int): LocalDate {
    val first = LocalDate(ym.year, ym.month, 1)
    val last = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).dayOfMonth
    return LocalDate(ym.year, ym.month, minOf(day, last))
}

@Composable
internal fun MoneyTab() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val summary = app.moneytracker.ui.rememberAccountSummary()
    var trendOn by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var manage by remember { mutableStateOf(false) }
    val today = repo.today()

    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("Accounts")) {
            // Groups gets its own icon (stacked layers): a gear means app Settings everywhere.
            IconTile(Glyph.Groups, tr("Account groups"), { manage = true })
            IconTile(Glyph.Add, tr("Add account"), { nav.push(Screen.EditAccount()) }, tint = F.accent, background = F.accentTint)
        }
        val (lines, worth) = summary ?: return@Column
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetWorthHero(worth.net, worth.assets, worth.liabilities, trendOn) { trendOn = !trendOn }

            // Card bills waiting to be paid.
            lines.filter { it.billDue > 0 && !it.account.hidden }
                .sortedBy { billDueDate(it.account, today) ?: today }
                .forEach { l ->
                    val fmt = l.account.currency?.let { currencies[it]?.format } ?: currency
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
                        Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(F.accentTint).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(tr("Pay bill"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

            // Groups, each with its total; hidden accounts only when asked for.
            lines.groupBy { it.group }.entries.sortedBy { it.key.sortOrder }.forEach { (group, groupLines) ->
                val visible = groupLines.filter { showHidden || !it.account.hidden }
                if (visible.isEmpty()) return@forEach
                val isCard = group.type.isCreditCard
                val counted = groupLines.filter { it.account.includeInTotals }
                val total = if (isCard) counted.sumOf { Fx.toMain(it.card?.let { c -> c.statementDue + c.overdue } ?: it.owed, it.account.currency?.let { c -> currencies[c] }, currency.decimals) }
                else counted.sumOf {
                    val v = if (group.type.isDebitCard) it.balance else it.effectiveBalance
                    Fx.toMain(v, it.account.currency?.let { c -> currencies[c] }, currency.decimals)
                }
                // Cards: under the payable total, what's outstanding in the open cycles. A card in credit doesn't lower it,
                // since its credit doesn't pay another card's charges.
                val outstanding = if (isCard) counted.sumOf { l ->
                    Fx.toMain(maxOf(0L, l.card?.outstanding ?: 0L), l.account.currency?.let { c -> currencies[c] }, currency.decimals)
                } else null
                val allHidden = groupLines.all { it.account.hidden }
                val open = group.id !in collapsed
                FCard(padding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable { collapsed = if (open) collapsed + group.id else collapsed - group.id }.padding(horizontal = 16.dp, vertical = 8.dp)
                            .heightIn(min = 40.dp),                 // room for the card group's two amounts; every group header matches
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Name and count on the left, the total on the right.
                        Row(Modifier.weight(1f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(group.name, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            Text(" · ${visible.size}" + if (allHidden) " · " + tr("hidden") else "", color = F.muted, fontSize = 13.sp, maxLines = 1)
                        }
                        // Cards: payable on top, outstanding under it, as on each card row.
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(currency.format(total), color = if (allHidden) F.muted else balanceTone(total, isCard),
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
                            if (outstanding != null) Text(currency.format(outstanding), color = F.muted, fontSize = 12.sp, style = F.money, maxLines = 1)
                        }
                        Icon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
                    }
                    AnimatedVisibility(open, enter = expandVertically(tween(200)), exit = shrinkVertically(tween(200))) {
                        // No side padding here: each row pads itself so its tap highlight spans the card.
                        Column(Modifier.padding(bottom = 6.dp)) {
                            visible.forEachIndexed { i, l ->
                                if (i > 0) Divider(ACCOUNT_INSET)
                                AccountRow(l, today)
                            }
                        }
                    }
                }
            }

            val hiddenCount = lines.count { it.account.hidden }
            if (hiddenCount > 0) {
                val groupsWithHidden = lines.filter { it.account.hidden }.map { it.group.name }.distinct().joinToString(", ")
                // A quiet dashed button: hidden accounts are there, but not asking for attention.
                val dash = F.switchOff
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(F.iconShape)
                        .drawBehind {
                            val w = 1.dp.toPx()
                            drawRoundRect(dash, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(14.dp.toPx()),
                                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                        }
                        .clickable { showHidden = !showHidden }.padding(vertical = 10.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (showHidden) tr("Hide {0} hidden accounts", hiddenCount) else tr("{0} hidden accounts ({1}) · Show", hiddenCount, groupsWithHidden),
                        color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center,
                    )
                }
            }
            Gap(24.dp)
        }
    }

    if (manage) ManageSheet(summary?.lines.orEmpty()) { manage = false }
}

@Composable
/** Tapping the card opens or closes the last six months of net worth. */
private fun NetWorthHero(net: Long, assets: Long, liabilities: Long, trendOn: Boolean, onToggle: () -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).clickable(onClick = app.moneytracker.ui.Privacy.tap(onToggle)).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Net worth · in totals"), Modifier.weight(1f), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(if (trendOn) Glyph.ChevronUp else Glyph.ChevronDown, tr("Net worth trend"), tint = F.muted, modifier = Modifier.size(18.dp))
        }
        Text(currency.format(net), color = F.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
        Gap(12.dp)
        val share = if (assets + liabilities > 0) assets.toFloat() / (assets + liabilities) else 1f
        // Assets in the accent, liabilities in a quiet grey, a small gap between them.
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (share > 0f) Box(Modifier.weight(share).height(8.dp).background(F.accent))
            if (share < 1f) Box(Modifier.weight(1f - share).height(8.dp).background(liabilityTone))
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(tr("Assets"), currency.format(assets), F.accent)
            Legend(tr("Liabilities"), currency.format(liabilities), liabilityTone)
        }
        if (trendOn) {
            val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
            val accounts by repo.accounts.collectAsState(emptyList())
            val currencies by repo.currencies.collectAsState(emptyList())
            // Net worth at the end of each of the last six months (main currency, accounts in totals).
            val points by produceState(emptyList<Pair<YearMonth, Long>>(), accounts, ps, currencies) {
                value = withContext(repo.io) {
                    val all = repo.allTxns()
                    val included = accounts.filter { it.includeInTotals }.map { it.id }.toSet()
                    val current = ps.labelFor(repo.today())
                    (5 downTo 0).map { back ->
                        val ym = current.plus(-back)
                        val end = ps.month(ym).endInclusive
                        ym to all.filter { it.date <= end && it.accountId in included }.sumOf { it.signedAmount }
                    }
                }
            }
            Gap(16.dp)
            if (points.isNotEmpty()) {
                val change = points.last().second - points.first().second
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Last 6 months · {0} {1} since {2}", if (change >= 0) "▲" else "▼", currency.format(kotlin.math.abs(change)), monthShort(points.first().first.month)),
                        Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(tr("Full trend ›"), Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.push(Screen.AccountsTrend) }.padding(4.dp),
                        color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Gap(8.dp)
                MoneyBars(points.map { MoneyBar(monthShort(it.first.month), it.second) }, currency.decimals, height = 52.dp)
            }
        }
    }
}

@Composable
private fun Legend(label: String, value: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text("  $label ", color = F.muted, fontSize = 12.sp)
        Text(value, color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

/** Side padding of an account row inside its group card; the dividers between rows use the same inset. */
private val ACCOUNT_INSET = 16.dp

@Composable
private fun AccountRow(l: AccountLine, today: LocalDate) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val a = l.account
    val fmt = a.currency?.let { currencies[it]?.format } ?: currency
    val card = l.card
    val meta = buildList {
        if (card != null) {
            val due = billDueDate(a, today)
            // The bold amount is the bill (payable), as in the group header; this line only says what's next for it.
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
    // A card has two balances: payable from closed statements, and outstanding in the open cycle.
    val amount = if (card != null) card.statementDue + card.overdue else if (l.group.type.isDebitCard) l.balance else l.effectiveBalance
    val dim = a.hidden
    Column(Modifier.fillMaxWidth().clickable { nav.push(Screen.AccountDetail(a.id)) }.padding(horizontal = ACCOUNT_INSET, vertical = 12.dp)) {
        // Every row is as tall as a two-line card row, so the list keeps one rhythm.
        Row(Modifier.heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name + (a.currency?.let { "  ($it)" } ?: ""), color = if (dim) F.muted else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = if (card != null && card.overdue > 0) F.danger else F.muted, fontSize = 12.sp, maxLines = 2)
            }
            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                Text(fmt.format(amount), color = if (dim) F.muted else if (card != null && card.overdue > 0) F.danger else balanceTone(amount, card != null),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
                // Overpaid: what's left after every charge is covered shows as a credit.
                if (card != null) {
                    if (card.outstanding < 0) Text(tr("In credit {0}", fmt.format(-card.outstanding)), color = if (dim) F.muted else F.income, fontSize = 12.sp, maxLines = 1)
                    else Text(tr("Outstanding {0}", fmt.format(card.outstanding)), color = F.muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        val limit = a.cardLimit
        if (card != null && limit != null && limit > 0) {
            val ratio = card.cycleSpend.toFloat() / limit
            Gap(6.dp)
            Bar(ratio, fill = if (ratio > 1f) F.danger else if (ratio > 0.8f) F.warn else F.accent, height = 5.dp)
            Text(tr("{0} of {1} limit", fmt.format(card.cycleSpend), fmt.format(limit)), Modifier.padding(top = 4.dp),
                color = if (ratio > 1f) F.danger else F.muted, fontSize = 11.sp)
        }
    }
}

/** Order groups, hide whole groups, restore deleted accounts; links to the detailed lists. */
@Composable
private fun ManageSheet(lines: List<AccountLine>, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val groups by repo.groups.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val deleted by repo.deletedAccounts.collectAsState(emptyList())
    var showDeleted by remember { mutableStateOf(false) }
    fun io(block: () -> Unit) = repo.moneyIo(block)

    Sheet(tr("Manage accounts"), onDismiss, bottom = {
        PrimaryButton(tr("Add account"), { onDismiss(); nav.push(Screen.EditAccount()) })
    }) {
        Text(tr("Arrows change the order of groups. The eye hides a group's accounts from lists and pickers (history and balance stay)."),
            color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        groups.forEachIndexed { i, g ->
            val inGroup = accounts.filter { it.groupId == g.id }
            val hidden = inGroup.isNotEmpty() && inGroup.all { it.hidden }
            fun move(delta: Int) {
                val order = groups.map { it.id }.toMutableList()
                val j = i + delta
                if (j !in order.indices) return
                order[i] = order[j].also { order[j] = order[i] }
                io { repo.reorderGroups(order) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, color = if (hidden) F.muted else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("{0} · {1} accounts", g.type.moneyLabel, inGroup.size) + if (hidden) " · " + tr("hidden") else "", color = F.muted, fontSize = 12.sp)
                }
                if (inGroup.isNotEmpty()) SmallTile(if (hidden) Glyph.EyeOff else Glyph.Eye, if (hidden) tr("Show {0}", g.name) else tr("Hide {0}", g.name)) {
                    val before = inGroup.associate { it.id to it.hidden }
                    io { inGroup.forEach { repo.setAccountHidden(it.id, !hidden) } }
                    toaster.show(if (hidden) tr("{0} shown", g.name) else tr("{0} hidden", g.name)) { io { before.forEach { (id, h) -> repo.setAccountHidden(id, h) } } }
                }
                SmallTile(Glyph.ChevronUp, tr("Move {0} up", g.name), enabled = i > 0) { move(-1) }
                SmallTile(Glyph.ChevronDown, tr("Move {0} down", g.name), enabled = i < groups.lastIndex) { move(1) }
            }
        }
        Gap(8.dp)
        Divider()
        FRow(tr("Accounts"), tr("Order, hide or edit each account"), onClick = { onDismiss(); nav.push(Screen.AccountList) })
        FRow(tr("Account groups"), tr("Rename, change type, add or delete groups"), onClick = { onDismiss(); nav.push(Screen.AccountGroups) })
        FRow(tr("Net worth trend"), tr("Assets and liabilities over the last 12 months"), onClick = { onDismiss(); nav.push(Screen.AccountsTrend) })
        if (deleted.isNotEmpty()) {
            Divider()
            FRow(tr("Deleted accounts ({0})", deleted.size), null, chevron = false, onClick = { showDeleted = !showDeleted }, trailing = {
                Icon(if (showDeleted) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted)
            })
            if (showDeleted) deleted.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, Modifier.weight(1f), color = F.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("Restore"), Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                        io { repo.restoreAccount(a.id) }
                        toaster.show(tr("{0} restored to its group", a.name)) { io { repo.deleteAccount(a.id) } }
                    }.padding(horizontal = 10.dp, vertical = 8.dp), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (lines.isEmpty() && accounts.isEmpty()) Text(tr("No accounts yet."), color = F.muted, fontSize = 13.sp)
    }
}

@Composable
private fun SmallTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(36.dp).clip(RoundedCornerShape(10.dp)).background(F.fill).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) F.ink else F.border, modifier = Modifier.size(18.dp)) }
}

@Composable internal fun FreshAccountDetail(accountId: String) = AccountDetailBody(accountId)
@Composable internal fun FreshEditAccount(accountId: String?) = AccountEditBody(accountId)

/** Liabilities on the net-worth bar: a quiet grey next to the accent. */
private val liabilityTone: Color get() = Color(if (app.moneytracker.ui.darkTheme) 0xFF3D4642 else 0xFFD5DBD8)

/** Balances above zero are blue and below zero red. A card balance is what you owe, so it stays ink. */
private fun balanceTone(amount: Long, isCard: Boolean): Color = when {
    isCard || amount == 0L -> F.ink
    amount > 0 -> F.income
    else -> F.expense
}
