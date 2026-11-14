@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.tr
import app.moneytracker.domain.Calc
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.pressKey



// Building blocks of the entry editor (AddScreen.kt).

/** "1200+30" → "1200 + 30" for the big amount line. */
internal fun addExprShown(text: String): String =
    if (text.isEmpty()) "0" else text.map { if (it in Calc.OPS) " $it " else it.toString() }.joinToString("")

/**
 * 4 × 4 calculator keypad; long-press ⌫ clears. Digits sit on the page and the operators are teal glyphs.
 * Pass a weighted [modifier] to let the keys share the spare height (never shorter than 48dp).
 */
@Composable
internal fun AddKeypad(text: String, currency: CurrencyFormat, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf("7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", ".", "0", "⌫", "+")
    val aria = mapOf("÷" to tr("Divide"), "×" to tr("Multiply"), "−" to tr("Minus"), "+" to tr("Plus"), "⌫" to tr("Delete digit"), "." to tr("Decimal point"))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.chunked(4).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    val op = k in Calc.OPS
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(F.fabShape)
                            .combinedClickable(
                                role = Role.Button,
                                onLongClickLabel = if (k == "⌫") tr("Clear") else null,
                                onLongClick = if (k == "⌫") ({ onChange("") }) else null,
                                onClick = { onChange(pressKey(text, k, currency)) },
                            )
                            // The key's name is said once, by the key; the glyph drawn on it stays silent.
                            .semantics { contentDescription = aria[k] ?: k },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") Icon(Glyph.Backspace, null, tint = F.ink, modifier = Modifier.size(24.dp))
                        else Text(k, Modifier.clearAndSetSemantics {}, color = if (op) F.accent else F.ink, fontSize = if (op) 21.sp else 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** One choice in the entry tray: a wide row with an icon (or the name's mark), the full name, and a check when chosen. */
@Composable
internal fun PickRow(label: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.height(50.dp).clip(F.optionShape).background(if (selected) F.accentTint else F.page)
            .choiceTap(selected, Role.RadioButton, onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(32.dp).clip(F.innerShape).background(F.card), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, tint = if (selected) F.accent else F.muted, modifier = Modifier.size(18.dp))
            else Text(markText(label), Modifier.clearAndSetSemantics {}, color = if (selected) F.accent else F.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(label, Modifier.weight(1f), color = if (selected) F.accent else F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Glyph.Check, null, tint = F.accent, modifier = Modifier.size(18.dp))
    }
}

/** An account in the entry tray: full name, "Usual" when it is, and its balance (or what a card owes) on the right. */
@Composable
internal fun AccountPickRow(name: String, selected: Boolean, usual: Boolean, balance: String, balanceColor: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(F.controlShape).background(if (selected) F.accentTint else F.page)
            .choiceTap(selected, Role.RadioButton, onClick).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, Modifier.weight(1f, fill = false), color = if (selected) F.accent else F.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (usual) Box(Modifier.height(20.dp).clip(F.pillShape).background(F.card).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                Text(tr("Usual"), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Text(balance, color = balanceColor, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = F.money)
        Box(Modifier.size(18.dp)) { if (selected) Icon(Glyph.Check, null, tint = F.accent, modifier = Modifier.size(18.dp)) }
    }
}

/** Two equal columns of [PickRow]s. */
@Composable
internal fun <T> PickGrid(items: List<T>, cell: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A tab in the tray's header (Recent and the account groups); its parent is a selectable group. */
@Composable
internal fun TrayTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(F.pillShape).background(if (selected) F.accentTint else F.card)
            .choiceTap(selected, Role.Tab, onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/** Small rounded chip (date choices, filters inside the editor). */
@Composable
internal fun AddChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp).clip(F.controlShape).background(if (selected) F.accentTint else F.card)
            .border(1.dp, if (selected) F.accentTint else F.border, F.controlShape).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) F.accent else F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** Filled text input with a placeholder. */
@Composable
internal fun AddInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    number: Boolean = false,
    fieldModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    background: Color = F.page,
) {
    Row(
        modifier.heightIn(min = 48.dp).clip(F.optionShape).background(background)
            // Without a fill (inside a receipt row) the text lines up with the rows' values.
            .padding(start = if (background == Color.Transparent) 0.dp else 12.dp, end = if (trailing != null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (value.isEmpty()) Text(placeholder, color = F.muted, fontSize = 14.sp)
            BasicTextField(
                value, onChange, fieldModifier.fillMaxWidth(), singleLine = singleLine,
                textStyle = TextStyle(fontSize = 14.sp, color = F.ink),
                cursorBrush = SolidColor(F.accent),
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            )
        }
        trailing?.invoke()
    }
}

/** Accent text link ("Edit categories ›"). */
@Composable
internal fun AddLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Text(text, modifier.clip(F.tagShape).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)

@Composable
internal fun AddTimePicker(initial: Int, onDismiss: () -> Unit, onClear: () -> Unit, onPick: (Int) -> Unit) {
    val state = androidx.compose.material3.rememberTimePickerState(initial / 60, initial % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.card, shape = F.heroShape,
        text = { androidx.compose.material3.TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(tr("OK"), color = F.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text(tr("No time"), color = F.ink) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel"), color = F.ink) }
            }
        },
    )
}

/** Full-screen receipt photos: swipe between them, remove one. */
@Composable
internal fun AddPhotoViewer(files: List<String>, start: Int, onRemove: (Int) -> Unit, onDismiss: () -> Unit) {
    val photos = LocalPlatform.current.photos
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(start.coerceIn(0, (files.size - 1).coerceAtLeast(0))) { files.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                photos.PhotoFull(files[page], Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(F.iconShape).clickable(role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = tr("Close photo") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyph.Close, null, tint = Color.White)
                }
                Text(tr("Receipt {0} of {1}", pager.currentPage + 1, files.size), Modifier.weight(1f).padding(start = 8.dp),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = { onRemove(pager.currentPage) }) { Text(tr("Remove"), color = Color.White, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
