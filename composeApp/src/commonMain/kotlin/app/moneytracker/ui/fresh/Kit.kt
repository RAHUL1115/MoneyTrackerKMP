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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.Accent
import app.moneytracker.ui.AccentChoice
import app.moneytracker.ui.AccentTint
import app.moneytracker.ui.accentChoice
import app.moneytracker.ui.colorsSwapped
import app.moneytracker.ui.darkTheme
import app.moneytracker.ui.tr
import kotlinx.coroutines.delay

// The new layout's look: calm greens on an off-white page, big rounded cards, one clear action per screen.
// Colours follow the app's dark style, accent choice and income/expense colour setting like the classic layout.

private fun pick(light: Long, dark: Long) = Color(if (darkTheme) dark else light)

object F {
    val page: Color get() = pick(0xFFF3F4F1, 0xFF0F1211)
    val card: Color get() = pick(0xFFFFFFFF, 0xFF1A1E1C)
    val ink: Color get() = pick(0xFF16201D, 0xFFE9EEEB)
    val muted: Color get() = pick(0xFF5D6964, 0xFF9AA6A1)
    val line: Color get() = pick(0xFFECEEEA, 0xFF2A302D)
    val border: Color get() = pick(0xFFE3E6E2, 0xFF333A36)
    val fill: Color get() = pick(0xFFEEF0EC, 0xFF252B28)
    private val teal get() = accentChoice == AccentChoice.TEAL || (accentChoice == AccentChoice.SYSTEM && app.moneytracker.ui.systemAccent == null)
    /** Primary actions, selection, the hero card. */
    val accent: Color get() = if (teal) pick(0xFF0F5E4E, 0xFF4FC2A6) else Accent
    val accentTint: Color get() = if (teal) pick(0xFFE3F2EE, 0xFF173A31) else AccentTint
    /** Text and icons on [accent]. */
    val onAccent: Color get() = if (darkTheme && teal) Color(0xFF07231C) else Color.White
    /** Over budget and the like: one soft warm tone, used sparingly. */
    val warn: Color get() = pick(0xFFC26B4E, 0xFFE59A7C)
    /** Soft background for things waiting on the user (bank SMS to review). */
    val warnTint: Color get() = pick(0xFFFFF1E6, 0xFF35241A)
    val danger: Color get() = pick(0xFFB4441C, 0xFFF08A5D)
    val dangerTint: Color get() = pick(0xFFFCE9E0, 0xFF3A2218)
    private val green get() = pick(0xFF157A3C, 0xFF5BD08A)
    private val red get() = pick(0xFFB4441C, 0xFFF08A5D)
    // Money in is green; money out reads in plain ink so lists stay calm. Swapped colours keep their old meaning.
    val income: Color get() = if (colorsSwapped) red else green
    val expense: Color get() = if (colorsSwapped) green else ink

    val cardShape = RoundedCornerShape(20.dp)
    val heroShape = RoundedCornerShape(24.dp)
    val buttonShape = RoundedCornerShape(16.dp)
    val iconShape = RoundedCornerShape(14.dp)
    /** Side margin of every screen. */
    val gutter = 16.dp

    val money = TextStyle(fontFeatureSettings = "tnum")
}

/** Category and account marks share one soft grey-green; the letters tell them apart. */
@Suppress("UNUSED_PARAMETER")
fun markColors(name: String): Pair<Color, Color> = pick(0xFFEEF1ED, 0xFF232927) to pick(0xFF46524D, 0xFFB7C2BD)

