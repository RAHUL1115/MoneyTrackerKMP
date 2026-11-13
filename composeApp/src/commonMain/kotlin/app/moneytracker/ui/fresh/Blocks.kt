package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The new layout's building blocks: cards, rows, buttons, pills, switches and spacing.

@Composable
fun FCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(F.cardShape).background(F.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
        content = content,
    )
}

/** Card title row: bold title, optional action text on the right. */
@Composable
fun CardTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (action != null && onAction != null) Text(action, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp),
            color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Small grey label above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier.padding(start = 4.dp, top = 8.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

/** The one main action at the bottom of a screen or sheet. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val bg = when { !enabled -> F.fill; danger -> F.danger; else -> F.accent }
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(F.buttonShape).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) F.onAccent else F.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, filled: Boolean = false) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(F.buttonShape).background(if (filled) F.page else F.card)
            .then(if (filled) Modifier else Modifier.border(1.dp, F.border, F.buttonShape)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (danger) F.danger else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** Rounded pill; soft accent tint when selected. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    Row(
        modifier.height(34.dp).clip(RoundedCornerShape(999.dp))
            .background(if (selected) F.accentTint else F.card)
            .then(if (selected) Modifier else Modifier.border(1.dp, F.border, RoundedCornerShape(999.dp)))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = if (selected) F.accent else F.ink, modifier = Modifier.size(16.dp))
        Text(text, color = if (selected) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Horizontally scrolling row of pills. */
@Composable
fun PillRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)

/** Segmented switch (Week / Month / Year, Expense / Income / Transfer). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp)).background(F.track).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (on) F.card else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) F.ink else F.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) }
        }
    }
}

/** A tappable row inside a card: optional leading, title + subtitle, trailing text, chevron. */
@Composable
fun FRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = F.muted,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 52.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = F.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
        trailing?.invoke()
        if (chevron && onClick != null && trailing == null) Icon(Glyph.ChevronRight, null, tint = F.muted, modifier = Modifier.size(20.dp))
    }
}

/** On/off switch: 46×28, teal when on, a clear grey when off. */
@Composable
fun FSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) 21.dp else 3.dp, label = "switch")
    Box(
        Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(999.dp)).background(if (checked) F.accent else F.switchOff)
            .then(if (onChange != null) Modifier.toggleable(checked, onValueChange = onChange, role = androidx.compose.ui.semantics.Role.Switch) else Modifier),
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun Divider(inset: Dp = 0.dp) = Box(Modifier.fillMaxWidth().padding(horizontal = inset).height(1.dp).background(F.line))

/** Progress bar on a card or the hero (pass track/fill for the hero's white-on-green look). */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, fill: Color = F.accent, track: Color = F.track, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(999.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxSize().clip(RoundedCornerShape(999.dp)).background(fill))
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

/** A horizontally scrolling row that runs past the screen's side margins, so it reads as scrollable. */
fun Modifier.bleedScroll(state: androidx.compose.foundation.ScrollState): Modifier = layout { measurable, constraints ->
    val extra = (F.gutter * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val p = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, p.height) { p.place(-extra / 2, 0) }
}.horizontalScroll(state).padding(horizontal = F.gutter)

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))
