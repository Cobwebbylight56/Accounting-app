package com.rhys.financetracker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette: Vision 2.
 *
 * Deep ink and lavender, with the colour saved for what matters: the money
 * bubbles on Home, the raised bubble in the bar along the bottom, and the
 * charts. Everything else stays quiet so those can glow.
 *
 * Every text colour meets 4.5:1 against what it sits on, in both themes, and
 * white on every bubble's gradient does too.
 */

// ------------------------------------------------------------------- light
// Porcelain and lavender: white cards on a faintly violet page.
val LightPrimary = Color(0xFF5B4BD6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE6E1FF)
val LightOnPrimaryContainer = Color(0xFF1C1062)

val LightSecondary = Color(0xFF00806A)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFC6F3E6)
val LightOnSecondaryContainer = Color(0xFF00382D)

val LightTertiary = Color(0xFFC2366F)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFD9E6)
val LightOnTertiaryContainer = Color(0xFF3E0420)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFF5F4FB)
val LightOnBackground = Color(0xFF17152B)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF17152B)
val LightSurfaceVariant = Color(0xFFE4E1F2)
val LightOnSurfaceVariant = Color(0xFF4D4A66)
val LightOutline = Color(0xFF77748F)
val LightOutlineVariant = Color(0xFFDCD8EC)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFFFFFF)
val LightSurfaceContainer = Color(0xFFF0EEF9)
val LightSurfaceContainerHigh = Color(0xFFE9E6F6)
val LightSurfaceContainerHighest = Color(0xFFE2DFF2)

// -------------------------------------------------------------------- dark
// Midnight ink, the cards a step lighter, lavender for what can be tapped.
val DarkPrimary = Color(0xFFA79BFF)
val DarkOnPrimary = Color(0xFF1C1150)
val DarkPrimaryContainer = Color(0xFF3B2F8C)
val DarkOnPrimaryContainer = Color(0xFFE5E0FF)

val DarkSecondary = Color(0xFF66E3C4)
val DarkOnSecondary = Color(0xFF003829)
val DarkSecondaryContainer = Color(0xFF1E4F45)
val DarkOnSecondaryContainer = Color(0xFFB9F5E5)

val DarkTertiary = Color(0xFFFF9EC4)
val DarkOnTertiary = Color(0xFF5A0F33)
val DarkTertiaryContainer = Color(0xFF6B2348)
val DarkOnTertiaryContainer = Color(0xFFFFD9E7)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF0B0D1A)
val DarkOnBackground = Color(0xFFECEDFB)
val DarkSurface = Color(0xFF121428)
val DarkOnSurface = Color(0xFFECEDFB)
val DarkSurfaceVariant = Color(0xFF2E3358)
val DarkOnSurfaceVariant = Color(0xFFB4B8D6)
val DarkOutline = Color(0xFF8A8FB3)
val DarkOutlineVariant = Color(0xFF34385E)

val DarkSurfaceContainerLowest = Color(0xFF080A15)
val DarkSurfaceContainerLow = Color(0xFF161931)
val DarkSurfaceContainer = Color(0xFF1B1E39)
val DarkSurfaceContainerHigh = Color(0xFF22264A)
val DarkSurfaceContainerHighest = Color(0xFF2A2F57)

/**
 * A bubble's two colours, top-left to bottom-right: the Home tiles, the
 * raised tab in the bar along the bottom, a card's balance.
 */