/** Two-letter mark: "Food" → "FO", "Credit card" → "CC". */
fun markText(name: String): String {
    val words = name.trim().split(' ', '/', '-', '&').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "·"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
fun Mark(name: String, size: Dp = 36.dp, icon: ImageVector? = null) {
    val (bg, fg) = markColors(name)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.31f)).background(bg), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(size * 0.5f))
        else Text(markText(name), color = fg, fontSize = (size.value * 0.33f).sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ───────────────────────── toasts

/**
 * Set while a screen wants the keyboard to draw over its lower part instead of the app resizing around it
 * (the entry editor: the keyboard covers its keypad and Save, and nothing else moves).
 */
object KeyboardOverlay {
    var active by mutableStateOf(false)
}

/** A short message at the bottom of the screen, optionally with Undo. One at a time. */
@Stable
class Toaster {
    var current by mutableStateOf<Toast?>(null)
        private set
    private var seq = 0
    fun show(message: String, undo: (() -> Unit)? = null) { current = Toast(++seq, message, undo) }
    fun dismiss() { current = null }
    data class Toast(val id: Int, val message: String, val undo: (() -> Unit)?)
}

val LocalToaster = compositionLocalOf { Toaster() }

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val t = toaster.current ?: return
    LaunchedEffect(t.id) { delay(if (t.undo != null) 5000 else 2500); if (toaster.current?.id == t.id) toaster.dismiss() }
    Row(
        modifier.padding(horizontal = F.gutter, vertical = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(F.ink).padding(start = 16.dp, end = 8.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.message, Modifier.weight(1f), color = F.page, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.undo != null) TextButton(onClick = { t.undo.invoke(); toaster.dismiss() }) {
            Text(tr("Undo"), color = F.accentTint, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
    }
}

// ───────────────────────── screen frames

/** Top of a main tab: big title, optional line above it, actions on the right. */
@Composable
fun TabHeader(title: String, overline: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter).padding(top = 18.dp, bottom = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (overline != null) Text(overline, color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(title, color = F.ink, fontSize = if (overline != null) 22.sp else 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** 44dp square white button holding an icon (header actions, back). */
@Composable
fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit, badge: Int = 0, tint: Color = F.ink, background: Color = F.card, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clip(F.iconShape).background(background).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp).clip(CircleShape).background(F.warn),
            contentAlignment = Alignment.Center,
        ) { Text(if (badge > 9) "9+" else badge.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

/**
 * A screen opened on top of the tabs: back tile + title, scrolling body, optional bottom action.
 * [scroll] = false when the body manages its own scrolling (lists).
 * [add] puts a "+ label" button at the bottom right, within reach of the thumb.
 */
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    add: Pair<String, () -> Unit>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(F.page).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), onBack)
            Text(title, Modifier.weight(1f), color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = if (scroll) F.gutter else 0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            if (scroll) Spacer(Modifier.height(if (add != null) 100.dp else 24.dp))
        }
        if (bottom != null) Box(Modifier.fillMaxWidth().background(F.page).padding(horizontal = F.gutter, vertical = 12.dp)) { bottom() }
    }
    if (add != null) Row(
        Modifier.align(Alignment.BottomEnd).padding(end = F.gutter, bottom = 20.dp).height(56.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(F.accent)
            .clickable(onClick = add.second).padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Add, null, tint = F.onAccent, modifier = Modifier.size(22.dp))
        Text(add.first, color = F.onAccent, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
    }
}

// ───────────────────────── building blocks

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
        Text(title, Modifier.weight(1f), color = F.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (action != null && onAction != null) Text(action, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp),
            color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Small grey label above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier.padding(start = 4.dp, top = 8.dp), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)

/** The one main action at the bottom of a screen or sheet. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val bg = when { !enabled -> F.fill; danger -> F.danger; else -> F.accent }
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(F.buttonShape).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) F.onAccent else F.muted, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(F.buttonShape).background(F.card).border(1.dp, F.border, F.buttonShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (danger) F.danger else F.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

/** Rounded pill; soft accent tint when selected. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(999.dp))
            .background(if (selected) F.accentTint else F.card)
            .then(if (selected) Modifier else Modifier.border(1.dp, F.border, RoundedCornerShape(999.dp)))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = if (selected) F.accent else F.ink, modifier = Modifier.size(16.dp))
        Text(text, color = if (selected) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Horizontally scrolling row of pills. */
@Composable
fun PillRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** Segmented switch (Week / Month / Year, Expense / Income / Transfer). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(44.dp).clip(F.iconShape).background(F.fill).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(11.dp)).background(if (on) F.card else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) F.ink else F.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1) }
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
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 13.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = F.muted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = F.money, maxLines = 1)
        trailing?.invoke()
        if (chevron && onClick != null && trailing == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = F.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(F.line))

/** Progress bar on a card or the hero (pass track/fill for the hero's white-on-green look). */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, fill: Color = F.accent, track: Color = F.fill, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(999.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxSize().clip(RoundedCornerShape(999.dp)).background(fill))
    }
}

// ───────────────────────── sheets and dialogs

/** Bottom sheet with a title; the body scrolls when tall. [half] opens a long list half-way; drag up for the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, bottom: (@Composable () -> Unit)? = null, half: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !half),
        containerColor = F.card, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(title, color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            if (bottom != null) { Spacer(Modifier.height(12.dp)); bottom() }
        }
    }
}

/** One choice in a sheet: tick when selected. */
@Composable
fun SheetOption(text: String, selected: Boolean = false, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) F.accentTint else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(text, color = F.ink, fontSize = 15.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = F.muted, fontSize = 12.sp)
        }
        if (selected) Text("✓", color = F.accent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Ask before something that can't be undone. Opens from the bottom so both buttons sit under the thumb. */
@Composable
fun Confirm(title: String, body: String, confirm: String, onDismiss: () -> Unit, danger: Boolean = true, onConfirm: () -> Unit) {
    Sheet(title, onDismiss, bottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(tr("Cancel"), onDismiss, Modifier.weight(1f))
            PrimaryButton(confirm, { onDismiss(); onConfirm() }, Modifier.weight(1f), danger = danger)
        }
    }) {
        Text(body, color = F.muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Empty list message inside a card. */
@Composable
fun EmptyCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    FCard {
        Text(text, color = F.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (action != null && onAction != null) { Spacer(Modifier.height(4.dp)); SecondaryButton(action, onAction) }
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
