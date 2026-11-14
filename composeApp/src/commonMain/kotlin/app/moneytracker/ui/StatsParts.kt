package app.moneytracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.FillColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.LocalChartMemory
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.domain.CurrencyFormat
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Counts from the last shown amount to [target] whenever it changes; settles on the exact value. [id] names it in [LocalChartMemory]. */
@Composable
internal fun animatedAmount(target: Long, id: String): Long {
    val memory = LocalChartMemory.current
    val key = "amount:$id"
    val last = memory?.shown?.get(key) as? Long
    val anim = remember { Animatable((last ?: 0L).toFloat()) }
    var settled by remember { mutableStateOf(last == target) }
    LaunchedEffect(target) {
        if (memory != null && memory.shown[key] == target) { anim.snapTo(target.toFloat()); settled = true; return@LaunchedEffect }
        memory?.shown?.set(key, target)
        settled = false
        anim.animateTo(target.toFloat(), tween(700, easing = FastOutSlowInEasing))
        settled = true
    }
    return if (settled) target else anim.value.roundToLong()
}

/** "▲ 12%" against the previous period; green when the change is good (less spending, more income). */
@Composable
internal fun ChangeChip(current: Long, previous: Long, upIsGood: Boolean, compact: Boolean = false) {
    if (previous <= 0L) {
        if (current > 0 && !compact) Text(tr("New"), Modifier.background(FillColor, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
            color = InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        return
    }
    val pct = ((current - previous) * 100.0 / previous).roundToInt()
    val color = when {
        pct == 0 -> SubtleText
        (pct > 0) == upIsGood -> IncomeColor
        else -> ExpenseColor
    }
    val text = "${if (pct > 0) "▲" else if (pct < 0) "▼" else "="} ${kotlin.math.abs(pct)}%"
    if (compact) Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    else Text(text, Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun MiniStat(label: String, value: String, color: Color, modifier: Modifier, note: String? = null) {
    Column(modifier) {
        Text(label, color = SubtleText, fontSize = 12.sp, maxLines = 1)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (note != null) Text(note, color = SubtleText, fontSize = 11.sp, maxLines = 1)
    }
}

/** A breakdown row: colour dot, name and amount, then a share bar with the percentage and the change. */
@Composable
internal fun SliceRow(
    slice: BreakdownPart, name: String, color: Color, total: Long, income: Boolean, currency: CurrencyFormat,
    highlighted: Boolean, dimmed: Boolean, open: (() -> Unit)?, extraBottom: Dp = 0.dp,
) {
    val share = if (total > 0) slice.amount.coerceAtLeast(0).toFloat() / total else 0f
    val pct = (share * 100).roundToInt()
    // The bar slides to its new width when the period or grouping changes.
    val shownShare by animateFloatAsState(share.coerceIn(0f, 1f), tween(700), label = "share")
    Row(
        // [extraBottom] (the card's spacing under its last row) is inside the tap area but outside the picked tint.
        Modifier.fillMaxWidth().clickable(enabled = open != null) { open?.invoke() }.padding(bottom = extraBottom)
            .background(if (highlighted) color.copy(alpha = 0.08f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 11.dp)
            .then(if (dimmed) Modifier.alpha(0.45f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(currency.format(slice.amount), Modifier.padding(start = 8.dp), color = Ink, fontWeight = FontWeight.Medium)
            }
            if (slice.refunded > 0) Text(tr("incl. {0} refunded", currency.format(slice.refunded)), color = IncomeColor, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(FillColor)) {
                    Box(Modifier.fillMaxWidth(shownShare).height(4.dp).clip(RoundedCornerShape(50)).background(color))
                }
                Text(if (pct == 0 && slice.amount > 0) "<1%" else "$pct%", Modifier.padding(start = 8.dp), color = SubtleText, fontSize = 12.sp)
                if (slice.previous > 0) {
                    Spacer(Modifier.width(8.dp))
                    ChangeChip(slice.amount, slice.previous, upIsGood = income, compact = true)
                }
            }
        }
    }
}
