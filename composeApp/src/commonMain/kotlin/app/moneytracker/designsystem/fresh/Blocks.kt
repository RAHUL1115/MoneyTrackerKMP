package app.moneytracker.designsystem.fresh

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The new layout's building blocks: cards, rows, buttons, pills, switches and spacing.
//
// How the pieces fit:
// - A card of rows is an [FListCard]: no side padding of its own. Each [FRow] / [FSwitchRow] and [Divider] inside it
//   gets the screen's row inset (12, 14, 16 or 18dp, whatever the screen uses today), so a tapped row highlights the
//   card's full width and follows its rounded corners.
// - A row that turns something on or off is one target: the whole row is the switch (or checkbox), and the control
//   inside it only draws ([FSwitch] with a null callback).
// - Choices announce themselves as choices: [Segmented] as tabs, [Pill] and [SheetOption] as selectable items.

/** A white rounded card with [padding] inside on every side; tappable as a whole when [onClick] is set. Cards of rows use [FListCard]. */
@Composable
fun FCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = F.cardPadding, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(F.cardShape).background(F.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
        content = content,
    )
}

/**
 * A card that holds rows ([FRow], [FSwitchRow], entry rows). It has no side padding: every row and [Divider] inside
 * pads itself with the screen's inset, so a row's tap highlight runs edge to edge.
 *
 * `FCard(padding = 0.dp) { Column(Modifier.padding(horizontal = N, vertical = V)) { … } }` becomes
 * `FListCard(verticalPadding = V) { … }` with `inset = N` on each row and divider — the same pixels. A child that does
 * not fill the width (a plain [Text]) needs its own `Modifier.padding(horizontal = N)`.
 */
@Composable
fun FListCard(modifier: Modifier = Modifier, verticalPadding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(F.cardShape).background(F.card).padding(vertical = verticalPadding), content = content)
}

/** Card title row: bold title, optional action text on the right. */
@Composable
fun CardTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (action != null && onAction != null) Text(action, Modifier.clip(F.tagShape).clickable(role = Role.Button, onClick = onAction).padding(6.dp),
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
        modifier.fillMaxWidth().height(52.dp).clip(F.buttonShape).background(bg).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) F.onAccent else F.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, filled: Boolean = false) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(F.buttonShape).background(if (filled) F.page else F.card)
            .then(if (filled) Modifier else Modifier.border(1.dp, F.border, F.buttonShape)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (danger) F.danger else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/**
 * Rounded pill; soft accent tint when selected. Without a [role] it is a plain button (date pills, "Doesn't repeat").
 * Give it the role of what it is so TalkBack says so: [Role.RadioButton] for one choice of several (put the pills
 * in a `Modifier.selectableGroup()` parent), [Role.Checkbox] for an on/off pill such as a tag.
 */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null, role: Role? = null) {
    Row(
        modifier.height(34.dp).clip(F.pillShape)
            .background(if (selected) F.accentTint else F.card)
            .then(if (selected) Modifier else Modifier.border(1.dp, F.border, F.pillShape))
            .choiceTap(selected, role, onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = if (selected) F.accent else F.ink, modifier = Modifier.size(16.dp))
        Text(text, color = if (selected) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * The tap of something that may be chosen: a plain click without a [role]; on/off ([Role.Checkbox], [Role.Switch])
 * as a toggle; any other role as one selectable choice. The ripple and the click are the same in every case.
 */
fun Modifier.choiceTap(selected: Boolean, role: Role?, onClick: () -> Unit): Modifier = when (role) {
    null -> clickable(onClick = onClick)
    Role.Checkbox, Role.Switch -> toggleable(selected, role = role, onValueChange = { onClick() })
    else -> selectable(selected, role = role, onClick = onClick)
}

/** Segmented switch (Week / Month / Year, Expense / Income / Transfer). TalkBack reads it as tabs, the selected one marked. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(42.dp).clip(F.controlShape).background(F.track).padding(4.dp).selectableGroup()) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(F.innerShape).background(if (on) F.card else Color.Transparent)
                    .selectable(on, role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) F.ink else F.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) }
        }
    }
}

/**
 * A row inside a card: optional leading, title + subtitle, trailing text, chevron. Tappable when [onClick] is set.
 *
 * In an [FListCard], pass the screen's row inset as [inset] (and the same inset to [Divider]): the row pads itself, so
 * its highlight spans the card. [extraTop] / [extraBottom] move the card's top / bottom spacing inside the first /
 * last row, so that row's highlight also reaches the card's rounded edge; the row looks exactly as before.
 * A row with a switch is an [FSwitchRow].
 */
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
    modifier: Modifier = Modifier,
    inset: Dp = 0.dp,
    extraTop: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
) {
    val tap = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    FRowLayout(modifier.fillMaxWidth().then(tap).rowSpacing(inset, extraTop, extraBottom), title, subtitle, value, valueColor, leading) {
        trailing?.invoke()
        if (chevron && onClick != null && trailing == null) Icon(Glyph.ChevronRight, null, tint = F.muted, modifier = Modifier.size(20.dp))
    }
}

/**
 * An [FRow] with a switch at the end, drawn exactly like `FRow(title, subtitle, onClick = …, trailing = { FSwitch(…) })`.
 * The whole row is one switch for taps and TalkBack; the drawn [FSwitch] takes no taps of its own.
 * [inset], [extraTop] and [extraBottom] work as on [FRow].
 */
@Composable
fun FSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    inset: Dp = 0.dp,
    extraTop: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
) {
    val tap = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    FRowLayout(modifier.fillMaxWidth().then(tap).rowSpacing(inset, extraTop, extraBottom), title, subtitle, null, F.muted, leading) {
        FSwitch(checked, null)
    }
}

/** A row's spacing inside its tap area: card spacing first, then the 52dp minimum height, then the row's own padding. */
private fun Modifier.rowSpacing(inset: Dp, extraTop: Dp, extraBottom: Dp): Modifier =
    padding(top = extraTop, bottom = extraBottom).heightIn(min = 52.dp).padding(horizontal = inset, vertical = 8.dp)

/** What [FRow] and [FSwitchRow] draw; [end] comes after the value. */
@Composable
private fun FRowLayout(
    rowModifier: Modifier, title: String, subtitle: String?, value: String?, valueColor: Color,
    leading: (@Composable () -> Unit)?, end: @Composable RowScope.() -> Unit,
) {
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = F.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
        end()
    }
}

/**
 * On/off switch: 46×28, teal when on, a clear grey when off. Inside a row, pass a null [onChange] and make the row the
 * target ([FSwitchRow], or `Modifier.toggleable(role = Role.Switch)` on a custom row); its size is the same either way.
 */
@Composable
fun FSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) 21.dp else 3.dp, label = "switch")
    Box(
        modifier.size(46.dp, 28.dp).clip(F.pillShape).background(if (checked) F.accent else F.switchOff)
            .then(if (onChange != null) Modifier.toggleable(checked, onValueChange = onChange, role = Role.Switch) else Modifier),
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

/** 1dp hairline between rows. In an [FListCard], give it the rows' inset so it keeps its length: `Divider(inset = 14.dp)`. */
@Composable
fun Divider(inset: Dp = 0.dp, modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().padding(horizontal = inset).height(1.dp).background(F.line))

/** Progress bar on a card or the hero (pass track/fill for the hero's white-on-green look). */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, fill: Color = F.accent, track: Color = F.track, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(F.pillShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxSize().clip(F.pillShape).background(fill))
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
