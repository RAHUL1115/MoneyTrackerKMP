package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val payDay = a.cardPaymentDay ?: return null
    val closed = Periods.cardCycle(today, a.cardSettlementDay ?: 1).start.minus(DatePeriod(days = 1))
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
    val summary = rememberMoneySummary()
    var trendOn by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var manage by remember { mutableStateOf(false) }
    val today = repo.today()

    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("Money")) {
            IconTile(Glyph.Trend, tr("Net worth trend"), { trendOn = !trendOn },
                tint = if (trendOn) F.accent else F.ink, background = if (trendOn) F.accentTint else F.card)
            // A gear means app Settings everywhere, so this one says what it opens.
            HeaderButton(tr("Groups"), filled = false) { manage = true }
            IconTile(Glyph.Add, tr("Add account"), { nav.push(Screen.EditAccount()) })
        }
        val (lines, worth) = summary ?: return@Column
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = F.gutter),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NetWorthHero(worth.net, worth.assets, worth.liabilities, trendOn)

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
                        Text(tr("Pay bill"), Modifier.clip(RoundedCornerShape(12.dp)).background(F.accent).padding(horizontal = 14.dp, vertical = 10.dp),
                            color = F.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

            // Groups, each with its total; hidden accounts only when asked for.
            lines.groupBy { it.group }.entries.sortedBy { it.key.sortOrder }.forEach { (group, groupLines) ->
                val visible = groupLines.filter { showHidden || !it.account.hidden }
                if (visible.isEmpty()) return@forEach
                val isCard = group.type.isCreditCard
                val counted = groupLines.filter { it.account.includeInTotals }
                val total = if (isCard) -counted.sumOf { Fx.toMain(it.owed, it.account.currency?.let { c -> currencies[c] }, currency.decimals) }
                else counted.sumOf {
                    val v = if (group.type.isDebitCard) it.balance else it.effectiveBalance
                    Fx.toMain(v, it.account.currency?.let { c -> currencies[c] }, currency.decimals)
                }
                val allHidden = groupLines.all { it.account.hidden }
                val open = group.id !in collapsed
                FCard(padding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().clickable { collapsed = if (open) collapsed + group.id else collapsed - group.id }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.name, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Text(" · ${visible.size}" + if (allHidden) " · " + tr("hidden") else "", Modifier.weight(1f), color = F.muted, fontSize = 13.sp, maxLines = 1)
                        Text(currency.format(total), color = if (allHidden) F.muted else if (total < 0) F.expense else F.ink,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
                        Icon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
                    }
                    if (open) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
                        visible.forEachIndexed { i, l ->
                            if (i > 0) Divider()
                            AccountRow(l, today)
                        }
                    }
                }
            }

            val hiddenCount = lines.count { it.account.hidden }
            if (hiddenCount > 0) {
                val groupsWithHidden = lines.filter { it.account.hidden }.map { it.group.name }.distinct().joinToString(", ")
                Text(
                    if (showHidden) tr("Hide {0} hidden accounts", hiddenCount) else tr("{0} hidden accounts ({1}) · Show", hiddenCount, groupsWithHidden),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showHidden = !showHidden }.padding(vertical = 12.dp, horizontal = 4.dp),
                    color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                )
            }
            Gap(24.dp)
        }
    }

    if (manage) ManageSheet(summary?.first.orEmpty()) { manage = false }
}

@Composable
private fun NetWorthHero(net: Long, assets: Long, liabilities: Long, trendOn: Boolean) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).padding(20.dp)) {
        Text(tr("Net worth · in totals"), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(currency.format(net), color = F.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
        Gap(12.dp)
        val share = if (assets + liabilities > 0) assets.toFloat() / (assets + liabilities) else 1f
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)).background(F.fill)) {
            if (share > 0f) Box(Modifier.weight(share).height(8.dp).background(F.accent))
            if (share < 1f) Box(Modifier.weight(1f - share).height(8.dp))
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(tr("Assets"), currency.format(assets), 1f)
            Legend(tr("Liabilities"), currency.format(liabilities), 0.35f)
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
private fun Legend(label: String, value: String, alpha: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(F.accent.copy(alpha = alpha)))
        Text("  $label ", color = F.muted, fontSize = 12.sp)
        Text(value, color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

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
            when {
                card.overdue > 0 -> add(tr("{0} overdue", fmt.format(card.overdue)))
                l.billDue > 0 && due != null -> add(tr("Bill {0} · due {1}", fmt.format(l.billDue), due.short()))
                l.billDue > 0 -> add(tr("Bill {0}", fmt.format(l.billDue)))
                else -> add(tr("Statement on day {0}", a.cardSettlementDay ?: 1))
            }
        }
        if (l.group.type.isDebitCard) add(tr("Debit card"))
        if (l.effectiveBalance != l.balance && card == null && !l.group.type.isDebitCard) add(tr("{0} after card spends", fmt.format(l.effectiveBalance)))
        if (!a.includeInTotals) add(tr("Not in totals"))
        if (a.hidden) add(tr("Hidden"))
    }
    val amount = if (card != null) -l.owed else if (l.group.type.isDebitCard) l.balance else l.effectiveBalance
    val dim = a.hidden
    Column(Modifier.fillMaxWidth().clickable { nav.push(Screen.AccountDetail(a.id)) }.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name + (a.currency?.let { "  ($it)" } ?: ""), color = if (dim) F.muted else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = if (card != null && card.overdue > 0) F.danger else F.muted, fontSize = 12.sp, maxLines = 2)
            }
            Text(fmt.format(amount), color = if (dim) F.muted else if (amount < 0) F.expense else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                style = F.money, maxLines = 1)
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
