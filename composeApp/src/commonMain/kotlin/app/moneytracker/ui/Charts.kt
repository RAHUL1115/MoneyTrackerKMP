package app.moneytracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Line over evenly spaced points with a soft fill and dots; labels under each point. Zero line drawn when values cross it. */
@Composable
fun LineChart(values: List<Long>, labels: List<String>, color: Color = Accent, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 18.dp, vertical = 10.dp)) {
            val max = maxOf(values.max(), 0L).toFloat()
            val min = minOf(values.min(), 0L).toFloat()
            val span = (max - min).takeIf { it > 0f } ?: 1f
            val stepX = if (values.size > 1) size.width / (values.size - 1) else 0f
            fun y(v: Long) = size.height - (v - min) / span * size.height
            val pts = values.mapIndexed { i, v -> Offset(if (values.size > 1) i * stepX else size.width / 2, y(v)) }
            // Baseline at zero.
            drawLine(DividerColor, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 2f)
            if (pts.size > 1) {
                val line = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                val fill = Path().apply { addPath(line); lineTo(pts.last().x, y(0)); lineTo(pts[0].x, y(0)); close() }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
                drawPath(line, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            pts.forEach { drawCircle(SurfaceColor, 7f, it); drawCircle(color, 7f, it, style = Stroke(4f)) }
        }
        AxisLabels(labels)
    }
}

/** Paired bars per period (e.g. deposit vs withdrawal, income vs expense). */
@Composable
fun PairBarChart(a: List<Long>, b: List<Long>, labels: List<String>, colorA: Color = IncomeColor, colorB: Color = ExpenseColor, modifier: Modifier = Modifier) {
    if (a.isEmpty()) return
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(130.dp).padding(horizontal = 6.dp, vertical = 10.dp)) {
            val max = (a + b).maxOrNull()?.coerceAtLeast(1L)?.toFloat() ?: 1f
            val slot = size.width / a.size
            val barW = (slot * 0.28f).coerceAtMost(28f)
            a.indices.forEach { i ->
                val cx = slot * i + slot / 2
                listOf(a[i] to colorA, b[i] to colorB).forEachIndexed { j, (v, c) ->
                    val h = v / max * size.height
                    val x = if (j == 0) cx - barW - 2f else cx + 2f
                    if (h > 0f) drawRoundRect(c, Offset(x, size.height - h), Size(barW, h), CornerRadius(6f, 6f))
                }
            }
            drawLine(DividerColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        }
        AxisLabels(labels)
    }
}

/**
 * What each chart on a screen last animated to, kept above its scrolling list. Lazy rows forget their state when
 * scrolled away; with this, a chart scrolled back into view shows its data at once instead of animating again.
 */
class ChartMemory { internal val shown = mutableMapOf<String, Any?>() }
val LocalChartMemory = androidx.compose.runtime.staticCompositionLocalOf<ChartMemory?> { null }

