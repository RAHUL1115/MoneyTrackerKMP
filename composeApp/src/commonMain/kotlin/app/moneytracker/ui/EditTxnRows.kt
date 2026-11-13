@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.moneytracker.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_NAME

// The rows of the classic editor (EditTxnScreen.kt): type, date, amount, accounts, category.

/** An adjustment has no type selected; choosing one converts it into a normal entry. A refund lives on the Income tab. */
@Composable
internal fun TypeRow(c: EditContext) {
    val f = c.model.form
    val tabs = listOf(DraftType.INCOME, DraftType.EXPENSE, DraftType.TRANSFER)
    TypeSegments(
        listOf(tr("Income"), tr("Expense"), tr("Transfer")),
        if (f.isAdjustment) -1 else tabs.indexOf(if (f.type == DraftType.REFUND) DraftType.INCOME else f.type),
        // Refund → Expense keeps the (expense) category.
        { i -> c.model.pickType(tabs[i], keepRefundCategory = true) },
    )
}

@Composable
internal fun DateFormRow(c: EditContext) {
    val f = c.model.form
    val ui = c.ui
    FormRow("Date", false, onClick = { ui.panel = Panel.NONE; ui.datePicker = true }, trailing = if (f.isAdjustment) null else ({
        Column(
            Modifier.clickable { ui.panel = Panel.NONE; ui.repeatDialog = true }.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Repeat, null, tint = if (f.repeat != null || f.installments > 1) Accent else SubtleText, modifier = Modifier.size(20.dp))
            Text(tr("Rep/Inst."), fontSize = 10.sp, color = SubtleText)
        }
    })) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.date.numeric())
                if (c.model.setup.timeOn) Text(
                    f.timeOfDay?.let { formatTime(it) } ?: tr("Add time"),
                    Modifier.padding(start = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { ui.panel = Panel.NONE; ui.timePicker = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (f.timeOfDay == null) SubtleText else Ink,
                )
            }
            val extra = listOfNotNull(f.repeat?.label?.let { tr(it) }, if (f.installments > 1) tr("{0} installments", f.installments) else null)
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), color = Accent, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun AmountRows(c: EditContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    FormRow("Amount", ui.panel == Panel.AMOUNT, onClick = { focus.clearFocus(); ui.panel = Panel.AMOUNT }, trailing = if (f.isTransfer) ({
        Text(
            tr("Fees"),
            Modifier.border(1.dp, if (ui.showFee) Accent else SubtleText, RoundedCornerShape(4.dp))
                .clickable { ui.showFee = !ui.showFee; if (ui.showFee) ui.panel = Panel.FEE else c.model.edit { it.copy(feeText = "") } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (ui.showFee) Accent else InkSoft,
        )
    }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(amountDisplay(f.amountText, ui.panel == Panel.AMOUNT, c.model.fmt), Modifier.weight(1f))
            if (LocalCurrencies.current.isNotEmpty() && !f.isAdjustment) CurrencyMenu(f.currencyCode) { code -> c.model.edit { it.copy(currencyCode = code) } }
        }
    }
    if (f.isTransfer && ui.showFee) {
        FormRow("Fees", ui.panel == Panel.FEE, onClick = { focus.clearFocus(); ui.panel = Panel.FEE }) {
            Text(amountDisplay(f.feeText, ui.panel == Panel.FEE, c.model.fmt))
        }
    }
}

@Composable
private fun CurrencyMenu(code: String?, onPick: (String?) -> Unit) {
    val currency = LocalCurrency.current
    var open by remember { mutableStateOf(false) }
    Box {
        Text(code ?: tr("Main"), Modifier.border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .clickable { open = true }.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp)
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("${currency.symbol}  ${tr("Main currency")}") }, onClick = { onPick(null); open = false })
            LocalCurrencies.current.values.forEach { cur ->
                DropdownMenuItem(text = { Text("${cur.symbol}  ${cur.code}") }, onClick = { onPick(cur.code); open = false })
            }
        }
    }
}

