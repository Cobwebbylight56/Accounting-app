package com.rhys.financetracker.ui.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhys.financetracker.ui.components.ChartCurves
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The opening animation, about four and a half seconds long, in Vision 2's
 * colours: the bubble from the app's icon springs up, its bars rise one after
 * another, a smooth line climbs across their tops into a little glowing
 * bubble, and the name slides in under it while bubbles float up the screen.
 * Then it fades into the app.
 *
 * Tap anywhere to skip. It is not shown at all when the phone's animations
 * are switched off, or when "Opening animation" is off in Settings.
 *
 * Everything runs off one clock, [time] in milliseconds, so each part is a
 * plain function of it and the timings below read as a script.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val time = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Played out to the end, from wherever it is; skipping jumps it to the fade.
    suspend fun playFrom(start: Float) {
        if (time.value < start) time.snapTo(start)
        time.animateTo(TOTAL_MS, tween(durationMillis = (TOTAL_MS - time.value).toInt(), easing = LinearEasing))
        onFinished()
    }
    LaunchedEffect(Unit) { playFrom(0f) }
    val t = time.value
    /** Progress through one step, eased. The script's times are stretched by [PACE]. */
    fun phase(start: Float, end: Float): Float =
        FastOutSlowInEasing.transform(((t - start * PACE) / ((end - start) * PACE)).coerceIn(0f, 1f))
    /** The same on the real clock, for the hold and the fade. */
    fun clock(start: Float, end: Float): Float =
        FastOutSlowInEasing.transform(((t - start) / (end - start)).coerceIn(0f, 1f))
    /** A pop with a little overshoot, so a bubble lands rather than appears. */
    fun pop(start: Float, end: Float): Float {
        val p = phase(start, end)
        return p + 0.18f * sin(p * PI.toFloat())
    }

    val fadeOut = 1f - clock(FADE_START_MS, TOTAL_MS)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeOut }
            .background(Brush.linearGradient(listOf(Night, Indigo)))
            .semantics { contentDescription = "Finance Tracker. Tap to skip." }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // Straight to the fade, rather than cutting away. Taking over
                // the clock stops the first run, so only this one finishes.
                if (time.value < FADE_START_MS) scope.launch { playFrom(FADE_START_MS) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Two great soft glows, violet above and pink below, drifting slowly.
            val drift = sin(t / 900f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Violet.copy(alpha = 0.42f * phase(0f, 700f)), Color.Transparent),
                    center = Offset(size.width * (0.30f + 0.04f * drift), size.height * 0.30f),
                    radius = size.width * 0.85f,
                ),
                radius = size.width * 0.85f,
                center = Offset(size.width * (0.30f + 0.04f * drift), size.height * 0.30f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Pink.copy(alpha = 0.26f * phase(0f, 900f)), Color.Transparent),
                    center = Offset(size.width * (0.78f - 0.04f * drift), size.height * 0.80f),
                    radius = size.width * 0.75f,
                ),
                radius = size.width * 0.75f,
                center = Offset(size.width * (0.78f - 0.04f * drift), size.height * 0.80f),
            )
            // Bubbles floating up the whole screen.
            FLOATERS.forEach { b ->
                val life = ((t + b.delay) % FLOAT_LIFE_MS) / FLOAT_LIFE_MS
                val r = b.radius.dp.toPx()
                val x = size.width * b.x + sin(life * 2 * PI.toFloat() + b.x * 9f) * 16.dp.toPx()
                val y = size.height * (1.08f - life * 1.16f)
                val alpha = sin(life * PI.toFloat()) * 0.55f * phase(0f, 600f)
                val colours = BUBBLES[b.colour]
                drawCircle(
                    brush = Brush.linearGradient(
                        colours.map { it.copy(alpha = alpha) },
                        start = Offset(x - r, y - r),
                        end = Offset(x + r, y + r),
                    ),
                    radius = r,
                    center = Offset(x, y),
                )
                drawCircle(Color.White.copy(alpha = alpha * 0.7f), radius = r * 0.28f, center = Offset(x - r * 0.35f, y - r * 0.38f))
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val arrive = pop(0f, 650f)
            Canvas(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        val scale = 0.55f + 0.45f * arrive
                        scaleX = scale
                        scaleY = scale
                        alpha = phase(0f, 400f)
                    },
            ) {
                val w = size.width
                fun at(x: Float, y: Float) = Offset(w * x, w * y)
                val corner = CornerRadius(w * 0.34f, w * 0.34f)
                val badge = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), corner)) }

                // Its glow, breathing gently.
                val breathe = 0.85f + 0.15f * sin(t / 420f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Glow.copy(alpha = 0.55f * breathe), Color.Transparent),
                        center = center,
                        radius = w * 0.85f,
                    ),
                    radius = w * 0.85f,
                    center = center,
                )
                drawPath(badge, Brush.linearGradient(listOf(Blue, Violet, Pink), start = Offset.Zero, end = Offset(w, w)))
                clipPath(badge) {
                    // As on Home's bubbles: two circles drifting off the edges, and light across the top.
                    drawCircle(Color.White.copy(alpha = 0.13f), radius = w * 0.54f, center = at(0.98f, 0.02f))
                    drawCircle(Color.White.copy(alpha = 0.08f), radius = w * 0.30f, center = at(0.08f, 1.02f))
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), endY = w * 0.56f))
                    // Once it is all built, a band of light sweeps across.
                    val shine = clock(SHINE_START_MS, SHINE_START_MS + 900f)
                    if (shine > 0f && shine < 1f) {
                        val band = w * 0.45f
                        val x = -band + (w + band * 2) * shine
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
                                start = Offset(x - band / 2f, 0f),
                                end = Offset(x + band / 2f, w),
                            ),
                        )
                    }
                }

                // The bars, rising one after another.
                val floor = w * FLOOR
                BAR_X.forEachIndexed { i, x ->
                    val grow = phase(250f + i * 110f, 650f + i * 110f)
                    val top = floor - (floor - w * BAR_TOP[i]) * grow
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.28f),
                        topLeft = Offset(w * (x - BAR_W / 2f), top),
                        size = Size(w * BAR_W, floor - top),
                        cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
                    )
                }

                // The line climbing over them, drawn as it goes, with a wash under it.
                val points = LINE.map { (x, y) -> at(x, y) }
                val line = ChartCurves.path(points)
                val draw = phase(950f, 1450f)
                val wash = phase(1250f, 1700f)
                if (wash > 0f) {
                    clipPath(badge) {
                        drawPath(
                            ChartCurves.area(points, floor),
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.35f * wash), Color.Transparent),
                                startY = w * 0.3f,
                                endY = floor,
                            ),
                        )
                    }
                }
                val measure = PathMeasure().apply { setPath(line, false) }
                if (draw > 0f) {
                    val partial = Path()
                    measure.getSegment(0f, measure.length * draw, partial, true)
                    drawPath(
                        path = partial,
                        color = Color.White,
                        style = Stroke(width = w * 0.052f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }

                // The little bubble at the top of the line, landing as the line reaches it.
                val sun = pop(1380f, 1750f)
                if (sun > 0f) {
                    val c = points.last()
                    val r = w * SUN_R * sun
                    val pulse = if (phase(1380f, 1750f) >= 1f) (sin(t / 240f) + 1f) / 2f else 0f
                    drawCircle(Color.White.copy(alpha = 0.20f + 0.10f * pulse), radius = r + w * (0.06f + 0.02f * pulse), center = c)
                    drawCircle(Brush.linearGradient(listOf(SunStart, SunEnd), start = c - Offset(r, r), end = c + Offset(r, r)), radius = r, center = c)
                    drawCircle(Color.White.copy(alpha = 0.5f), radius = r * 0.27f, center = c - Offset(r * 0.31f, r * 0.34f))
                }
                // And one below, a moment later.
                val mint = pop(1550f, 1900f)
                if (mint > 0f) {
                    val c = at(MINT_X, MINT_Y)
                    val r = w * MINT_R * mint
                    drawCircle(Brush.linearGradient(listOf(MintStart, MintEnd), start = c - Offset(r, r), end = c + Offset(r, r)), radius = r, center = c)
                    drawCircle(Color.White.copy(alpha = 0.5f), radius = r * 0.3f, center = c - Offset(r * 0.35f, r * 0.37f))
                }
            }

            Spacer(Modifier.height(30.dp))
            val title = phase(1050f, 1550f)
            Text(
                text = "Finance Tracker",
                color = Color.White,
                fontSize = 31.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.3.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = title
                    translationY = (1f - title) * 24.dp.toPx()
                },
            )
            Spacer(Modifier.height(8.dp))
            val tagline = phase(1350f, 1850f)
            Text(
                text = "Every penny, in its place",
                color = Lilac.copy(alpha = 0.9f),
                fontSize = 15.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = tagline
                    translationY = (1f - tagline) * 16.dp.toPx()
                },
            )
        }
    }
}

