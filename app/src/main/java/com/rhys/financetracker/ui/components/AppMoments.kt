package com.rhys.financetracker.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * True while the opening animation is still playing over the app. Anything
 * that pops up when the app opens — Needs a look, a re-sort note — waits for
 * it, or it would appear behind the animation and be missed.
 */
val LocalIntroShowing = compositionLocalOf { false }
