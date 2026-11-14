package app.moneytracker.activity.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.Privacy
import app.moneytracker.core.tr
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.Mark
import app.moneytracker.designsystem.fresh.choiceTap

/** ‹ October 2026 ›; tapping the label opens the month picker. [under] spreads the arrows to the edges with it below the label. */
@Composable
internal fun ActPeriodSwitch(
    label: String, onPrev: () -> Unit, onNext: () -> Unit, onLabel: () -> Unit, tile: Boolean = false, minWidth: Int = 112,
    under: (@Composable () -> Unit)? = null,
) {
    val labelText = @Composable { m: Modifier ->
        Text(label, m.clip(RoundedCornerShape(10.dp)).clickable(onClick = onLabel).padding(horizontal = 4.dp, vertical = 8.dp),
            color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
    }
    if (under != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        ArrowButton(Glyph.ChevronLeft, tr("Previous"), tile, onPrev)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            labelText(Modifier.widthIn(min = minWidth.dp))
            under()
        }
        ArrowButton(Glyph.ChevronRight, tr("Next"), tile, onNext)
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ArrowButton(Glyph.ChevronLeft, tr("Previous"), tile, onPrev)
        labelText(Modifier.widthIn(min = minWidth.dp))
        ArrowButton(Glyph.ChevronRight, tr("Next"), tile, onNext)
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, tile: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(if (tile) 36.dp else 32.dp, 36.dp).clip(RoundedCornerShape(10.dp)).background(if (tile) F.page else Color.Transparent)
            .semantics { contentDescription = label }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = F.ink, modifier = Modifier.size(20.dp)) }
}

/** Money in · Money out · Net in a white strip. */
@Composable
internal fun ActSummary(income: Long, expense: Long, modifier: Modifier = Modifier, background: Color = F.card) {
    val currency = LocalCurrency.current
    // While amounts are hidden, a tap peeks at them for a few seconds.
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background)
        .clickable(enabled = Privacy.masked) { Privacy.peek() }.padding(vertical = 10.dp, horizontal = 4.dp)) {
        SummaryCell(tr("Money in"), currency.format(income), F.income)
        Box(Modifier.width(1.dp).height(36.dp).background(F.line))
        SummaryCell(tr("Money out"), currency.format(expense), F.expense)
        Box(Modifier.width(1.dp).height(36.dp).background(F.line))
        SummaryCell(tr("Net"), currency.format(income - expense), F.ink)
    }
}

@Composable
private fun RowScope.SummaryCell(label: String, value: String, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = F.muted, fontSize = 12.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

/**
 * A small pill: a plain button without a [role], an on/off filter with [Role.Checkbox], or one choice of several with
 * [Role.RadioButton] inside a `selectableGroup()`.
 */
@Composable
internal fun ActChip(text: String, selected: Boolean, role: Role? = null, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(F.pillShape).background(if (selected) F.accentTint else F.card)
            .then(if (selected) Modifier else Modifier.border(1.dp, F.border, F.pillShape))
            .choiceTap(selected, role, onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) F.accent else F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

internal class ActOption(val label: String, val sub: String, val mark: String?, val markName: String = label, val onClick: () -> Unit)

/** Two columns of bordered choice tiles with a mark (the bulk-edit sheets). */
@Composable
internal fun ActOptionGrid(options: List<ActOption>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { o -> OptionTile(o) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowScope.OptionTile(o: ActOption) {
    Row(
        Modifier.weight(1f).heightIn(min = 52.dp).clip(F.iconShape).border(1.dp, F.border, F.iconShape)
            .clickable(role = Role.Button, onClick = o.onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (o.mark != null) Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(F.accentTint), contentAlignment = Alignment.Center) {
            Text(o.mark, color = F.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        } else Mark(o.markName, 30.dp)
        Column(Modifier.weight(1f)) {
            Text(o.label, color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (o.sub.isNotEmpty()) Text(o.sub, color = F.muted, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}
