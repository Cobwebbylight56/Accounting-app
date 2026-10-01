package com.rhys.financetracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.rhys.financetracker.core.money.Money
import kotlin.math.roundToLong

/**
 * [minor] as money that counts up to its figure when it first shows, and
 * glides to the new one when it changes. Always exact once it settles; shown
 * straight away when the phone's animations are off.
 */
@Composable
fun animatedMoney(minor: Long, format: (Long) -> String = { Money.format(it) }): String {
    val reduceMotion = rememberReduceMotion()
    val shown = remember { Animatable(if (reduceMotion) minor.toFloat() else 0f) }
    LaunchedEffect(minor) {
        if (reduceMotion) {
            shown.snapTo(minor.toFloat())
        } else {
            shown.animateTo(minor.toFloat(), tween(durationMillis = 700, easing = FastOutSlowInEasing))
        }
    }
    // A float cannot hold every penny of a large sum, so the settled figure
    // is the real one rather than the animation's last frame.
    return format(if (shown.isRunning) shown.value.roundToLong() else minor)
}
