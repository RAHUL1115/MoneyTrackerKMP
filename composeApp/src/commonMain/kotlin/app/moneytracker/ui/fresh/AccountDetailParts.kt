package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.CardStatus
import app.moneytracker.domain.Category
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.Periods
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.cardUsage
import app.moneytracker.ui.Privacy
import app.moneytracker.ui.accounts.AccountHistory
import app.moneytracker.ui.accounts.billDue
import app.moneytracker.ui.accounts.displayName
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.label
import app.moneytracker.ui.tr
import kotlinx.datetime.LocalDate

// The pieces of the new layout's account detail screen: the balance card, action tiles, totals and entry rows.

@Composable
internal fun AccountHero(line: AccountLine, fmt: CurrencyFormat, history: AccountHistory, today: LocalDate) {
    val a = line.account
    val card = line.card
    Column(Modifier.fillMaxWidth().clip(F.heroShape).background(F.card)
        .clickable(enabled = Privacy.masked) { Privacy.peek() }.padding(20.dp)) {
        Row {
            Text(listOf(line.group.type.displayName, line.group.name).distinct().joinToString(" · ") + (a.currency?.let { " · $it" } ?: ""),
                Modifier.weight(1f), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card != null) Text(tr("Cycle {0}", Periods.cardCycle(today, a.statementDay).label()), color = F.muted, fontSize = 12.sp, maxLines = 1)
        }
        Gap(8.dp)
        if (card != null) {
            CardBalances(line, card, fmt)
            CardLimitAndUsage(line, card, fmt, history, today)
        } else {
            Text(tr("Balance"), color = F.muted, fontSize = 12.sp)
            Text(fmt.format(line.balance), color = F.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
            if (line.effectiveBalance != line.balance && !line.group.type.isDebitCard)
                Text(tr("{0} after debit card spends", fmt.format(line.effectiveBalance)), color = F.muted, fontSize = 12.sp)
        }
        if (a.memo.isNotBlank()) {
            Gap(10.dp)
            Text(a.memo, color = F.muted, fontSize = 13.sp)
        }
    }
}

/** A card's two balances: payable from closed statements, and not yet billed (or in credit). */
@Composable
private fun CardBalances(line: AccountLine, card: CardStatus, fmt: CurrencyFormat) {
    Row {
        Column(Modifier.weight(1f)) {
            Text(tr("Balance payable"), color = F.muted, fontSize = 12.sp)
            Text(fmt.format(line.billDue), color = F.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
            if (card.overdue > 0) Text(tr("incl. {0} overdue", fmt.format(card.overdue)), color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            val credit = card.outstanding < 0
            Text(if (credit) tr("In credit") else tr("Not yet billed"), color = F.muted, fontSize = 12.sp)
            Text(fmt.format(kotlin.math.abs(card.outstanding)), color = if (credit) F.income else F.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                style = F.money, maxLines = 1)
            Text(tr("Spent this cycle {0}", fmt.format(card.cycleSpend)), color = F.muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** The card's monthly limit and usage target, each as a bar, when set. */
@Composable
private fun CardLimitAndUsage(line: AccountLine, card: CardStatus, fmt: CurrencyFormat, history: AccountHistory, today: LocalDate) {
    val a = line.account
    val limit = a.cardLimit
    if (limit != null && limit > 0) {
        Gap(12.dp)
        Bar(card.cycleSpend.toFloat() / limit, fill = F.accent, track = F.fill, height = 6.dp)
        Text(tr("{0} of {1} limit", fmt.format(card.cycleSpend), fmt.format(limit)), Modifier.padding(top = 4.dp), color = F.muted, fontSize = 12.sp)
    }
    val target = a.usageTarget ?: return
    val usage = cardUsage(history.history, a.statementDay, today, a.usageTargetType == 2, history.usageExcluded)
    val met = usage.measured >= target
    Gap(12.dp)
    Bar(usage.thisCycle.toFloat() / target, fill = F.accent, track = F.fill, height = 6.dp)
    Text(
        (if (a.usageTargetType == 2) tr("Usage target {0} · this cycle {1} · 3-month avg. {2} {3}", fmt.format(target),
            fmt.format(usage.thisCycle), fmt.format(usage.measured), if (met) "✓" else "✗")
        else tr("Usage target {0} · this cycle {1} · last month {2} {3}", fmt.format(target),
            fmt.format(usage.thisCycle), fmt.format(usage.measured), if (met) "✓" else "✗")),
        Modifier.padding(top = 4.dp), color = F.ink, fontSize = 12.sp, fontWeight = if (met) FontWeight.Normal else FontWeight.Medium,
    )
}

@Composable
internal fun RowScope.AccountActionTile(icon: ImageVector, label: String, primary: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val bg = when { primary -> F.accent; selected -> F.accentTint; else -> F.card }
    val fg = when { primary -> F.onAccent; selected -> F.accent; else -> F.ink }
    Column(
        Modifier.weight(1f).clip(F.buttonShape).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AccountMiniTotal(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(F.card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The entry's title: its note, or else where the money went. */
private fun entryTitle(t: Txn, accountName: Map<String, String>, catById: Map<String, Category>): String = t.note.ifBlank {
    when (t.kind) {
        TxnKind.TRANSFER_OUT -> tr("To {0}", accountName[t.toAccountId].orEmpty())
        TxnKind.TRANSFER_IN -> tr("From {0}", accountName[t.toAccountId].orEmpty())
        TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr(MODIFIED_BALANCE_NAME)
        TxnKind.INCOME, TxnKind.EXPENSE -> categoryPath(t.categoryId, catById)
    }
}

/** The small line under an entry's title: kind, instalment, refund, repeat. */
private fun entryMeta(t: Txn, accountName: Map<String, String>, catById: Map<String, Category>): String = listOfNotNull(
    when (t.kind) {
        TxnKind.TRANSFER_OUT -> tr("Transfer to {0}", accountName[t.toAccountId].orEmpty())
        TxnKind.TRANSFER_IN -> tr("Transfer from {0}", accountName[t.toAccountId].orEmpty())
        TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr("Not counted")
        TxnKind.INCOME, TxnKind.EXPENSE -> if (t.note.isNotBlank()) categoryPath(t.categoryId, catById) else null
    },
    t.installmentLabel,
    if (t.isRefund) tr("Refund") else null,
    if (t.recurringRuleId != null) "↻" else null,
).joinToString(" · ")

/** An entry in a day card. The row pads itself with [inset] (and [extraBottom], the card's spacing under its last row) so its highlight spans the card. */
@Composable
internal fun AccountEntryRow(
    t: Txn, accountName: Map<String, String>, catById: Map<String, Category>, fmt: CurrencyFormat, running: Long?,
    inset: Dp, extraBottom: Dp = 0.dp, onClick: () -> Unit,
) {
    val title = entryTitle(t, accountName, catById)
    val meta = entryMeta(t, accountName, catById)
    // Signed so a refund (negative expense) reads as money in.
    val value = t.signedAccountValue
    val incoming = value > 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = extraBottom).padding(horizontal = inset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt.format(value, showSign = true), color = if (incoming) F.income else if (t.kind == TxnKind.EXPENSE) F.expense else F.ink,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
            if (running != null) Text(fmt.format(running), color = F.muted, fontSize = 11.sp, style = F.money, maxLines = 1)
        }
    }
}
