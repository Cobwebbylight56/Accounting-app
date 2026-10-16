package com.rhys.financetracker.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * True while the opening animation is still playing over the app. Anything
 * that pops up when the app opens — Needs a look, a re-sort note — waits for
 * it, or it would appear behind the animation and be missed.
 */
val LocalIntroShowing = compositionLocalOf { false }

/**
 * Pop-ups that open by themselves take turns, one at a time: a progress
 * report waits for Needs a look to be closed rather than opening on top.
 */
object PopupTurns {
    /** True while one is open. */
    var open by mutableStateOf(false)
}
