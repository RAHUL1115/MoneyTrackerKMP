package app.moneytracker.insights.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalCurrency
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.categoryPath
import app.moneytracker.designsystem.classic.label
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.Mark
import app.moneytracker.designsystem.fresh.MoneyTextField
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SheetOptionGroup
import app.moneytracker.designsystem.fresh.choiceTap
import app.moneytracker.insights.BudgetState
import app.moneytracker.insights.budgetAmount

/** Amount box with a compact Save beside it; [link] on the left and Remove (red text) on the right below. */
@Composable
internal fun AmountEditor(label: String, initial: Long?, onRemove: (() -> Unit)?, link: (@Composable () -> Unit)? = null, onSave: (Long) -> Unit) {
    val currency = LocalCurrency.current
    var text by remember(initial) { mutableStateOf(initial?.let { currency.toInput(it) }.orEmpty()) }
    val value = budgetAmount(text, currency)
    val enabled = value != null && (value > 0 || onRemove != null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyTextField(label, text, { text = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, placeholder = currency.format(0))
            Box(
                Modifier.height(44.dp).clip(F.innerShape).background(if (enabled) F.accent else F.fill)
                    .clickable(enabled = enabled, role = Role.Button) { if (value != null) { if (value == 0L) onRemove?.invoke() else onSave(value) } }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tr("Save"), color = if (enabled) F.onAccent else F.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (link != null || onRemove != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { link?.invoke() }
            if (onRemove != null) Text(tr("Remove budget"),
                Modifier.clip(F.innerShape).clickable(role = Role.Button, onClick = onRemove).padding(horizontal = 10.dp, vertical = 8.dp),
                color = F.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Sheet to add a category budget: pick a category without one, type the monthly amount. */
@Composable
internal fun AddBudgetSheet(state: BudgetState, nameOf: (String) -> String, onDismiss: () -> Unit, onAdd: (key: String, amount: Long) -> Unit) {
    val currency = LocalCurrency.current
    val options = state.unbudgeted
    var picked by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    val value = currency.parse(amount)?.takeIf { it > 0 }
    Sheet(tr("Add a category budget"), onDismiss, bottom = {
        val key = picked
        PrimaryButton(
            when { key == null -> tr("Pick a category"); value == null -> tr("Enter an amount"); else -> tr("Add {0} budget", nameOf(key)) },
            { if (key != null && value != null) onAdd(key, value) },
            enabled = key != null && value != null,
        )
    }) {
        Text(tr("Applies from {0} onward.", state.month.label()), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        if (options.isEmpty()) Text(tr("Every category already has a budget."), color = F.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        // Three columns of tiles; the picked one gets an accent outline. One group of choices for TalkBack.
        if (options.isNotEmpty()) SheetOptionGroup {
            options.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c -> CategoryTile(categoryPath(c.id, state.catById), c.name, picked == c.id, Modifier.weight(1f)) { picked = c.id } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Gap(8.dp)
        MoneyTextField(tr("Monthly amount"), amount, { amount = it }, placeholder = currency.format(0), keyboard = KeyboardType.Decimal)
    }
}

/** A category in the add-budget sheet: mark and name in a white tile, outlined in the accent when picked. */
@Composable
private fun CategoryTile(label: String, markName: String, picked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = F.optionShape
    Row(
        modifier.height(48.dp).clip(shape).background(if (picked) F.accentTint else F.card)
            .border(if (picked) 2.dp else 1.dp, if (picked) F.accent else F.border, shape).choiceTap(picked, Role.RadioButton, onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mark(markName, 26.dp)
        Text(label, color = if (picked) F.accent else F.ink, fontSize = 14.sp, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