data class Bubble(val start: Color, val end: Color) {
    val brush: androidx.compose.ui.graphics.Brush
        get() = androidx.compose.ui.graphics.Brush.linearGradient(listOf(start, end))
}

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
    /**
     * Credit: spending on a card, and what's owed on one. Yellow, so it
     * stands apart from spending from the bank (red). Paying it off is
     * green, like money in. This one is for figures written in it.
     */
    val card: Color,
    /** Subscriptions, wherever they are drawn as a part of money out. */
    val subscriptions: Color,
    /** Series colours for charts, in the order they should be used. */
    val chartSeries: List<Color>,
    /**
     * Money in and money out as lines and bars on a chart, and the card and
     * subscription parts of money out. Aqua, coral, yellow and violet: a set
     * that passes a colour-blindness check side by side, in this order —
     * bank, subscriptions, card up a bar — against the card in both themes.
     * Not the green and red of the figures: a red–green pair cannot be told
     * apart by about one man in twelve.
     */
    val chartIn: Color,
    val chartOut: Color,
    val chartCard: Color,
    /** Gridlines, and the faint track a ring or a bar runs along. */
    val chartGrid: Color,
    /** The home screen's bubbles, and text on them. */
    val bubbleOcean: Bubble,
    val bubbleSunset: Bubble,
    val bubbleMint: Bubble,
    val bubbleViolet: Bubble,
    /** Flat colours of the bubbles, for the small things drawn in the same colour. */
    val tileBlue: Color,
    val tileSage: Color,
    val tileBlush: Color,
    val tileMist: Color,
    val onTile: Color,
    /** The bar along the bottom, its hairline edge, and the raised bubble behind the tab you are on. */
    val navBar: Color,
    val navEdge: Color,
    val navSelected: Bubble,
    val onNavSelected: Color,
    /** The hairline round a card. */
    val cardEdge: Color,
) {
    companion object {
        private val Ocean = Bubble(Color(0xFF1F5FD0), Color(0xFF0F7490))
        private val Sunset = Bubble(Color(0xFFB4307A), Color(0xFFC4472A))
        private val Mint = Bubble(Color(0xFF08766A), Color(0xFF0A7A55))
        private val Violet = Bubble(Color(0xFF5B47E0), Color(0xFF8B3FD9))

        val Light = FinanceColors(
            income = Color(0xFF14804A),
            onIncomeContainer = Color(0xFF00210F),
            incomeContainer = Color(0xFFB6F2CD),
            expense = Color(0xFFC0262D),
            onExpenseContainer = Color(0xFF410002),
            expenseContainer = Color(0xFFFFDAD6),
            transfer = Color(0xFF3F5BA9),
            savings = Color(0xFF00806A),
            warning = Color(0xFF8F6A00),
            warningContainer = Color(0xFFFFE08C),
            onWarningContainer = Color(0xFF291D00),
            positive = Color(0xFF14804A),
            negative = Color(0xFFC0262D),
            neutral = Color(0xFF5F5D72),
            card = Color(0xFF856700),
            subscriptions = Color(0xFF6250D6),
            chartSeries = listOf(
                Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
                Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF6250D6), Color(0xFFE34948),
            ),
            chartIn = Color(0xFF1BAF7A),
            chartOut = Color(0xFFEB6834),
            chartCard = Color(0xFFEDA100),
            chartGrid = Color(0xFFE6E3F1),
            bubbleOcean = Ocean,
            bubbleSunset = Sunset,
            bubbleMint = Mint,
            bubbleViolet = Violet,
            tileBlue = Ocean.start,
            tileSage = Mint.start,
            tileBlush = Sunset.start,
            tileMist = Violet.start,
            onTile = Color(0xFFFFFFFF),
            navBar = Color(0xFFFFFFFF),
            navEdge = Color(0xFFE4E1F2),
            navSelected = Bubble(Color(0xFF6A4CF5), Color(0xFFC2366F)),
            onNavSelected = Color(0xFFFFFFFF),
            cardEdge = Color(0xFFECEAF5),
        )

        val Dark = FinanceColors(
            income = Color(0xFF6FD79B),
            onIncomeContainer = Color(0xFFB6F2CD),
            incomeContainer = Color(0xFF0B4A2C),
            expense = Color(0xFFFF8A8A),
            onExpenseContainer = Color(0xFFFFDAD6),
            expenseContainer = Color(0xFF6B1410),
            transfer = Color(0xFF9DB4FF),
            savings = Color(0xFF66E3C4),
            warning = Color(0xFFFFD54F),
            warningContainer = Color(0xFF4A3800),
            onWarningContainer = Color(0xFFFFE08C),
            positive = Color(0xFF6FD79B),
            negative = Color(0xFFFF8A8A),
            neutral = Color(0xFFA9ACC6),
            card = Color(0xFFF2C94C),
            subscriptions = Color(0xFF9085E9),
            chartSeries = listOf(
                Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
                Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
            ),
            chartIn = Color(0xFF199E70),
            chartOut = Color(0xFFD95926),
            chartCard = Color(0xFFC98500),
            chartGrid = Color(0xFF262A4A),
            bubbleOcean = Ocean,
            bubbleSunset = Sunset,
            bubbleMint = Mint,
            bubbleViolet = Violet,
            tileBlue = Ocean.start,
            tileSage = Mint.start,
            tileBlush = Sunset.start,
            tileMist = Violet.start,
            onTile = Color(0xFFFFFFFF),
            navBar = Color(0xFF1B1E39),
            navEdge = Color(0xFF2E3358),
            navSelected = Bubble(Color(0xFF7B61FF), Color(0xFFE0457A)),
            onNavSelected = Color(0xFFFFFFFF),
            cardEdge = Color(0xFF22264A),
        )
    }
}
