package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.domain.Calc
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.ui.EntryForm
import app.moneytracker.ui.LocalCurrencies
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.formatTime
import app.moneytracker.ui.message
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

// The receipt card of the add screen (AddScreen.kt): amount over a card of rows, the row being filled underlined.

/** Side inset of everything inside the receipt card; each row applies it inside its own tap area. */
private val ReceiptInset = 16.dp

internal fun shown(f: CurrencyFormat, expr: String) = if (f.symbolAfter) "$expr ${f.symbol}" else "${f.symbol}$expr"

/** "Today", "Yesterday", or "Mon 3 Mar". */
internal fun dateLabel(d: LocalDate, today: LocalDate): String = when (d) {
    today -> tr("Today")
    today.minus(DatePeriod(days = 1)) -> tr("Yesterday")
    else -> "${d.weekday()} ${d.short()}"
}

/** The category's full path, or null when none is picked. */
internal fun AddContext.categoryLabel(): String? = model.form.categoryId?.let { categoryPath(it, choices.catById) }?.ifEmpty { null }

internal fun AddContext.accountName(id: String?): String? = id?.let { i -> choices.accounts.firstOrNull { it.id == i }?.name }

@Composable
internal fun ReceiptCard(c: AddContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    // No side padding on the card: each row pads itself, so a row's tap highlight spans the card's full width.
    Column(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).padding(top = 14.dp, bottom = 2.dp)) {
        val (hint, hintColor) = amountHint(c)
        Text(hint, Modifier.padding(horizontal = ReceiptInset).height(18.dp), color = hintColor, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        AmountLine(c) { ui.feeTarget = false; ui.goTo(Step.AMOUNT, focus) }
        // The row being filled is underlined in the accent colour.
        RowDivider(ui.step == Step.AMOUNT, Modifier.padding(top = 8.dp))
        when {
            f.isTransfer -> {
                ReceiptRow(Glyph.Card, c.accountName(f.accountId) ?: tr("Choose"), tr("From"), missing = f.accountId == null, active = ui.step == Step.FROM) { ui.goTo(Step.FROM, focus) }
                ReceiptRow(Glyph.Swap, c.accountName(f.toAccountId) ?: tr("Choose"), tr("To"), missing = f.toAccountId == null, active = ui.step == Step.TO) { ui.goTo(Step.TO, focus) }
            }
            f.isAdjustment -> {
                ReceiptRow(Glyph.Edit, tr(MODIFIED_BALANCE_NAME), tr("Adjustment"), onClick = null)
                ReceiptRow(Glyph.Card, c.accountName(f.accountId) ?: tr("Choose"), tr("Account"), onClick = null)
            }
            else -> CategoryAndAccountRows(c)
        }
        DateRow(c)
        RowDivider(false)
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = ReceiptInset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RowIcon(Glyph.Note, accent = ui.step == Step.NOTE)
            NoteField(c, Modifier.weight(1f), background = Color.Transparent)
        }
    }
}

/** The big amount (or the fee being typed) with the currency chip. */
@Composable
private fun AmountLine(c: AddContext, onClick: () -> Unit) {
    val f = c.model.form
    val fmt = c.model.fmt
    val feeTarget = c.ui.feeTarget
    val total = c.model.amountValue()
    val amountText = shown(fmt, addExprShown(if (feeTarget) f.feeText.ifEmpty { "0" } else f.amountText))
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = ReceiptInset),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            (if (feeTarget) tr("Fee") + " " else "") + amountText, Modifier.weight(1f, fill = false),
            color = if (feeTarget) F.accent else if ((total ?: 0) > 0) F.ink else F.muted,
            fontSize = when { amountText.length > 16 -> 26.sp; amountText.length > 11 -> 32.sp; else -> 40.sp },
            fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, style = F.money,
        )
        CurrencyChip(f) { c.ui.sheet = AddSheet.CURRENCY }
    }
}

@Composable
private fun CategoryAndAccountRows(c: AddContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    val catById = c.choices.catById
    val catLabel = c.categoryLabel()
    val current = f.categoryId?.let { catById[it] }
    val moneyIn = f.type == DraftType.INCOME || f.type == DraftType.REFUND
    ReceiptRow(
        categoryIcon(current?.let { catById[it.parentId ?: it.id]?.name ?: it.name }) ?: Glyph.More,
        catLabel ?: tr("Pick a category"), if (f.type == DraftType.REFUND) tr("Refund for") else tr("Category"),
        accent = true, missing = catLabel == null, active = ui.step == Step.CATEGORY,
    ) { ui.goTo(Step.CATEGORY, focus) }
    ReceiptRow(Glyph.Card, c.accountName(f.accountId) ?: tr("Choose"), if (moneyIn) tr("Into") else tr("Account"),
        missing = f.accountId == null, active = ui.step == Step.ACCOUNT) { ui.goTo(Step.ACCOUNT, focus) }
}

