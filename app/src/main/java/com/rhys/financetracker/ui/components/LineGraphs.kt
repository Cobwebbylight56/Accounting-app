package com.rhys.financetracker.ui.components

import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** How a series' points are marked, so two lines never differ by colour alone. */
enum class MarkerShape { CIRCLE, SQUARE }

/** One line on a [TrendLineChart]: a value, in pence, for each label. */
data class LineSeries(
    val name: String,
    val values: List<Long>,
    val color: Color,
    val dashed: Boolean = false,
    val marker: MarkerShape = MarkerShape.CIRCLE,
)

/**
 * A line graph over time, after the graph-paper style: light gridlines with
 * the amounts up the side, a dot on every month, a soft fill under each line,
 * and the months along the bottom.
 *
 * It draws itself in from left to right. Tap or slide along it to read every
 * line's figure for that month; [onPointClick] hears the tap too. A second
 * line is dashed with square dots, so the two can be told apart without
 * colour, and the last figure on each line is written beside it.
 */
@Composable
fun TrendLineChart(
    labels: List<String>,
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    selectedIndex: Int? = null,
    onPointClick: ((Int) -> Unit)? = null,
    /** The key under the graph; a single line is named by its card instead. */
    showLegend: Boolean = series.size > 1,
    /** The card behind the graph, for the ring round each dot. */
    surface: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    val points = labels.size
    if (points < 2 || series.isEmpty() || series.all { line -> line.values.all { it == 0L } }) {
        EmptyChartPlaceholder(modifier, "No figures for this period yet")
        return
    }

    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(series) {
        if (reduceMotion) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    var touched by remember(labels) { mutableStateOf<Int?>(null) }

    val all = series.flatMap { it.values }
    val ticks = remember(all) { niceTicks(minOf(0L, all.min()), maxOf(0L, all.max())) }
    val low = ticks.first()
    val high = ticks.last()

    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val zero = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBack = MaterialTheme.colorScheme.inverseSurface
    val tooltipInk = MaterialTheme.colorScheme.inverseOnSurface
    val highlight = MaterialTheme.colorScheme.primary

    val description = "Line graph, ${labels.first()} to ${labels.last()}. " +
        series.joinToString(". ") { line ->
            line.name + ": " + labels.indices.joinToString(", ") { i ->
                "${labels[i]} ${Money.format(line.values.getOrElse(i) { 0L })}"
            }
        } + ". Tap the graph to read a month."

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val index = indexAt(tap.x, size.width.toFloat(), points, 44.dp.toPx(), 12.dp.toPx())
                        touched = if (touched == index) null else index
                        onPointClick?.invoke(index)
                    }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragEnd = { },
                        onHorizontalDrag = { change, _ ->
                            touched = indexAt(change.position.x, size.width.toFloat(), points, 44.dp.toPx(), 12.dp.toPx())
                        },
                    )
                },
        ) {
            val left = 44.dp.toPx()
            val right = size.width - 12.dp.toPx()
            val top = 14.dp.toPx()
            val bottom = size.height - 24.dp.toPx()
            val plotWidth = right - left
            val stepX = plotWidth / (points - 1)
            fun xAt(i: Int) = left + i * stepX
            fun yAt(v: Long) = bottom - ((v - low).toFloat() / (high - low).coerceAtLeast(1L)) * (bottom - top)

            val labelPaint = Paint().apply {
                isAntiAlias = true
                textSize = 11.dp.toPx()
                color = muted.toArgb()
            }

            // Gridlines and the amounts up the side.
            ticks.forEach { tick ->
                val y = yAt(tick)
                drawLine(
                    color = if (tick == 0L && low < 0L) zero else grid,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = if (tick == 0L && low < 0L) 1.5.dp.toPx() else 1.dp.toPx(),
                )
                labelPaint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    Money.formatCompact(tick),
                    left - 6.dp.toPx(),
                    y + 4.dp.toPx(),
                    labelPaint,
                )
            }
            // Months along the bottom, every one while they fit.
            val every = ceil(points / 12f).toInt().coerceAtLeast(1)
            labelPaint.textAlign = Paint.Align.CENTER
            labels.forEachIndexed { i, label ->
                if (i % every != 0 && i != points - 1) return@forEachIndexed
                val isPicked = i == (touched ?: selectedIndex)
                labelPaint.color = (if (isPicked) highlight else muted).toArgb()
                labelPaint.isFakeBoldText = isPicked
                drawContext.canvas.nativeCanvas.drawText(label, xAt(i), size.height - 6.dp.toPx(), labelPaint)
            }
            labelPaint.isFakeBoldText = false

            // The month picked from outside, as a soft band.
            selectedIndex?.takeIf { it in 0 until points && touched == null }?.let { i ->
                drawRect(
                    color = highlight.copy(alpha = 0.08f),
                    topLeft = Offset(xAt(i) - stepX / 2f, top),
                    size = Size(stepX, bottom - top),
                )
            }

            // The lines, drawn in from the left.
            clipRect(left = 0f, top = 0f, right = left + plotWidth * progress.value + 8.dp.toPx(), bottom = size.height) {
                val baseY = yAt(0L.coerceIn(low, high))
                series.forEach { line ->
                    val path = Path()
                    val fill = Path()
                    line.values.take(points).forEachIndexed { i, v ->
                        val x = xAt(i)
                        val y = yAt(v)
                        if (i == 0) {
                            path.moveTo(x, y)
                            fill.moveTo(x, baseY)
                            fill.lineTo(x, y)
                        } else {
                            path.lineTo(x, y)
                            fill.lineTo(x, y)
                        }
                    }
                    fill.lineTo(xAt(line.values.take(points).size - 1), baseY)
                    fill.close()
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            colors = listOf(line.color.copy(alpha = 0.20f), line.color.copy(alpha = 0f)),
                            startY = top,
                            endY = bottom,
                        ),
                    )
                    drawPath(
                        path = path,
                        color = line.color,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            pathEffect = if (line.dashed) {
                                PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                            } else {
                                null
                            },
                        ),
                    )
                }
                if (points <= MAX_MARKED_POINTS) {
                    series.forEach { line ->
                        line.values.take(points).forEachIndexed { i, v ->
                            marker(line, Offset(xAt(i), yAt(v)), surface, big = i == touched)
                        }
                    }
                }
            }

            // The last figure on each line, written beside it.
            if (progress.value >= 1f && touched == null && series.size <= 4) {
                val ends = series.map { it to yAt(it.values.getOrElse(points - 1) { 0L }) }
                    .sortedBy { it.second }
                var lastY = -1000f
                labelPaint.textAlign = Paint.Align.RIGHT
                labelPaint.color = ink.toArgb()
                labelPaint.textSize = 11.dp.toPx()
                ends.forEach { (line, y) ->
                    val placed = maxOf(y - 9.dp.toPx(), lastY + 13.dp.toPx())
                    lastY = placed
                    drawContext.canvas.nativeCanvas.drawText(
                        Money.formatCompact(line.values.getOrElse(points - 1) { 0L }),
                        right - 8.dp.toPx(),
                        placed.coerceAtLeast(top),
                        labelPaint,
                    )
                }
            }

            // Reading one month: a line down, and every figure in a box.
            touched?.let { i ->
                val x = xAt(i)
                drawLine(
                    color = muted.copy(alpha = 0.6f),
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1.dp.toPx(),
                )
                tooltip(
                    x = x,
                    top = top,
                    left = left,
                    right = right,
                    title = labels[i],
                    rows = series.map { it to it.values.getOrElse(i) { 0L } },
                    back = tooltipBack,
                    ink = tooltipInk,
                    surface = surface,
                )
            }
        }
        if (showLegend) {
            Spacer(Modifier.height(6.dp))
            LineLegend(series, surface = surface)
        }
    }
}

