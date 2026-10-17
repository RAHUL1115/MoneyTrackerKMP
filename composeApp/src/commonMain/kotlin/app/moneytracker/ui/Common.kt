package app.moneytracker.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Category
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.YearMonth
import kotlinx.datetime.LocalDate

// ───────────────────────── palette

/** Dark style on/off (Configuration › Style). Snapshot state: every colour below re-reads it. */
var darkTheme by mutableStateOf(false)
private fun pick(light: Long, dark: Long) = Color(if (darkTheme) dark else light)

/** Accent colours offered in Configuration › Style: light, dark, tint light, tint dark. */
enum class AccentChoice(val label: String, val light: Long, val dark: Long, val tintLight: Long, val tintDark: Long) {
    /** The phone's wallpaper colours ([systemAccent]); teal where the OS has none. */
    SYSTEM("Follow system", 0xFF0F766E, 0xFF2DD4BF, 0xFFE6F4F2, 0xFF12302D),
    TEAL("Teal", 0xFF0F766E, 0xFF2DD4BF, 0xFFE6F4F2, 0xFF12302D),
    INDIGO("Indigo", 0xFF4F46E5, 0xFF8B93F8, 0xFFEEF0FE, 0xFF1F2140),
    AMBER("Amber", 0xFFB45309, 0xFFF5A524, 0xFFFDF3E4, 0xFF3A2A12),
    PLUM("Plum", 0xFF86198F, 0xFFD46BDD, 0xFFF7E8F8, 0xFF33183A),
    GRAPHITE("Graphite", 0xFF3F4450, 0xFFB7BCC6, 0xFFE8E9EC, 0xFF2A2C31),
}
var accentChoice by mutableStateOf(AccentChoice.TEAL)

/** Accent tones from the OS (Android 12+ wallpaper colours); null where unsupported. Set by the platform. */
data class SystemAccent(val light: Long, val dark: Long, val tintLight: Long, val tintDark: Long)
var systemAccent by mutableStateOf<SystemAccent?>(null)

/** The accent's four tones: the system's when chosen and available, else the swatch's own. */
fun AccentChoice.tones(): SystemAccent =
    systemAccent?.takeIf { this == AccentChoice.SYSTEM } ?: SystemAccent(light, dark, tintLight, tintDark)

/** Brand accent (teal unless changed): navigation, selection, primary actions. */
val Accent: Color get() = accentChoice.tones().let { pick(it.light, it.dark) }
/** Light tint of the accent for selected rows and chips. */
val AccentTint: Color get() = accentChoice.tones().let { pick(it.tintLight, it.tintDark) }
/** Destructive actions and errors. */
val DangerColor: Color get() = pick(0xFFD9473C, 0xFFF0675C)
val PageBackground: Color get() = pick(0xFFF3F4F6, 0xFF0F1012)
val HeaderBackground: Color get() = PageBackground
/** Cards, rows, bars: white in light style. */
val SurfaceColor: Color get() = pick(0xFFFFFFFF, 0xFF1A1B1E)
val SubtleText: Color get() = pick(0xFF8A8F99, 0xFF8F949E)
val DividerColor: Color get() = pick(0xFFECEDF0, 0xFF2A2C31)
/** Soft fills: segmented tracks, secondary buttons, chips. */
val FillColor: Color get() = pick(0xFFEEEFF2, 0xFF2A2C31)
val FillStrong: Color get() = pick(0xFFE6E8EC, 0xFF34373D)
val BorderColor: Color get() = pick(0xFFD6D8DD, 0xFF3A3D44)
/** Primary text: a soft near-black rather than pure black. */
val Ink: Color get() = pick(0xFF1B1C1F, 0xFFECEDEF)
/** Neutral amounts (totals, transfers). */
val InkSoft: Color get() = pick(0xFF45474D, 0xFFC4C7CE)
/** "Income-Expenses color" setting: Set B swaps the two. Snapshot state, so every reader recomposes. */
var colorsSwapped by mutableStateOf(false)
private val Green: Color get() = pick(0xFF16A34A, 0xFF4ADE80)
private val Rose: Color get() = pick(0xFFE5484D, 0xFFF87171)
val IncomeColor: Color get() = if (colorsSwapped) Rose else Green
val ExpenseColor: Color get() = if (colorsSwapped) Green else Rose

/** List display options from Configuration. */
data class ListPrefs(val showTime: Boolean = false, val showDescription: Boolean = false)
val LocalListPrefs = androidx.compose.runtime.compositionLocalOf { ListPrefs() }

val CardShape = RoundedCornerShape(16.dp)

/** Transaction lists: slim card margin + row inset, so columns line up on 16dp edges. */
val ListCardMargin = 6.dp
val ListRowInset = 10.dp

/** White rounded card inset from the screen edges — the app's basic grouping surface. */
fun Modifier.card(margin: Dp = 12.dp): Modifier = this.padding(horizontal = margin).clip(CardShape).background(SurfaceColor)

