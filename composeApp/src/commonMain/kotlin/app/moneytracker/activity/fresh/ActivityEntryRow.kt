package app.moneytracker.activity.fresh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.EntryLookups
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.core.formatTime
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.LocalListPrefs
import app.moneytracker.designsystem.classic.categoryPath
import app.moneytracker.designsystem.classic.mainCategoryName
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.Mark
import app.moneytracker.domain.Flow
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.classify
import kotlin.math.abs

/** Lookups every entry row needs, for screens that draw entry rows without a holder of their own. */
@Composable
internal fun rememberActEntryData(): EntryLookups {
    val repo = LocalRepo.current
    val accountName by repo.accountStore.accountNames.collectAsState()
    val catById by repo.categoryStore.categoryById.collectAsState()
    val tags by repo.tagStore.txnTagNames.collectAsState()
    val photos by repo.photoStore.txnIdsWithPhotos.collectAsState()
    val rules by repo.reportStore.reportRules.collectAsState()
    return remember(accountName, catById, rules, tags, photos) {
        EntryLookups(accountName, catById, rules, tags, photos)
    }
}

/** Side padding inside an entry card: [ActEntryRow] pads itself, and dividers between rows use the same inset. */
internal val ENTRY_INSET = 18.dp

/** The words on an entry row: its category, its title line and the details line under it. */
private class EntryTexts(val catName: String, val title: String, val meta: String)

private fun entryTexts(txn: Txn, data: EntryLookups, showDate: Boolean, showTime: Boolean): EntryTexts {
    val catName = when {
        txn.kind.isTransfer -> tr("Transfer")
        txn.kind.isAdjustment -> tr(MODIFIED_BALANCE_NAME)
        else -> categoryPath(txn.categoryId, data.catById)
    }
    val title = listOfNotNull(txn.note.ifBlank { null }, txn.installmentLabel).joinToString(" ").ifBlank { catName }
    val account = when (txn.kind) {
        TxnKind.TRANSFER_OUT -> "${data.accountName[txn.accountId].orEmpty()} → ${data.accountName[txn.toAccountId].orEmpty()}"
        TxnKind.TRANSFER_IN -> "${data.accountName[txn.toAccountId].orEmpty()} → ${data.accountName[txn.accountId].orEmpty()}"
        TxnKind.INCOME, TxnKind.EXPENSE, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> data.accountName[txn.accountId].orEmpty()
    }
    val meta = listOfNotNull(
        if (showDate) "${txn.date.dayOfMonth} ${monthShort(txn.date.monthNumber)} ${txn.date.year}" else null,
        if (showTime) txn.timeOfDay?.let { formatTime(it) } else null,
        if (txn.isRefund) tr("Refund") else null,
        if (txn.kind.isTransfer) null else catName,
        account.ifBlank { null },
        if (txn.recurringRuleId != null) tr("repeats") else null,
    ).joinToString(" · ")
    return EntryTexts(catName, title, meta)
}

/** Category mark · note and details · amount. Refunds show as +amount in the income colour. */
@Composable
internal fun ActEntryRow(
    txn: Txn,
    data: EntryLookups,
    selecting: Boolean = false,
    selected: Boolean = false,
    showDate: Boolean = false,
    compact: Boolean = false,
    /** Space from the card's edges to the content; the tap highlight still spans the full width. */
    inset: Dp = ENTRY_INSET,
    /** Card space under the last row, inside its tap highlight so the highlight reaches the card's bottom edge. */
    extraBottom: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val prefs = LocalListPrefs.current
    val texts = entryTexts(txn, data, showDate, prefs.showTime)
    Row(
        Modifier.fillMaxWidth().entryTap(selecting, selected, onLongClick, onClick).padding(bottom = extraBottom)
            .heightIn(min = if (compact) 52.dp else 64.dp).padding(horizontal = inset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
    ) {
        if (selecting) SelectBox(selected)
        val markSize = if (compact) 32.dp else 40.dp
        when {
            txn.kind.isTransfer -> Mark(tr("Transfer"), markSize, icon = Glyph.Swap)
            txn.kind.isAdjustment -> Mark(texts.catName, markSize, icon = Glyph.Edit)
            else -> Mark(mainCategoryName(txn.categoryId, data.catById), markSize)
        }
        EntryDetails(txn, texts, data, prefs.showDescription)
        EntryAmount(txn, data)
    }
}

/**
 * Tap opens (or, while [selecting], ticks) the entry; long-press selects it. While selecting, TalkBack reads the row
 * as a checkbox that is ticked or not.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.entryTap(selecting: Boolean, selected: Boolean, onLongClick: (() -> Unit)?, onClick: () -> Unit): Modifier =
    if (selecting) {
        combinedClickable(role = Role.Checkbox, onClick = onClick, onLongClick = onLongClick)
            .semantics { toggleableState = ToggleableState(selected) }
    } else {
        combinedClickable(onLongClickLabel = onLongClick?.let { tr("Select") }, onClick = onClick, onLongClick = onLongClick)
    }

/** The tick box while selecting; the row itself carries the ticked state for TalkBack. */
@Composable
private fun SelectBox(selected: Boolean) {
    Box(
        Modifier.clearAndSetSemantics {}.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) F.accent else F.card)
            .border(2.dp, if (selected) F.accent else F.border, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) { if (selected) Text("✓", color = F.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RowScope.EntryDetails(txn: Txn, texts: EntryTexts, data: EntryLookups, showDescription: Boolean) {
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(texts.title, Modifier.weight(1f, fill = false), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (txn.id in data.photos) Icon(Glyph.Camera, tr("Has a photo"), tint = F.muted, modifier = Modifier.size(14.dp))
            if (txn.lat != null) Icon(Glyph.Place, tr("Has a location"), tint = F.muted, modifier = Modifier.size(14.dp))
        }
        if (texts.meta.isNotEmpty()) Text(texts.meta, Modifier.padding(top = 3.dp), color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showDescription && txn.memo.isNotBlank()) Text(txn.memo, color = F.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val tags = data.tags[txn.id].orEmpty()
        if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" }, color = F.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The amount, signed by where the money went; a foreign amount shows over its main-currency value. */
@Composable
private fun EntryAmount(txn: Txn, data: EntryLookups) {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val flow = classify(data.rules.row(txn))
    val plus = txn.isRefund || flow == Flow.INCOME
    val color = amountColor(plus, txn.kind.isAdjustment, flow)
    val prefix = when {
        plus -> "+"
        flow == Flow.EXPENSE -> "−"
        else -> ""
    }
    Column(horizontalAlignment = Alignment.End) {
        val foreign = txn.currency?.let { currencies[it] }
        if (foreign != null && txn.originalAmount != null) {
            Text(prefix + foreign.format.format(abs(txn.originalAmount)), color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
            Text(currency.format(abs(txn.amount)), color = F.muted, fontSize = 11.sp, style = F.money, maxLines = 1)
        } else Text(prefix + currency.format(abs(txn.amount)), color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
    }
}

private fun amountColor(plus: Boolean, adjust: Boolean, flow: Flow): Color = when {
    plus -> F.income
    adjust -> F.muted
    flow == Flow.EXPENSE -> F.expense
    else -> F.ink
}
