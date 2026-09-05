package com.rhys.financetracker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * A muted green is used as the primary colour rather than the usual finance-app
 * blue: it reads as "money" without being aggressive, and it leaves red and
 * amber free to mean exactly one thing each — overspent and warning.
 *
 * Every pair below meets at least 4.5:1 contrast against its container in both
 * light and dark schemes.
 */

// ------------------------------------------------------------------- light
val LightPrimary = Color(0xFF1B5E4B)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFA7F2D4)
val LightOnPrimaryContainer = Color(0xFF002115)

val LightSecondary = Color(0xFF4B635A)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFCDE9DC)
val LightOnSecondaryContainer = Color(0xFF072019)

val LightTertiary = Color(0xFF3F6375)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFC2E8FD)
val LightOnTertiaryContainer = Color(0xFF001E2B)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFBFDF9)
val LightOnBackground = Color(0xFF191C1B)
val LightSurface = Color(0xFFFBFDF9)
val LightOnSurface = Color(0xFF191C1B)
val LightSurfaceVariant = Color(0xFFDCE5DF)
val LightOnSurfaceVariant = Color(0xFF404944)
val LightOutline = Color(0xFF707974)

// -------------------------------------------------------------------- dark
val DarkPrimary = Color(0xFF8BD5B9)
val DarkOnPrimary = Color(0xFF003827)
val DarkPrimaryContainer = Color(0xFF00513A)
val DarkOnPrimaryContainer = Color(0xFFA7F2D4)

val DarkSecondary = Color(0xFFB2CCC0)
val DarkOnSecondary = Color(0xFF1D352D)
val DarkSecondaryContainer = Color(0xFF344C43)
val DarkOnSecondaryContainer = Color(0xFFCDE9DC)

val DarkTertiary = Color(0xFFA7CCE0)
val DarkOnTertiary = Color(0xFF0B3446)
val DarkTertiaryContainer = Color(0xFF264B5D)
val DarkOnTertiaryContainer = Color(0xFFC2E8FD)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF101413)
val DarkOnBackground = Color(0xFFE1E3E0)
val DarkSurface = Color(0xFF101413)
val DarkOnSurface = Color(0xFFE1E3E0)
val DarkSurfaceVariant = Color(0xFF404944)
val DarkOnSurfaceVariant = Color(0xFFBFC9C3)
val DarkOutline = Color(0xFF8A938D)

/**
 * Colours whose meaning is fixed regardless of the theme.
 *
 * They are exposed through [FinanceColors] rather than the Material scheme
 * because "money in is green, money out is red" is a semantic the app owns,
 * not something a dynamic wallpaper palette should be allowed to change.
 */
data class FinanceColors(
    val income: Color,
    val onIncomeContainer: Color,
    val incomeContainer: Color,
    val expense: Color,
    val onExpenseContainer: Color,
    val expenseContainer: Color,
    val transfer: Color,
    val savings: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val positive: Color,
    val negative: Color,
    val neutral: Color,
    /** Series colours for charts, in the order they should be used. */
    val chartSeries: List<Color>,
) {
    companion object {
        val Light = FinanceColors(
            income = Color(0xFF1B7A4B),
            onIncomeContainer = Color(0xFF00210F),
            incomeContainer = Color(0xFFB6F2CD),
            expense = Color(0xFFB3261E),
            onExpenseContainer = Color(0xFF410002),
            expenseContainer = Color(0xFFFFDAD6),
            transfer = Color(0xFF3F6375),
            savings = Color(0xFF00695C),
            warning = Color(0xFF8F6A00),
            warningContainer = Color(0xFFFFE08C),
            onWarningContainer = Color(0xFF291D00),
            positive = Color(0xFF1B7A4B),
            negative = Color(0xFFB3261E),
            neutral = Color(0xFF5F6B66),
            chartSeries = listOf(
                Color(0xFF1B5E4B), Color(0xFF0277BD), Color(0xFFAD1457), Color(0xFFEF6C00),
                Color(0xFF5E35B1), Color(0xFF00838F), Color(0xFF558B2F), Color(0xFFC62828),
                Color(0xFF6D4C41), Color(0xFF455A64), Color(0xFF9E9D24), Color(0xFF7B1FA2),
            ),
        )

        val Dark = FinanceColors(
            income = Color(0xFF6FD79B),
            onIncomeContainer = Color(0xFFB6F2CD),
            incomeContainer = Color(0xFF0B4A2C),
            expense = Color(0xFFFFB4AB),
            onExpenseContainer = Color(0xFFFFDAD6),
            expenseContainer = Color(0xFF6B1410),
            transfer = Color(0xFFA7CCE0),
            savings = Color(0xFF6FD4C6),
            warning = Color(0xFFFFD54F),
            warningContainer = Color(0xFF4A3800),
            onWarningContainer = Color(0xFFFFE08C),
            positive = Color(0xFF6FD79B),
            negative = Color(0xFFFFB4AB),
            neutral = Color(0xFFA5B0AB),
            chartSeries = listOf(
                Color(0xFF6FD79B), Color(0xFF7EC8F0), Color(0xFFF48FB1), Color(0xFFFFB74D),
                Color(0xFFB39DDB), Color(0xFF7ED6DE), Color(0xFFAED581), Color(0xFFEF9A9A),
                Color(0xFFBCAAA4), Color(0xFFB0BEC5), Color(0xFFDCE775), Color(0xFFCE93D8),
            ),
        )
    }
}