// ───────────────────────── formatting

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun YearMonth.label() = "${tr(monthNames[month - 1])} $year"
fun monthShort(m: Int) = tr(monthNames[m - 1])
fun LocalDate.short() = "$dayOfMonth ${tr(monthNames[monthNumber - 1])}"
fun LocalDate.weekday() = tr(dayNames[dayOfWeek.ordinal])
fun LocalDate.long() = "${weekday()}, $dayOfMonth ${tr(monthNames[monthNumber - 1])} $year"
fun LocalDate.numeric() = "${monthNumber.toString().padStart(2, '0')}/${dayOfMonth.toString().padStart(2, '0')}/${(year % 100).toString().padStart(2, '0')} (${weekday()})"
fun DateRange.label() = "${start.short()} – ${endInclusive.short()}"
fun DateRange.dotted() = "${start.monthNumber}.${start.dayOfMonth}.${start.year % 100}  ~  ${endInclusive.monthNumber}.${endInclusive.dayOfMonth}.${endInclusive.year % 100}"

// ───────────────────────── bars & headers

/** Plain white top bar: back arrow, title, trailing actions. */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).statusBarsPadding().height(52.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back")) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** Top bar with "‹ Oct 2026 ›" on the left and actions on the right. */
@Composable
fun MonthTopBar(
    label: String, onPrev: () -> Unit, onNext: () -> Unit, statusBar: Boolean = true,
    onLabelClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).then(if (statusBar) Modifier.statusBarsPadding() else Modifier).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous")) }
        Text(
            label,
            Modifier.clip(RoundedCornerShape(8.dp)).then(if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next")) }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun TitleTopBar(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).statusBarsPadding().height(52.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        trailing()
    }
}

/** Text tabs with a short rounded accent indicator under the selected label. */
@Composable
fun UnderlineTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(SurfaceColor)) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 4.dp)) {
            titles.forEachIndexed { i, t ->
                val isSel = i == selected
                // Beyond four tabs, width follows label length so every label stays fully visible on narrow phones.
                val weight = if (titles.size > 4) t.length + 3f else 1f
                Box(Modifier.weight(weight).fillMaxHeight().clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(
                        t, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        color = if (isSel) Ink else SubtleText,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.titleSmall,
                    )
                    if (isSel) Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).width(22.dp).height(3.dp)
                            .background(Accent, RoundedCornerShape(50)),
                    )
                }
            }
        }
        HorizontalDivider(color = DividerColor)
    }
}

/** "Income | Expenses | Total" strip, numbers without currency symbol. */
@Composable
fun SummaryStrip(
    items: List<Triple<String, Long, Color>>,
    currency: CurrencyFormat,
) {
    Column(Modifier.background(SurfaceColor)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 7.dp)) {
            items.forEach { (label, value, color) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 12.sp, color = SubtleText)
                    Text(
                        currency.format(value, withSymbol = false), Modifier.padding(top = 1.dp), color = color,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider(color = DividerColor)
    }
}

@Composable
fun IncomeExpenseStrip(income: Long, expense: Long, currency: CurrencyFormat) = SummaryStrip(
    listOf(
        Triple(tr("Income"), income, IncomeColor),
        Triple(tr("Expenses"), expense, ExpenseColor),
        Triple(tr("Total"), income - expense, InkSoft),
    ),
    currency,
)