@Composable
internal fun TransferRows(c: EditContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    FormRow("From", ui.panel == Panel.ACCOUNT, onClick = { focus.clearFocus(); ui.panel = Panel.ACCOUNT }, trailing = {
        IconButton(onClick = { c.model.swapAccounts() }) {
            Icon(Icons.Outlined.SwapVert, tr("Swap accounts"), tint = SubtleText)
        }
    }) { Text(c.accountName(f.accountId)) }
    FormRow("To", ui.panel == Panel.TO_ACCOUNT, onClick = { focus.clearFocus(); ui.panel = Panel.TO_ACCOUNT }) {
        Text(c.accountName(f.toAccountId))
    }
}

internal fun EditContext.accountName(id: String?): String = id?.let { i -> choices.accounts.firstOrNull { it.id == i }?.name }.orEmpty()

@Composable
internal fun CategoryAndAccountRows(c: EditContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    if (f.isAdjustment) FormRow("Category", false, onClick = {}) { Text(tr(MODIFIED_BALANCE_NAME)) }
    else FormRow("Category", ui.panel == Panel.CATEGORY, onClick = { focus.clearFocus(); ui.panel = Panel.CATEGORY },
        // Income tab only: a refund books money back against an expense category.
        trailing = if (f.type == DraftType.INCOME || f.type == DraftType.REFUND) ({ RefundBox(c) }) else null,
    ) { CategoryValue(c) }
    FormRow("Account", ui.panel == Panel.ACCOUNT, onClick = { focus.clearFocus(); ui.panel = Panel.ACCOUNT }) {
        Text(c.accountName(f.accountId))
    }
}

@Composable
private fun CategoryValue(c: EditContext) {
    val f = c.model.form
    val catById = c.choices.catById
    val learned by androidx.compose.runtime.produceState<String?>(null, f.note, f.type) { value = c.hints.learnedCategory(f.note, f.type) }
    val sug = f.suggestedCategory(learned, catById)
    if (f.categoryId == null && sug != null) {
        Text(tr("Suggested: {0}  ✓", categoryPath(sug, catById)), Modifier.clickable { c.model.edit { it.copy(categoryId = sug) } }, color = IncomeColor)
    } else Text(f.categoryId?.let { categoryPath(it, catById) }.orEmpty())
}

/** Long-press (or hover with a mouse) explains what the box does. */
@Composable
private fun RefundBox(c: EditContext) {
    val refund = c.model.form.type == DraftType.REFUND
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(Modifier.widthIn(max = 260.dp)) {
                Text(tr("Money back for a purchase, like a returned order. It lowers spending in the expense category instead of counting as income."))
            }
        },
        state = rememberTooltipState(),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                c.model.toggleRefund()
                c.model.applyHabitAccount(null)
                if (c.ui.panel == Panel.NONE) c.ui.panel = Panel.CATEGORY
            }.padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(refund, null, Modifier.size(36.dp))
            Text(tr("Refund"), fontSize = 13.sp, color = if (refund) IncomeColor else SubtleText)
        }
    }
}

/** Raw digits while typing on the keypad; grouped "₹ 10,000.00" otherwise. */
private fun amountDisplay(text: String, editing: Boolean, currency: CurrencyFormat): String = when {
    text.isEmpty() -> ""
    editing -> "${currency.symbol} $text"
    else -> currency.parse(text)?.let { currency.format(it) } ?: "${currency.symbol} $text"
}

/** "Label   value" row; the value area gets a coral underline while its panel is open. */
@Composable
internal fun FormRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr(label), Modifier.width(84.dp), color = SubtleText)
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)) { value() }
            }
            HorizontalDivider(color = if (active) Accent else Color.Transparent, thickness = 1.5.dp)
        }
        if (trailing != null) Box(Modifier.padding(start = 6.dp)) { trailing() }
    }
}
