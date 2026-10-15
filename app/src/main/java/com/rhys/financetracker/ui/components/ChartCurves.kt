package com.rhys.financetracker.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.sqrt

/**
 * Smooth lines for the charts that never lie about the figures.
 *
 * An ordinary curve through a set of points swings above a peak and below a
 * trough between them, drawing a month higher or lower than it ever was.
 * This one bends only as much as the points allow (monotone cubic,
 * Fritsch–Carlson): between two months it never goes past either of them.
 */
object ChartCurves {

    /**
     * The two control points of the curve between each pair of neighbouring
     * points, as `[c1x, c1y, c2x, c2y]`. [xs] must rise from left to right.
     */
    fun controls(xs: List<Float>, ys: List<Float>): List<FloatArray> {
        val n = xs.size
        if (n < 2) return emptyList()
        val dx = FloatArray(n - 1) { xs[it + 1] - xs[it] }
        val slope = FloatArray(n - 1) { if (dx[it] == 0f) 0f else (ys[it + 1] - ys[it]) / dx[it] }
        val tangent = FloatArray(n)
        tangent[0] = slope[0]
        tangent[n - 1] = slope[n - 2]
        for (i in 1 until n - 1) {
            // A peak or a trough is flat on top: the curve can't rise past it.
            tangent[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (slope[i] == 0f) {
                tangent[i] = 0f
                tangent[i + 1] = 0f
                continue
            }
            val a = tangent[i] / slope[i]
            val b = tangent[i + 1] / slope[i]
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / sqrt(s)
                tangent[i] = tau * a * slope[i]
                tangent[i + 1] = tau * b * slope[i]
            }
        }
        return (0 until n - 1).map { i ->
            val third = dx[i] / 3f
            floatArrayOf(
                xs[i] + third,
                ys[i] + tangent[i] * third,
                xs[i + 1] - third,
                ys[i + 1] - tangent[i + 1] * third,
            )
        }
    }

    /** The smooth line through [points], left to right. */
    fun path(points: List<Offset>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        controls(points.map { it.x }, points.map { it.y }).forEachIndexed { i, c ->
            path.cubicTo(c[0], c[1], c[2], c[3], points[i + 1].x, points[i + 1].y)
        }
        return path
    }

    /** [path], closed down to [baseY] so it can be filled. */
    fun area(points: List<Offset>, baseY: Float): Path {
        val path = path(points)
        if (points.isEmpty()) return path
        path.lineTo(points.last().x, baseY)
        path.lineTo(points.first().x, baseY)
        path.close()
        return path
    }
}