/** Day header: big day number, tinted weekday chip, month.year, income/expense on the right. */
@Composable
fun DayHeader(date: LocalDate, income: Long, expense: Long, currency: CurrencyFormat, inset: Dp = 16.dp) {
    Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(start = inset, end = inset, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(date.dayOfMonth.toString(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(
            date.weekday(),
            Modifier.padding(start = 8.dp).background(AccentTint, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        )
        Text("${date.monthNumber.toString().padStart(2, '0')}.${date.year}", Modifier.padding(start = 6.dp), fontSize = 10.sp, color = SubtleText)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        // Only the sides that moved; a "₹ 0.00" column is noise on a busy list.
        if (income != 0L) Text(currency.format(income), color = IncomeColor, maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (income != 0L && expense != 0L) Spacer(Modifier.width(14.dp))
        // Below zero only when refunds outweigh the day's spending: money back, shown like a refund row.
        if (expense != 0L) Text(if (expense < 0) currency.format(-expense, showSign = true) else currency.format(expense), color = if (expense < 0) IncomeColor else ExpenseColor,
            maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(text: String = tr("No data available."), icon: ImageVector? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 120.dp, start = 40.dp, end = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) Box(Modifier.size(72.dp).background(FillColor, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = SubtleText)
        }
        Text(text, Modifier.padding(top = 12.dp), color = SubtleText, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

/** App-wide switch: coral when on, and a clearly outlined grey track when off (the default "off" is too faint on white). */
@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked, onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = Accent, checkedThumbColor = Color.White, checkedBorderColor = Accent,
            uncheckedTrackColor = FillStrong, uncheckedBorderColor = FillStrong, uncheckedThumbColor = Color.White,
        ),
    )
}

/** Zero amounts in tables read as noise; keep them legible but quiet. */
val ZeroColor: Color get() = pick(0xFFBDBDC2, 0xFF4A4D54)

/** Section header used in lists (grey band). */
@Composable
fun GreyHeader(left: String, right: String? = null, rightColor: Color = SubtleText) {
    Row(Modifier.fillMaxWidth().background(PageBackground).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(left, Modifier.weight(1f), color = SubtleText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (right != null) Text(right, color = rightColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Amount(minor: Long, currency: CurrencyFormat, color: Color = Color.Unspecified, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        currency.format(minor),
        color = color,
        modifier = modifier,
        textAlign = TextAlign.End,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** Small coloured "37%" badge used in stats lists. */
@Composable
fun PercentBadge(percent: Int, color: Color) {
    Text(
        "$percent%",
        Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
    )
}

/** Pill segmented control: a soft grey track, the selected segment raised in white with its type colour. */
@Composable
fun TypeSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp)
            .background(FillColor, RoundedCornerShape(14.dp)).padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            val color = if (isSel) (if (i == 0) IncomeColor else if (i == 1) ExpenseColor else Ink) else SubtleText
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .then(if (isSel) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)).background(SurfaceColor, RoundedCornerShape(10.dp)) else Modifier)
                    .clip(RoundedCornerShape(10.dp)).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = color, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium) }
        }
    }
}

// ───────────────────────── pickers (used by secondary forms)

/** Read-only text field that opens a menu. */
@Composable
fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T?,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionDisplay: (T) -> String = display,
) {
    var open by remember { mutableStateOf(false) }
    // Nullable option lists (e.g. "Don't repeat") still render their null choice.
    @Suppress("UNCHECKED_CAST")
    val shown = if (selected != null || (options as List<Any?>).contains(null)) display(selected as T) else ""
    Box(modifier) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionDisplay(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerHost(open: Boolean, date: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate?) -> Unit, clearable: Boolean = false) {
    if (!open) return
    val state = rememberDatePickerState(initialSelectedDateMillis = date?.let { it.toEpochDays().toLong() * MILLIS_PER_DAY })
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(LocalDate.fromEpochDays((it / MILLIS_PER_DAY).toInt())) }
                onDismiss()
            }) { Text(tr("OK")) }
        },
        dismissButton = {
            Row {
                if (clearable) TextButton(onClick = { onPick(null); onDismiss() }) { Text(tr("Clear")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    ) { DatePicker(state = state) }
}

@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier, clearable: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = date?.long() ?: tr("None"),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    DatePickerHost(open, date, { open = false }, onChange, clearable)
}

private const val MILLIS_PER_DAY = 86_400_000L

/** Main categories followed by their children — for pickers. */
fun flattenCategories(all: List<Category>): List<Category> {
    val mains = all.filter { it.parentId == null }
    return mains.flatMap { m -> listOf(m) + all.filter { it.parentId == m.id } }
}

fun categoryPath(id: String?, byId: Map<String, Category>): String {
    if (id == app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY) return tr(app.moneytracker.domain.MODIFIED_BALANCE_NAME)
    val c = id?.let { byId[it] } ?: return tr("Uncategorized")
    val parent = c.parentId?.let { byId[it] }
    return if (parent != null) "${parent.name} › ${c.name}" else c.name
}

fun mainCategoryName(id: String?, byId: Map<String, Category>): String {
    if (id == app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY) return tr(app.moneytracker.domain.MODIFIED_BALANCE_NAME)
    val c = id?.let { byId[it] } ?: return tr("Uncategorized")
    return c.parentId?.let { byId[it]?.name } ?: c.name
}

fun subCategoryName(id: String?, byId: Map<String, Category>): String? {
    val c = id?.let { byId[it] } ?: return null
    return if (c.parentId != null) c.name else null
}

@Composable
fun FullScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(PageBackground)) { content() }
}

/**
 * Month picker: drops down under the top bar (no dimming), dark header with "Date",
 * THIS MONTH and close, the year with ‹ ›, then the twelve months as plain text; the shown month is in the accent colour.
 */
@Composable
fun MonthPickerDialog(selected: YearMonth, current: YearMonth, onDismiss: () -> Unit, onPick: (YearMonth) -> Unit) {
    var year by remember { mutableStateOf(selected.year) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Just below the status bar and the 52dp top bar.
    val top = WindowInsets.statusBars.getTop(density) + with(density) { 50.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, top),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().shadow(10.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)).background(SurfaceColor).padding(bottom = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth().background(PickerHeader).height(52.dp).padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Date"), Modifier.weight(1f), color = Color.White, fontSize = 15.sp)
                Text(tr("THIS MONTH"), Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(current) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = onDismiss) { Icon(androidx.compose.material.icons.Icons.Filled.Close, tr("Close"), tint = Color.White) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous year"), tint = InkSoft) }
                Text(year.toString(), Modifier.weight(1f), textAlign = TextAlign.Center, color = InkSoft, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next year"), tint = InkSoft) }
            }
            (1..12).chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    row.forEach { m ->
                        val isSel = year == selected.year && m == selected.month
                        Box(
                            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(YearMonth(year, m)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(monthShort(m).uppercase(), color = if (isSel) Accent else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** Header of the month picker, in the accent colour. */
private val PickerHeader: Color get() = Accent
