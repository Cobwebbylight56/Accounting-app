package com.rhys.financetracker.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.ui.theme.FinanceTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The app's charts, drawn directly on a Compose [Canvas].
 *
 * Writing them by hand rather than pulling in a charting library keeps the APK
 * small, removes a dependency that would need tracking, and — most importantly
 * — lets every chart carry a proper `contentDescription`, which almost no
 * charting library does.  A screen reader reads out the actual figures.
 */

/** One slice or bar. */
data class ChartEntry(
    val label: String,
    val value: Float,
    val color: Color,
    /** Pre-formatted for the legend and the accessibility description. */
    val displayValue: String,
)

/**
 * A donut chart.  Values must be non-negative; anything at or below zero is
 * dropped, since a slice of nothing has no meaning.
 *
 * @param onSliceClick called with the index of the slice that was tapped, or
 *   `null` when the tap landed on the hole in the middle or outside the ring.
 *   Supplying it makes the chart interactive: slices lift slightly and the
 *   selected one is drawn thicker.
 * @param selectedIndex the slice to emphasise, usually the last one tapped.
 */
@Composable
fun DonutChart(
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    centreLabel: String? = null,
    centreValue: String? = null,
    strokeWidth: Float = 46f,
    selectedIndex: Int? = null,
    onSliceClick: ((Int?) -> Unit)? = null,
) {
    val visible = entries.filter { it.value > 0f }
    val total = visible.sumOf { it.value.toDouble() }.toFloat()

    if (visible.isEmpty() || total <= 0f) {
        EmptyChartPlaceholder(modifier, "Nothing to show for this period")
        return
    }

    // The sweep of every slice, so drawing and hit-testing cannot disagree.
    val sweeps = remember(visible, total) {
        var running = -90f
        visible.map { entry ->
            val sweep = (entry.value / total) * 360f
            val arc = running to sweep
            running += sweep
            arc
        }
    }

    val description = "Donut chart. " + visible.joinToString(", ") {
        "${it.label} ${it.displayValue}, ${percent(it.value, total)}"
    } + if (onSliceClick != null) ". Tap a slice for a breakdown." else ""

    Box(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .then(
                    if (onSliceClick == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(sweeps) {
                            detectTapGestures { tap ->
                                onSliceClick(
                                    sliceAt(
                                        tap = tap,
                                        canvas = size.toSize(),
                                        strokeWidth = strokeWidth,
                                        sweeps = sweeps,
                                    ),
                                )
                            }
                        }
                    },
                ),
        ) {
            val diameter = minOf(size.width, size.height) - strokeWidth
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f,
            )
            sweeps.forEachIndexed { index, (startAngle, sweep) ->
                val isSelected = index == selectedIndex
                val width = if (isSelected) strokeWidth * 1.28f else strokeWidth
                drawArc(
                    color = visible[index].color,
                    startAngle = startAngle,
                    // A hairline gap makes adjacent slices distinguishable
                    // without relying on colour alone.
                    sweepAngle = (sweep - 1f).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = Offset(
                        topLeft.x - (width - strokeWidth) / 2f,
                        topLeft.y - (width - strokeWidth) / 2f,
                    ),
                    size = Size(diameter + (width - strokeWidth), diameter + (width - strokeWidth)),
                    style = Stroke(width = width),
                    alpha = if (selectedIndex == null || isSelected) 1f else 0.45f,
                )
            }
        }

        if (centreValue != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centreValue,
                    style = MaterialTheme.typography.titleLarge,
                )
                centreLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Which slice, if any, a tap landed on.
 *
 * The ring is hollow, so a tap must be within the band's radius as well as at
 * the right angle — otherwise tapping the total in the middle would select
 * whichever slice happened to be behind it.
 */
internal fun sliceAt(
    tap: Offset,
    canvas: Size,
    strokeWidth: Float,
    sweeps: List<Pair<Float, Float>>,
): Int? {
    val diameter = minOf(canvas.width, canvas.height) - strokeWidth
    val radius = diameter / 2f
    val centre = Offset(canvas.width / 2f, canvas.height / 2f)

    val distance = hypot(tap.x - centre.x, tap.y - centre.y)
    // Generous band: half a stroke either side, plus a little for fat fingers.
    val tolerance = strokeWidth * 0.9f
    if (distance < radius - tolerance || distance > radius + tolerance) return null

    // atan2 gives -180..180 with 0 pointing right; the chart starts at -90
    // (twelve o'clock), so normalise into the same 0..360 space.
    var angle = Math.toDegrees(
        atan2((tap.y - centre.y).toDouble(), (tap.x - centre.x).toDouble()),
    ).toFloat()
    if (angle < -90f) angle += 360f

    sweeps.forEachIndexed { index, (start, sweep) ->
        if (angle >= start && angle < start + sweep) return index
    }
    return null
}

