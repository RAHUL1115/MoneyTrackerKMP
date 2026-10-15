package app.moneytracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@Composable
private fun AxisLabels(labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, color = SubtleText, textAlign = TextAlign.Center, maxLines = 1) }
    }
}