/** 0 → 1 over [durationMs] each time [key] changes, so a chart redraws itself growing in; [id] names it in [LocalChartMemory]. */
@Composable
fun rememberGrowIn(key: Any?, durationMs: Int = 700, id: String? = null): Float {
    val memory = LocalChartMemory.current?.takeIf { id != null }
    val slot = "grow:$id"
    val progress = remember { Animatable(if (memory != null && memory.shown[slot] == key) 1f else 0f) }
    LaunchedEffect(key) {
        if (memory != null) {
            if (memory.shown[slot] == key) { progress.snapTo(1f); return@LaunchedEffect }
            memory.shown[slot] = key
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return progress.value
}

/** Rounded bars on soft tracks; [highlight] stands out and the rest are faded. Bars grow in (left to right) when the data changes; tapping one calls [onBarClick]. */
@Composable
fun BarChart(
    values: List<Long>, labels: List<String>, color: Color, highlight: Int? = null,
    modifier: Modifier = Modifier, onBarClick: ((Int) -> Unit)? = null, id: String? = null,
) {
    if (values.isEmpty()) return
    Column(modifier) {
        val tap = if (onBarClick == null) Modifier else Modifier.pointerInput(values.size) {
            detectTapGestures { o -> onBarClick((o.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)) }
        }
        val grow = rememberGrowIn(values to highlight, durationMs = 650 + values.size * 12, id = id)
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 6.dp, vertical = 8.dp).then(tap)) {
            val max = values.max().coerceAtLeast(1L).toFloat()
            val slot = size.width / values.size
            val barW = (slot * 0.56f).coerceAtMost(30.dp.toPx())
            val radius = CornerRadius(minOf(6.dp.toPx(), barW / 2))
            values.forEachIndexed { i, v ->
                val x = slot * i + (slot - barW) / 2
                // Tracks only while bars are wide enough to read as columns.
                if (values.size <= 12) drawRoundRect(FillColor, Offset(x, 0f), Size(barW, size.height), radius)
                // Each bar starts a little after the one before it.
                val t = ((grow * (1f + values.size * 0.04f)) - i * 0.04f).coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t)
                val h = if (v > 0) (v / max * size.height).coerceAtLeast(3.dp.toPx()) * eased else 0f
                val c = if (highlight == null || i == highlight) color else color.copy(alpha = 0.4f)
                if (h > 0f) drawRoundRect(c, Offset(x, size.height - h), Size(barW, h), radius)
            }
        }
        AxisLabels(labels)
    }
}

/**
 * Ring of slices (chart palette order) with small gaps that sweeps round when the data changes; [center] is drawn in the hole.
 * With [onSelect], tapping a slice selects it (it thickens and the others fade); tapping it again or the hole clears it.
 */
@Composable
fun DonutChart(
    values: List<Long>, modifier: Modifier = Modifier, id: String? = null,
    selected: Int? = null, onSelect: ((Int?) -> Unit)? = null, center: @Composable () -> Unit = {},
) {
    val total = values.sum().toFloat()
    val sweepIn = rememberGrowIn(values, durationMs = 900, id = id)
    val focus by androidx.compose.animation.core.animateFloatAsState(if (selected != null) 1f else 0f, tween(250), label = "focus")
    val tap = if (onSelect == null || total <= 0f) Modifier else Modifier.pointerInput(values, selected) {
        detectTapGestures { o ->
            val cx = size.width / 2f; val cy = size.height / 2f
            val dx = o.x - cx; val dy = o.y - cy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val outer = minOf(size.width, size.height) / 2f
            // The ring, with some slack so a finger doesn't have to be exact; the hole clears the selection.
            if (dist < outer * 0.55f || dist > outer * 1.1f) { onSelect(null); return@detectTapGestures }
            val angle = ((kotlin.math.atan2(dy, dx) * 180.0 / kotlin.math.PI + 90.0 + 360.0) % 360.0).toFloat()
            var start = 0f
            val hit = values.indices.firstOrNull { i -> val sweep = values[i] / total * 360f; (angle >= start && angle < start + sweep).also { start += sweep } }
            onSelect(if (hit == selected) null else hit)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().then(tap)) {
            val stroke = size.minDimension * 0.15f
            val d = size.minDimension - stroke * 1.4f
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(FillColor, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
            if (total <= 0f) return@Canvas
            val gap = if (values.count { it > 0 } > 1) 1.5f else 0f
            val shown = 360f * sweepIn
            var start = 0f
            values.forEachIndexed { i, v ->
                val sweep = v / total * 360f
                val visible = minOf(sweep, shown - start)
                if (visible > gap) {
                    val isSel = i == selected
                    val color = ChartColors[i % ChartColors.size].let { if (selected != null && !isSel) it.copy(alpha = 1f - 0.65f * focus) else it }
                    drawArc(color, start - 90f + gap / 2, visible - gap, false, topLeft, Size(d, d),
                        style = Stroke(if (isSel) stroke * (1f + 0.4f * focus) else stroke))
                }
                start += sweep
            }
        }
        center()
    }
}

@Composable
private fun AxisLabels(labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, color = SubtleText, textAlign = TextAlign.Center, maxLines = 1) }
    }
}