/** Date, with the time as its own button on the right. */
@Composable
private fun DateRow(c: AddContext) {
    val f = c.model.form
    val focus = LocalFocusManager.current
    val today = remember { c.model.setup.today() }
    Row(Modifier.fillMaxWidth().height(52.dp).padding(end = ReceiptInset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = tr("Date")) { focus.clearFocus(); c.ui.sheet = AddSheet.DATE }
                .padding(start = ReceiptInset),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowIcon(Glyph.Calendar, accent = false)
            val day = "${f.date.weekday()} ${f.date.short()}"
            Text(if (f.date == today || f.date == today.minus(DatePeriod(days = 1))) "${dateLabel(f.date, today)}, $day" else day,
                color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.model.setup.timeOn) Row(
            Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(F.page)
                .clickable(onClickLabel = tr("Time")) { focus.clearFocus(); c.ui.timePicker = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Glyph.Clock, null, tint = F.ink, modifier = Modifier.size(15.dp))
            Text(f.timeOfDay?.let { formatTime(it) } ?: tr("Add time"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The line under the amount: an error, the sum of a calculation, or what the entry is. */
private fun amountHint(c: AddContext): Pair<String, Color> {
    val f = c.model.form
    val total = c.model.amountValue()
    val problem = f.problem
    return when {
        problem != null -> problem.message() to F.danger
        Calc.hasOperator(f.amountText) -> "= " + (total?.let { c.model.fmt.format(it) } ?: "…") to F.accent
        f.isAdjustment -> (if (f.adjustUp) tr("Balance increase") else tr("Balance decrease")) to F.muted
        f.isTransfer && f.accountId != null && f.accountId == f.toAccountId -> tr("Pick two different accounts") to F.danger
        f.isTransfer -> tr("Move money") to F.muted
        else -> listOfNotNull(
            if (f.existing != null) tr("Editing") else null,
            if (f.type == DraftType.REFUND) tr("Refund") else null,
            c.categoryLabel() ?: tr("Pick a category"),
        ).joinToString(" · ") to F.muted
    }
}

@Composable
internal fun NoteField(c: AddContext, modifier: Modifier = Modifier, background: Color = F.page) {
    val ui = c.ui
    val focus = LocalFocusManager.current
    val voice = LocalPlatform.current.voice
    AddInput(
        c.model.form.note, { v -> c.model.edit { it.copy(note = v) } }, tr("Note"), modifier, background = background,
        fieldModifier = Modifier.focusRequester(ui.noteFocus).onFocusChanged { ui.noteFocused = it.isFocused },
        trailing = if (voice.speechAvailable) ({
            // Smart entry: MoneyTracker's own listening sheet; what it understood fills this screen in place.
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (ui.listening) F.accent else F.fill)
                    .clickable(onClickLabel = tr("Say the entry, e.g. 200 fuel")) { focus.clearFocus(); ui.listening = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Mic, tr("Say the entry, e.g. 200 fuel"), tint = if (ui.listening) F.onAccent else F.muted, modifier = Modifier.size(16.dp)) }
        }) else null,
    )
}

@Composable
private fun CurrencyChip(f: EntryForm, onClick: () -> Unit) {
    if (LocalCurrencies.current.isEmpty() || f.isAdjustment) return
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(999.dp))
            .clickable(onClickLabel = tr("Currency"), onClick = onClick).padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Quiet: only the code, so it doesn't compete with the amount.
        Text(f.currencyCode ?: tr("Main"), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Icon(Glyph.ChevronDown, tr("Currency"), tint = F.muted, modifier = Modifier.size(16.dp))
    }
}

/** Hairline under a receipt row, thicker and in the accent when the row is being filled. Always 2dp tall, so nothing moves. */
@Composable
private fun RowDivider(active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = ReceiptInset).height(2.dp), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.fillMaxWidth().height(if (active) 2.dp else 1.dp).background(if (active) F.accent else F.border))
    }
}

/** Icon tile at the start of a receipt row. */
@Composable
private fun RowIcon(icon: ImageVector, accent: Boolean) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (accent) F.accentTint else F.page), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (accent) F.accent else F.muted, modifier = Modifier.size(18.dp))
    }
}

/** One receipt row: icon, value, what it is, and a chevron when it opens a picker. Rows are divided by a hairline. */
@Composable
private fun ReceiptRow(icon: ImageVector, value: String, label: String, accent: Boolean = false, missing: Boolean = false, active: Boolean = false, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .padding(horizontal = ReceiptInset),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(icon, accent || active)
        Text(value, Modifier.weight(1f), color = if (missing) F.muted else F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = if (active) F.accent else F.muted, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        if (onClick != null) Icon(Glyph.ChevronRight, null, tint = if (active) F.accent else F.muted, modifier = Modifier.size(16.dp))
    }
    RowDivider(active)
}
