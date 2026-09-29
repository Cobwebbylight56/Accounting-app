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
// Soft slate blue, sage and blush on white: calm, and quiet enough that the
// only strong colours on screen are the ones that mean something — money in,
// money out, a warning.
val LightPrimary = Color(0xFF4F6482)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6DFEB)
val LightOnPrimaryContainer = Color(0xFF0B1D33)

val LightSecondary = Color(0xFF5E746C)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD5E0DC)
val LightOnSecondaryContainer = Color(0xFF16241F)

val LightTertiary = Color(0xFF7F5F6C)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFEEDCE3)
val LightOnTertiaryContainer = Color(0xFF2E1822)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFF6F7F9)
val LightOnBackground = Color(0xFF1B1F26)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1B1F26)
val LightSurfaceVariant = Color(0xFFE3E7ED)
val LightOnSurfaceVariant = Color(0xFF474E59)
val LightOutline = Color(0xFF737A86)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF3F5F8)
val LightSurfaceContainer = Color(0xFFEEF1F5)
val LightSurfaceContainerHigh = Color(0xFFE8ECF1)
val LightSurfaceContainerHighest = Color(0xFFE2E6EC)

// -------------------------------------------------------------------- dark
val DarkPrimary = Color(0xFFB3C6E2)
val DarkOnPrimary = Color(0xFF1C2E47)
val DarkPrimaryContainer = Color(0xFF354760)
val DarkOnPrimaryContainer = Color(0xFFD6DFEB)

val DarkSecondary = Color(0xFFB9CAC3)
val DarkOnSecondary = Color(0xFF243530)
val DarkSecondaryContainer = Color(0xFF3B4B45)
val DarkOnSecondaryContainer = Color(0xFFD5E0DC)

val DarkTertiary = Color(0xFFE0C2CE)
val DarkOnTertiary = Color(0xFF432A36)
val DarkTertiaryContainer = Color(0xFF5C4450)
val DarkOnTertiaryContainer = Color(0xFFEEDCE3)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF12151A)
val DarkOnBackground = Color(0xFFE2E5EA)
val DarkSurface = Color(0xFF181C22)
val DarkOnSurface = Color(0xFFE2E5EA)
val DarkSurfaceVariant = Color(0xFF424852)
val DarkOnSurfaceVariant = Color(0xFFC2C8D2)
val DarkOutline = Color(0xFF8C929C)

val DarkSurfaceContainerLowest = Color(0xFF0E1115)
val DarkSurfaceContainerLow = Color(0xFF1C2027)
val DarkSurfaceContainer = Color(0xFF20252C)
val DarkSurfaceContainerHigh = Color(0xFF2A2F37)
val DarkSurfaceContainerHighest = Color(0xFF353A42)

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
    /** The home screen's tiles: slate blue, sage, blush and mist, and text on them. */
    val tileBlue: Color,
    val tileSage: Color,
    val tileBlush: Color,
    val tileMist: Color,
    val onTile: Color,
    /** The bottom bar, and the raised circle behind the tab you are on. */
    val navBar: Color,
    val navSelected: Color,
    val onNavSelected: Color,
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
            tileBlue = Color(0xFF9DB0C9),
            tileSage = Color(0xFFD5E0DC),
            tileBlush = Color(0xFFDCC5CF),
            tileMist = Color(0xFFC9D4E1),
            onTile = Color(0xFF1C2635),
            navBar = Color(0xFFC9C3C3),
            navSelected = Color(0xFF93A7C1),
            onNavSelected = Color(0xFF15202F),
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
            tileBlue = Color(0xFF3C4D66),
            tileSage = Color(0xFF394a44),
            tileBlush = Color(0xFF55414C),
            tileMist = Color(0xFF3A4555),
            onTile = Color(0xFFEDF1F7),
            navBar = Color(0xFF2B2F36),
            navSelected = Color(0xFF7C92B0),
            onNavSelected = Color(0xFF0F1826),
        )
    }
}