private class Floater(val x: Float, val delay: Float, val radius: Float, val colour: Int)

/** Fixed, so the intro looks the same every time. */
private val FLOATERS = listOf(
    Floater(0.08f, 0f, 9f, 0), Floater(0.20f, 1100f, 5f, 2), Floater(0.31f, 2100f, 13f, 1),
    Floater(0.45f, 500f, 6f, 3), Floater(0.58f, 1600f, 10f, 0), Floater(0.67f, 300f, 4f, 2),
    Floater(0.76f, 2600f, 8f, 1), Floater(0.88f, 800f, 12f, 3), Floater(0.94f, 1900f, 5f, 0),
    Floater(0.25f, 3200f, 7f, 3), Floater(0.52f, 3700f, 4f, 1), Floater(0.82f, 4300f, 9f, 2),
)

/**
 * The mark, the same as the app's icon, as shares of the bubble's width:
 * where the bars stand and how tall they grow, the points the line runs
 * through, and the two little bubbles.
 */
private const val FLOOR = 0.84f
private const val BAR_W = 0.112f
private val BAR_X = listOf(0.24f, 0.404f, 0.568f, 0.732f)
private val BAR_TOP = listOf(0.65f, 0.53f, 0.59f, 0.39f)
private val LINE = listOf(
    0.11f to 0.67f, 0.24f to 0.56f, 0.404f to 0.44f, 0.568f to 0.50f, 0.732f to 0.30f, 0.852f to 0.148f,
)
private const val SUN_R = 0.128f
private const val MINT_X = 0.088f
private const val MINT_Y = 0.88f
private const val MINT_R = 0.08f

