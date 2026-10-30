@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.moneytracker.ui.fresh

import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Calc
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.tr

// Building blocks of the entry editor (AddScreen.kt).

/** The amount text after pressing [k] on the calculator keypad. */
internal fun addPress(text: String, k: String, currency: CurrencyFormat): String {
    val ops = Calc.OPS
    // The number being typed is whatever follows the last operator.
    val head = text.dropLastWhile { it !in ops }
    val num = text.substring(head.length)
    return when (k) {
        "⌫" -> text.dropLast(1)
        "C" -> ""
        "=" -> Calc.evaluate(text, currency.decimals) ?: text
        "+", "−", "×", "÷" -> when {
            text.isEmpty() -> text
            text.last() in ops -> text.dropLast(1) + k
            text.last() == '.' -> text.dropLast(1) + k
            else -> text + k
        }
        "." -> if (currency.decimals == 0 || num.contains('.')) text else text + (if (num.isEmpty()) "0." else ".")
        else -> {
            val candidate = if (num == "0") head + k else text + k
            val newNum = candidate.substring(head.length)
            val frac = newNum.substringAfter('.', "")
            if (newNum.contains('.') && frac.length > currency.decimals) text
            else if (newNum.substringBefore('.').length > 12) text else candidate
        }
    }
}

/** A half-typed calculation ("120+30") counts as its result. */
internal fun addSettle(text: String, currency: CurrencyFormat): String =
    if (Calc.hasOperator(text)) Calc.evaluate(text, currency.decimals) ?: text else text

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
    Column(modifier.heightIn(min = 216.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.chunked(4).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    val op = k in Calc.OPS
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                            .combinedClickable(
                                onClickLabel = aria[k] ?: k,
                                onLongClick = if (k == "⌫") ({ onChange("") }) else null,
                                onClick = { onChange(addPress(text, k, currency)) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") Icon(Icons.AutoMirrored.Outlined.Backspace, aria[k], tint = F.ink, modifier = Modifier.size(24.dp))
                        else Text(k, color = if (op) F.accent else F.ink, fontSize = if (op) 21.sp else 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Category or account tile: coloured mark over the name. No outline at rest; the chosen one is tinted with a thin ring. */
@Composable
internal fun AddTile(name: String, label: String, selected: Boolean, modifier: Modifier = Modifier, mark: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val (bg, fg) = markColors(name)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.height(70.dp).clip(shape).background(if (selected) F.accentTint else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, F.accent, shape) else Modifier).clickable(onClick = onClick).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) F.card else if (mark != null) F.fill else bg), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, tint = if (selected) F.accent else fg, modifier = Modifier.size(18.dp))
            else Text(mark ?: markText(name), color = if (mark != null) F.muted else fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Equal-width tiles, [columns] per row. */
@Composable
internal fun <T> AddGrid(items: List<T>, columns: Int = 4, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Small rounded chip (date choices, filters inside the editor). */
@Composable
internal fun AddChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) F.accentTint else F.card)
            .border(1.dp, if (selected) F.accentTint else F.border, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
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
) {
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(F.page)
            .padding(start = 12.dp, end = if (trailing != null) 8.dp else 12.dp),
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
    Text(text, modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
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
    val platform = LocalPlatform.current
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(start.coerceIn(0, (files.size - 1).coerceAtLeast(0))) { files.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                platform.PhotoFull(files[page], Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(F.iconShape).clickable(onClickLabel = tr("Close photo"), onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, tr("Close photo"), tint = Color.White)
                }
                Text(tr("Receipt {0} of {1}", pager.currentPage + 1, files.size), Modifier.weight(1f).padding(start = 8.dp),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = { onRemove(pager.currentPage) }) { Text(tr("Remove"), color = Color.White, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
