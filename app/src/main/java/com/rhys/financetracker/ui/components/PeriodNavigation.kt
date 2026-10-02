package com.rhys.financetracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * Stepping through months (or years) on pages that look at one at a time.
 *
 * The arrows used to sit at the top of the page, so looking at a card further
 * down meant scrolling up to change month and back down again. Now the month
 * sits in a bar pinned to the bottom of the screen, under the thumb, with its
 * arrows on either side — and a swipe left or right anywhere on the page does
 * the same.
 */

/**
 * The bar pinned at the bottom: previous on the left, the period in the
 * middle, next on the right. Put it in a Scaffold's bottomBar.
 *
 * [position] orders the periods (year × 12 + month, say), so the name slides
 * in from the side it came from. [overSystemBar] lifts it clear of the
 * phone's own gesture bar, for pages without the app's tab bar under them.
 */
@Composable
fun PeriodBar(
    label: String,
    position: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "month",
    canGoNext: Boolean = true,
    subtitle: String? = null,
    onToday: (() -> Unit)? = null,
    overSystemBar: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (overSystemBar) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous $unit")
            }
            AnimatedContent(
                targetState = position to label,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState.first >= initialState.first
                    val sign = if (forward) 1 else -1
                    (slideInHorizontally { width -> sign * width / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -sign * width / 3 } + fadeOut()) using
                        SizeTransform(clip = false)
                },
                label = "period",
            ) { (_, shown) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (onToday != null) {
                TextButton(onClick = onToday) { Text("Today") }
            }
            FilledTonalIconButton(onClick = onNext, enabled = canGoNext) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next $unit")
            }
        }
    }
}

/**
 * Swipe right for the previous period and left for the next, anywhere on
 * what this is put on. Charts and rows that scroll sideways keep their own
 * drags: they take the gesture first, so a swipe across them is theirs.
 */
fun Modifier.swipeToStep(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoNext: Boolean = true,
): Modifier = composed {
    val threshold = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    val haptics = LocalHapticFeedback.current
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val nextAllowed by rememberUpdatedState(canGoNext)
    pointerInput(Unit) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragCancel = { dragged = 0f },
            onDragEnd = {
                when {
                    dragged > threshold -> {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        previous()
                    }
                    dragged < -threshold && nextAllowed -> {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        next()
                    }
                }
                dragged = 0f
            },
            onHorizontalDrag = { change, amount ->
                dragged += amount
                change.consume()
            },
        )
    }
}

/** How far a finger has to travel sideways to change the period. */
private val SWIPE_DISTANCE = 64.dp