/**
 * The legend that accompanies a donut or bar chart.
 *
 * @param onEntryClick makes each row tappable, so the legend is a second, much
 *   larger target for the same action as tapping a slice — which matters when a
 *   slice is only a couple of degrees wide.
 */
@Composable
fun ChartLegend(
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    maxItems: Int = 8,
    selectedIndex: Int? = null,
    onEntryClick: ((Int) -> Unit)? = null,
) {
    val visible = entries.filter { it.value > 0f }
    val shown = visible.take(maxItems)
    val remainder = visible.drop(maxItems)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEachIndexed { index, entry ->
            val isSelected = index == selectedIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onEntryClick == null) {
                            Modifier
                        } else {
                            Modifier.clickable { onEntryClick(index) }
                        },
                    )
                    .padding(vertical = 6.dp),
            ) {
                Surface(
                    modifier = Modifier.size(if (isSelected) 16.dp else 12.dp),
                    shape = CircleShape,
                    color = entry.color,
                ) {}
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.displayValue,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (remainder.isNotEmpty()) {
            Text(
                text = "and ${remainder.size} more, totalling " +
                    Money.format(remainder.sumOf { it.value.toDouble() }.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * Grouped bars — used for income against expenses, month by month.
 *
 * @param groups one entry per column; each holds the bars drawn side by side.
 * @param onGroupClick called with the index of the tapped column, which is how
 *   the dashboard lets you tap a month to go to it.
 * @param selectedIndex the column to emphasise.
 */
@Composable
fun GroupedBarChart(
    groups: List<BarGroup>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 180.dp,
    selectedIndex: Int? = null,
    onGroupClick: ((Int) -> Unit)? = null,
) {
    if (groups.isEmpty() || groups.all { group -> group.bars.all { it.value == 0f } }) {
        EmptyChartPlaceholder(modifier, "No figures for this period yet")
        return
    }

    val maxValue = max(
        groups.maxOf { group -> group.bars.maxOfOrNull { abs(it.value) } ?: 0f },
        1f,
    )
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlightColor = MaterialTheme.colorScheme.primary

    val description = "Bar chart. " + groups.joinToString("; ") { group ->
        group.label + ": " + group.bars.joinToString(", ") { "${it.label} ${it.displayValue}" }
    } + if (onGroupClick != null) ". Tap a column to open that month." else ""

    Column(modifier = modifier.semantics { contentDescription = description }) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .then(
                    if (onGroupClick == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(groups.size) {
                            detectTapGestures { tap ->
                                // The whole column is the target, not just the
                                // bar, so a quiet month is as easy to hit as a
                                // busy one.
                                val column = (tap.x / (size.width / groups.size))
                                    .toInt()
                                    .coerceIn(0, groups.size - 1)
                                onGroupClick(column)
                            }
                        }
                    },
                ),
        ) {
            val bottom = size.height - 22f
            val groupWidth = size.width / groups.size
            val barCount = groups.first().bars.size.coerceAtLeast(1)
            val barWidth = (groupWidth * 0.62f) / barCount

            // Baseline.
            drawLine(
                color = axisColor,
                start = Offset(0f, bottom),
                end = Offset(size.width, bottom),
                strokeWidth = 1.5f,
            )

            groups.forEachIndexed { groupIndex, group ->
                val isSelected = groupIndex == selectedIndex
                if (isSelected) {
                    drawRect(
                        color = highlightColor.copy(alpha = 0.10f),
                        topLeft = Offset(groupIndex * groupWidth, 0f),
                        size = Size(groupWidth, bottom),
                    )
                }
                val groupStart = groupIndex * groupWidth + (groupWidth - barWidth * barCount) / 2f
                group.bars.forEachIndexed { barIndex, bar ->
                    val barHeight = (abs(bar.value) / maxValue) * (bottom - 8f)
                    drawRect(
                        color = bar.color,
                        topLeft = Offset(groupStart + barIndex * barWidth, bottom - barHeight),
                        size = Size(barWidth * 0.86f, barHeight),
                        alpha = if (selectedIndex == null || isSelected) 1f else 0.5f,
                    )
                }

                // Month label under each group, drawn with the platform canvas
                // because Compose's Canvas has no text primitive.
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (isSelected) highlightColor.toArgb() else labelColor.toArgb()
                        textSize = 26f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    drawText(
                        group.label,
                        groupIndex * groupWidth + groupWidth / 2f,
                        size.height - 4f,
                        paint,
                    )
                }
            }
        }
    }
}

data class BarGroup(val label: String, val bars: List<ChartEntry>)

/**
 * A line chart for a running balance.  The area below the line is filled so the
 * shape reads at a glance, and the zero line is drawn when the series crosses
 * it — going into the red should be visible instantly.
 *
 * @param onPointSelected called with the index of the nearest point when the
 *   chart is tapped, so the caller can show that day's figure.
 * @param selectedIndex the point to mark with a dot.
 */
@Composable
fun LineChart(
    points: List<Pair<String, Long>>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    height: androidx.compose.ui.unit.Dp = 180.dp,
    selectedIndex: Int? = null,
    onPointSelected: ((Int) -> Unit)? = null,
) {
    if (points.size < 2) {
        EmptyChartPlaceholder(modifier, "Not enough data to draw a line yet")
        return
    }

    val values = points.map { it.second }
    val maxValue = values.max()
    val minValue = values.min()
    val range = (maxValue - minValue).coerceAtLeast(1L)
    val zeroColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = lineColor.copy(alpha = 0.14f)
    val markerColor = MaterialTheme.colorScheme.primary

    val description = "Line chart from ${points.first().first} to ${points.last().first}. " +
        "Lowest ${Money.format(minValue)}, highest ${Money.format(maxValue)}, " +
        "ending at ${Money.format(values.last())}." +
        if (onPointSelected != null) " Tap the line to read a single day." else ""

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .then(
                if (onPointSelected == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(points.size) {
                        detectTapGestures { tap ->
                            val step = size.width / (points.size - 1)
                            onPointSelected(
                                (tap.x / step).roundToInt().coerceIn(0, points.size - 1),
                            )
                        }
                    }
                },
            ),
    ) {
        fun yFor(value: Long): Float =
            size.height - ((value - minValue).toFloat() / range) * (size.height - 12f) - 6f

        val stepX = size.width / (points.size - 1)

        val linePath = Path()
        val fillPath = Path()
        points.forEachIndexed { index, (_, value) ->
            val x = index * stepX
            val y = yFor(value)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        // Zero line, only when the series actually crosses it.
        if (minValue < 0L && maxValue > 0L) {
            val zeroY = yFor(0L)
            drawLine(
                color = zeroColor,
                start = Offset(0f, zeroY),
                end = Offset(size.width, zeroY),
                strokeWidth = 1.5f,
            )
        }

        drawPath(fillPath, color = fillColor)
        drawPath(linePath, color = lineColor, style = Stroke(width = 4f))

        selectedIndex?.takeIf { it in points.indices }?.let { index ->
            val x = index * stepX
            val y = yFor(points[index].second)
            drawLine(
                color = markerColor.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
            drawCircle(color = markerColor, radius = 9f, center = Offset(x, y))
        }
    }
}

/** A slim horizontal bar used inline in lists, e.g. category shares. */
@Composable
fun ProgressBarRow(
    label: String,
    value: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    secondary: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            drawRoundRect(
                color = color.copy(alpha = 0.18f),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
            drawRoundRect(
                color = color,
                size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
        }
        secondary?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyChartPlaceholder(modifier: Modifier, message: String) {
    Box(
        modifier = modifier.fillMaxWidth().height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun percent(value: Float, total: Float): String =
    if (total <= 0f) "0%" else "${((value / total) * 100).toInt()}%"

/** Assigns a chart colour by position, cycling if there are more items than colours. */
@Composable
fun chartColorAt(index: Int): Color {
    val series = FinanceTheme.colors.chartSeries
    return series[index % series.size]
}

/** Parses a stored hex colour, falling back to the palette when it is missing or bad. */
@Composable
fun colorFromHex(hex: String?, fallbackIndex: Int = 0): Color {
    val fallback = chartColorAt(fallbackIndex)
    if (hex.isNullOrBlank()) return fallback
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}