/** Names each line with a sample of how it is drawn: solid or dashed, round or square dots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineLegend(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    surface: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        series.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(width = 28.dp, height = 14.dp)) {
                    val y = size.height / 2f
                    drawLine(
                        color = line.color,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.5.dp.toPx(),
                        pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
                    )
                    marker(line, Offset(size.width / 2f, y), surface, big = false)
                }
                Spacer(Modifier.width(6.dp))
                Text(line.name, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A dot with a ring of the card's colour round it, so overlapping lines stay readable. */
private fun DrawScope.marker(line: LineSeries, at: Offset, surface: Color, big: Boolean) {
    val r = (if (big) 5.5.dp else 4.dp).toPx()
    val ring = 2.dp.toPx()
    when (line.marker) {
        MarkerShape.CIRCLE -> {
            drawCircle(color = surface, radius = r + ring, center = at)
            drawCircle(color = line.color, radius = r, center = at)
        }
        MarkerShape.SQUARE -> {
            drawRect(color = surface, topLeft = Offset(at.x - r - ring, at.y - r - ring), size = Size((r + ring) * 2, (r + ring) * 2))
            drawRect(color = line.color, topLeft = Offset(at.x - r, at.y - r), size = Size(r * 2, r * 2))
        }
    }
}

/** The box of figures shown while a month is being read. */
private fun DrawScope.tooltip(
    x: Float,
    top: Float,
    left: Float,
    right: Float,
    title: String,
    rows: List<Pair<LineSeries, Long>>,
    back: Color,
    ink: Color,
    surface: Color,
) {
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = 12.dp.toPx()
        color = ink.toArgb()
    }
    val lines = rows.map { (line, value) -> line to "${line.name}  ${Money.format(value)}" }
    val pad = 8.dp.toPx()
    val swatch = 10.dp.toPx()
    val lineHeight = 17.dp.toPx()
    val width = (listOf(paint.measureText(title)) + lines.map { paint.measureText(it.second) + swatch + 6.dp.toPx() })
        .max() + pad * 2
    val height = lineHeight * (lines.size + 1) + pad
    val boxLeft = if (x + 10.dp.toPx() + width <= right) x + 10.dp.toPx() else x - 10.dp.toPx() - width
    val box = Rect(boxLeft.coerceAtLeast(left), top, boxLeft.coerceAtLeast(left) + width, top + height)
    drawContext.canvas.nativeCanvas.drawRoundRect(
        RectF(box.left, box.top, box.right, box.bottom),
        8.dp.toPx(),
        8.dp.toPx(),
        Paint().apply { isAntiAlias = true; color = back.toArgb() },
    )
    paint.isFakeBoldText = true
    drawContext.canvas.nativeCanvas.drawText(title, box.left + pad, box.top + lineHeight, paint)
    paint.isFakeBoldText = false
    lines.forEachIndexed { i, (line, text) ->
        val baseline = box.top + lineHeight * (i + 2)
        marker(line, Offset(box.left + pad + swatch / 2f, baseline - 4.dp.toPx()), back, big = false)
        drawContext.canvas.nativeCanvas.drawText(text, box.left + pad + swatch + 6.dp.toPx(), baseline, paint)
    }
}

/** Which point a tap at [x] is nearest, for a plot [left] in from one side and [rightPad] from the other. */
private fun indexAt(x: Float, width: Float, points: Int, left: Float, rightPad: Float): Int {
    val plot = (width - left - rightPad).coerceAtLeast(1f)
    return (((x - left) / plot) * (points - 1)).roundToInt().coerceIn(0, points - 1)
}

/** Above this many points the dots crowd the line, so only the line is drawn. */
private const val MAX_MARKED_POINTS = 24

/**
 * Round gridline values, in pence, covering [min] to [max]: steps of 1, 2,
 * 2.5 or 5 times a power of ten, about four of them.
 */
internal fun niceTicks(min: Long, max: Long, count: Int = 4): List<Long> {
    if (max <= min) return listOf(min, min + 100L)
    val rough = (max - min).toDouble() / count
    val magnitude = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rough }
    val lo = floor(min / step) * step
    val hi = ceil(max / step) * step
    val ticks = mutableListOf<Long>()
    var t = lo
    while (t <= hi + step / 2) {
        ticks += t.roundToLong()
        t += step
    }
    return ticks
}

private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()

/**
 * True when the phone's animations are switched off (Settings → Accessibility
 * → Remove animations), so charts and the intro appear at once.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