/**
 * Four and a half seconds in all: the build-up, slowed by [PACE] so each
 * step can be seen, then a moment holding the finished picture — the light
 * sweeps across the bubble and the little one glows — before it fades.
 */
private const val PACE = 1.4f
private const val SHINE_START_MS = 2750f
private const val TOTAL_MS = 4400f
private const val FADE_START_MS = 3900f
private const val FLOAT_LIFE_MS = 5200f

/** The icon's colours: its night sky, its bubble, and the two little ones. */
private val Night = Color(0xFF1A1240)
private val Indigo = Color(0xFF0B1E3C)
private val Blue = Color(0xFF4F6BF5)
private val Violet = Color(0xFF6A4CF5)
private val Pink = Color(0xFFC2366F)
private val Glow = Color(0xFF7B4CF5)
private val SunStart = Color(0xFFFFA552)
private val SunEnd = Color(0xFFE0446A)
private val MintStart = Color(0xFF3DDBB4)
private val MintEnd = Color(0xFF0E9F7E)
private val Lilac = Color(0xFFD2C9FF)

/** The floating bubbles' colours, as on Home. */
private val BUBBLES = listOf(
    listOf(Blue, Violet),
    listOf(Violet, Pink),
    listOf(SunStart, SunEnd),
    listOf(MintStart, MintEnd),
)
