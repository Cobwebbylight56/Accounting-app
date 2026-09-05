package com.rhys.financetracker.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.ui.theme.FinanceTheme
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.first

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
 */
@Composable
fun DonutChart(
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    centreLabel: String? = null,
    centreValue: String? = null,
    strokeWidth: Float = 46f,
) {
    val visible = entries.filter { it.value > 0f }
    val total = visible.sumOf { it.value.toDouble() }.toFloat()

    if (visible.isEmpty() || total <= 0f) {
        EmptyChartPlaceholder(modifier, "Nothing to show for this period")
        return
    }

    val description = "Donut chart. " + visible.joinToString(", ") {
        "${it.label} ${it.displayValue}, ${percent(it.value, total)}"
    }

    Box(
        modifier = modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val diameter = minOf(size.width, size.height) - strokeWidth
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f,
            )
            var startAngle = -90f
            visible.forEach { entry ->
                val sweep = (entry.value / total) * 360f
                drawArc(
                    color = entry.color,
                    startAngle = startAngle,
                    // A hairline gap makes adjacent slices distinguishable
                    // without relying on colour alone.
                    sweepAngle = (sweep - 1f).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeWidth),
                )
                startAngle += sweep
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

/** The legend that accompanies a donut or bar chart. */
@Composable
fun ChartLegend(
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    maxItems: Int = 8,
) {
    val visible = entries.filter { it.value > 0f }
    val shown = visible.take(maxItems)
    val remainder = visible.drop(maxItems)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = CircleShape,
                    color = entry.color,
                ) {}
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyMedium,
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
            )
        }
    }
}

/**
 * Grouped bars — used for income against expenses, month by month.
 *
 * @param groups one entry per column; each holds the bars drawn side by side.
 */
@Composable
fun GroupedBarChart(
    groups: List<BarGroup>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 180.dp,
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

    val description = "Bar chart. " + groups.joinToString("; ") { group ->
        group.label + ": " + group.bars.joinToString(", ") { "${it.label} ${it.displayValue}" }
    }

    Column(modifier = modifier.semantics { contentDescription = description }) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
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
                val groupStart = groupIndex * groupWidth + (groupWidth - barWidth * barCount) / 2f
                group.bars.forEachIndexed { barIndex, bar ->
                    val barHeight = (abs(bar.value) / maxValue) * (bottom - 8f)
                    drawRect(
                        color = bar.color,
                        topLeft = Offset(groupStart + barIndex * barWidth, bottom - barHeight),
                        size = Size(barWidth * 0.86f, barHeight),
                    )
                }

                // Month label under each group, drawn with the platform canvas
                // because Compose's Canvas has no text primitive.
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = labelColor.toArgb()
                        textSize = 26f
                        textAlign = android.graphics.Paint.Align.CENTER
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
 */
@Composable
fun LineChart(
    points: List<Pair<String, Long>>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    height: androidx.compose.ui.unit.Dp = 180.dp,
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

    val description = "Line chart from ${points.first().first} to ${points.last().first}. " +
        "Lowest ${Money.format(minValue)}, highest ${Money.format(maxValue)}, " +
        "ending at ${Money.format(values.last())}."

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
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
