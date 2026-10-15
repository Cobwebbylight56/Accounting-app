package com.rhys.financetracker.ui

import com.rhys.financetracker.ui.components.ChartCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smooth chart lines never draw a month higher or lower than it was. */
class ChartCurvesTest {

    /** A point on the cubic from (x0,y0) to (x1,y1) with controls [c], at t. */
    private fun at(t: Float, y0: Float, c: FloatArray, y1: Float): Float {
        val u = 1 - t
        return u * u * u * y0 + 3 * u * u * t * c[1] + 3 * u * t * t * c[3] + t * t * t * y1
    }

    @Test
    fun `between two months the line never passes either`() {
        val xs = listOf(0f, 10f, 20f, 30f, 40f, 50f)
        val ys = listOf(100f, 20f, 25f, 90f, 95f, 10f)
        val controls = ChartCurves.controls(xs, ys)
        assertEquals(5, controls.size)
        controls.forEachIndexed { i, c ->
            val lo = minOf(ys[i], ys[i + 1])
            val hi = maxOf(ys[i], ys[i + 1])
            (0..20).map { it / 20f }.forEach { t ->
                val y = at(t, ys[i], c, ys[i + 1])
                assertTrue("segment $i at $t: $y outside $lo..$hi", y >= lo - 0.01f && y <= hi + 0.01f)
            }
        }
    }

    @Test
    fun `a flat run stays flat`() {
        val controls = ChartCurves.controls(listOf(0f, 1f, 2f), listOf(5f, 5f, 5f))
        controls.forEach { c ->
            assertEquals(5f, c[1], 0f)
            assertEquals(5f, c[3], 0f)
        }
    }

    @Test
    fun `fewer than two points has no curve`() {
        assertTrue(ChartCurves.controls(listOf(1f), listOf(1f)).isEmpty())
    }
}
