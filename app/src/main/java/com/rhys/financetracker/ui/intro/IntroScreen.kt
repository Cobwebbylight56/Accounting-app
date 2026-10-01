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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The opening animation, about two seconds long: a badge springs up, its
 * bars rise one after another, a line climbs across their tops to a glowing
 * point, and the name slides in under it while sparks drift upward. Then it
 * fades into the app.
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
    fun phase(start: Float, end: Float): Float =
        FastOutSlowInEasing.transform(((t - start) / (end - start)).coerceIn(0f, 1f))

    val fadeOut = 1f - phase(FADE_START_MS, TOTAL_MS)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeOut }
            .background(Brush.verticalGradient(listOf(Navy, DeepTeal)))
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
        // Sparks drifting up the whole screen.
        Canvas(modifier = Modifier.fillMaxSize()) {
            SPARKS.forEach { spark ->
                val life = ((t + spark.delay) % SPARK_LIFE_MS) / SPARK_LIFE_MS
                val x = size.width * spark.x + sin(life * 2 * PI.toFloat() + spark.x * 9f) * 14.dp.toPx()
                val y = size.height * (1.05f - life * 0.9f)
                val alpha = sin(life * PI.toFloat()) * 0.55f * phase(0f, 500f)
                drawCircle(
                    color = Mint.copy(alpha = alpha),
                    radius = spark.radius.dp.toPx(),
                    center = Offset(x, y),
                )
            }
            // A soft glow behind the badge, breathing gently.
            val glow = 0.20f + 0.08f * sin(t / 380f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Mint.copy(alpha = glow * phase(0f, 600f)), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f - 40.dp.toPx()),
                    radius = 190.dp.toPx(),
                ),
                radius = 190.dp.toPx(),
                center = Offset(size.width / 2f, size.height / 2f - 40.dp.toPx()),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val pop = phase(0f, 650f)
            Canvas(
                modifier = Modifier
                    .size(132.dp)
                    .graphicsLayer {
                        // A little overshoot, so it lands rather than appears.
                        val scale = 0.55f + 0.45f * pop + 0.06f * sin(pop * PI.toFloat())
                        scaleX = scale
                        scaleY = scale
                        alpha = pop
                    },
            ) {
                val corner = 34.dp.toPx()
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(Teal, Sea), start = Offset.Zero, end = Offset(size.width, size.height)),
                    cornerRadius = CornerRadius(corner, corner),
                )
                // A sheen across the top half.
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), endY = size.height * 0.6f),
                    cornerRadius = CornerRadius(corner, corner),
                )

                val inset = size.width * 0.2f
                val floor = size.height * 0.78f
                val barWidth = (size.width - inset * 2) / (BARS.size * 1.6f)
                val gap = barWidth * 0.6f
                val tops = BARS.mapIndexed { i, height ->
                    val grow = phase(250f + i * 110f, 650f + i * 110f)
                    val left = inset + i * (barWidth + gap)
                    val top = floor - (floor - size.height * 0.24f) * height * grow
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.9f),
                        topLeft = Offset(left, top),
                        size = Size(barWidth, floor - top),
                        cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f),
                    )
                    Offset(left + barWidth / 2f, floor - (floor - size.height * 0.24f) * height - 9.dp.toPx())
                }

                // The line climbing across the bar tops, drawn as it goes.
                val draw = phase(950f, 1450f)
                if (draw > 0f) {
                    val path = Path().apply {
                        moveTo(tops.first().x - barWidth, tops.first().y + 8.dp.toPx())
                        tops.forEach { lineTo(it.x, it.y) }
                    }
                    val partial = Path()
                    val measure = PathMeasure().apply { setPath(path, false) }
                    measure.getSegment(0f, measure.length * draw, partial, true)
                    drawPath(
                        path = partial,
                        color = Gold,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    // The glowing point at the end.
                    val end = measure.getPosition(measure.length * draw)
                    val burst = phase(1400f, 1700f)
                    drawCircle(Gold.copy(alpha = 0.35f * (1f - burst * 0.6f)), radius = (6f + 10f * burst).dp.toPx(), center = end)
                    drawCircle(Gold, radius = 4.5.dp.toPx(), center = end)
                }
            }

            Spacer(Modifier.height(28.dp))
            val title = phase(1050f, 1550f)
            Text(
                text = "Finance Tracker",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = title
                    translationY = (1f - title) * 24.dp.toPx()
                },
            )
            Spacer(Modifier.height(8.dp))
            val tagline = phase(1350f, 1850f)
            Text(
                text = "Every penny, in its place",
                color = Mint.copy(alpha = 0.85f),
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

private class Spark(val x: Float, val delay: Float, val radius: Float)

/** Fixed, so the intro looks the same every time. */
private val SPARKS = listOf(
    Spark(0.08f, 0f, 2.5f), Spark(0.19f, 900f, 1.8f), Spark(0.31f, 1700f, 3f),
    Spark(0.44f, 400f, 2f), Spark(0.57f, 1300f, 2.6f), Spark(0.66f, 200f, 1.6f),
    Spark(0.74f, 2100f, 2.2f), Spark(0.86f, 600f, 3f), Spark(0.93f, 1500f, 1.8f),
    Spark(0.25f, 2500f, 2.2f), Spark(0.51f, 2900f, 1.6f), Spark(0.80f, 3300f, 2.4f),
)

/** Bar heights inside the badge, as a share of the room: a rising month. */
private val BARS = listOf(0.38f, 0.58f, 0.47f, 0.86f)

private const val TOTAL_MS = 2400f
private const val FADE_START_MS = 2000f
private const val SPARK_LIFE_MS = 3600f

private val Navy = Color(0xFF0E1A2B)
private val DeepTeal = Color(0xFF0F3B3A)
private val Teal = Color(0xFF1B9A94)
private val Sea = Color(0xFF1F5F8B)
private val Mint = Color(0xFF9FF2D9)
private val Gold = Color(0xFFFFD27A)
