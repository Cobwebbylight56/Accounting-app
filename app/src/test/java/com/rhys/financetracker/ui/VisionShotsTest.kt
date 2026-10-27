package com.rhys.financetracker.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.math.ceil
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.domain.model.ThemeMode
import com.rhys.financetracker.domain.report.FinancialSummary
import com.rhys.financetracker.domain.report.MonthPoint
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.BreakdownView
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.ChartLegend
import com.rhys.financetracker.ui.components.DonutChart
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.LineChart
import com.rhys.financetracker.ui.components.LineSeries
import com.rhys.financetracker.ui.components.MarkerShape
import com.rhys.financetracker.ui.components.MonthTrend
import com.rhys.financetracker.ui.components.ProgressBarRow
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.StatTile
import com.rhys.financetracker.ui.components.TrendLineChart
import com.rhys.financetracker.ui.dashboard.DashboardScope
import com.rhys.financetracker.ui.dashboard.DashboardState
import com.rhys.financetracker.ui.dashboard.HomeHeader
import com.rhys.financetracker.ui.dashboard.HomeTiles
import com.rhys.financetracker.ui.dashboard.MonthList
import com.rhys.financetracker.ui.dashboard.PersonTabs
import com.rhys.financetracker.ui.navigation.Routes
import com.rhys.financetracker.ui.navigation.SoftBottomBar
import com.rhys.financetracker.ui.theme.FinanceTheme
import com.rhys.financetracker.ui.theme.FinanceTrackerTheme
import java.io.File
import java.io.FileOutputStream
import java.time.YearMonth
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of how the app looks, made on the build server so the design can
 * be checked without a phone: Home and the charts, dark and light, with
 * made-up figures. Only made when asked (VISION_SHOTS=1); otherwise skipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = Application::class, qualifiers = "w400dp-h2000dp-xxhdpi")
class VisionShotsTest {

    private companion object {
        const val SHOT = "shot"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyWhenAsked() {
        assumeTrue(System.getProperty("visionShots") == "1" || System.getenv("VISION_SHOTS") == "1")
    }

    @Test fun homeDark() = shoot("1-home-dark", ThemeMode.DARK) { Home() }

    @Test fun homeLight() = shoot("2-home-light", ThemeMode.LIGHT) { Home() }

    @Test fun chartsDark() = shoot("3-charts-dark", ThemeMode.DARK) { Charts() }

    @Test fun chartsLight() = shoot("4-charts-light", ThemeMode.LIGHT) { Charts() }

    @Test fun moreDark() = shoot("5-more-dark", ThemeMode.DARK) { More() }

    @Test fun moreLight() = shoot("6-more-light", ThemeMode.LIGHT) { More() }

    // The finished picture, after the light has swept across and before the fade.
    @Test fun intro() = shoot("7-intro", ThemeMode.DARK, advanceMs = 3_700, hold = true) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(760.dp)) {
            com.rhys.financetracker.ui.intro.IntroScreen(onFinished = {})
        }
    }

    @Test fun progressDark() = shoot("8-progress-dark", ThemeMode.DARK) { Progress() }

    @Test fun progressLight() = shoot("9-progress-light", ThemeMode.LIGHT) { Progress() }

    @Test fun tipsDark() = shoot("10-tips-dark", ThemeMode.DARK) { Tips() }

    @Test fun tipsLight() = shoot("11-tips-light", ThemeMode.LIGHT) { Tips() }

    /**
     * [hold] stops the clock at [advanceMs] rather than letting every
     * animation run to its end — for the intro, which ends by fading away.
     */
    private fun shoot(
        name: String,
        mode: ThemeMode,
        advanceMs: Long = 3_000,
        hold: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        if (hold) compose.mainClock.autoAdvance = false
        compose.setContent {
            FinanceTrackerTheme(themeMode = mode) {
                // As a screen's Scaffold does: the page colour, and text on it.
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .testTag(SHOT)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
        // Let every chart draw itself in, and every figure count up.
        compose.mainClock.advanceTimeBy(advanceMs)
        if (!hold) compose.waitForIdle()
        // Drawn straight into a picture, then cut to what was drawn.
        val bottom = compose.onNodeWithTag(SHOT).fetchSemanticsNode().boundsInRoot.bottom
        val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(full))
        val shot = Bitmap.createBitmap(full, 0, 0, root.width, minOf(root.height, ceil(bottom).toInt()))
        val dir = File("build/vision-shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val month = YearMonth.of(2026, 10)
    private val months = (5 downTo 0).map { month.minusMonths(it.toLong()) }

    private val state = DashboardState(
        isLoading = false,
        month = month,
        scope = DashboardScope(personIds = setOf(1L), label = "Rhys"),
        people = listOf(
            PersonEntity(id = 1L, name = "Rhys Evans", colorHex = "#7B61FF"),
            PersonEntity(id = 2L, name = "Hannah", colorHex = "#E0457A"),
        ),
        summary = FinancialSummary(
            totalBalanceMinor = 1_284_37,
            totalSavingsMinor = 6_420_00,
            totalLiabilitiesMinor = -1_309_89,
            netWorthMinor = 6_394_48,
            monthIncomeMinor = 2_186_23,
            monthExpenseMinor = 1_642_18,
            committedRecurringMinor = 412_00,
            savingsInMinor = 200_00,
        ),
        monthlyTrend = months.mapIndexed { i, m ->
            MonthPoint(
                yearMonth = m,
                incomeMinor = listOf(2_150_00L, 2_186_23L, 2_420_00L, 2_186_23L, 2_300_00L, 2_186_23L)[i],
                expenseMinor = listOf(1_980_00L, 2_310_00L, 1_640_00L, 1_890_00L, 2_050_00L, 1_642_18L)[i],
                cardMinor = listOf(240_00L, 410_00L, 180_00L, 320_00L, 290_00L, 210_00L)[i],
                subscriptionsMinor = listOf(38_97L, 38_97L, 43_96L, 43_96L, 43_96L, 43_96L)[i],
            )
        },
    )

    @Composable
    private fun Home() {
        HomeHeader(state, onMenu = {}, onAvatar = {}, onCustomise = {})
        PersonTabs(
            individuals = state.people,
            scope = state.scope,
            onPerson = {},
            onShared = {},
            onChooseShared = {},
        )
        HomeTiles(state, onOpenAccounts = {})
        MonthList(state)
        SectionCard(title = "Income against spending", subtitle = "The last 6 months") {
            MonthTrend(points = state.monthlyTrend, view = BreakdownView.LINE, selected = month)
        }
        SoftBottomBar(currentRoute = Routes.DASHBOARD, onSelect = {})
    }

    @Composable
    private fun Charts() {
        SectionCard(title = "Month by month", subtitle = "Money in against money out") {
            MonthTrend(points = state.monthlyTrend, view = BreakdownView.BARS, selected = month)
        }
        val labels = months.map { com.rhys.financetracker.core.time.DateUtils.monthNameShort(it.monthValue) }
        val colors = FinanceTheme.colors
        SectionCard(title = "Categories over time", subtitle = "The 3 biggest, and subscriptions") {
            TrendLineChart(
                labels = labels,
                series = listOf(
                    LineSeries("Groceries", listOf(330_00L, 360_00L, 650_00L, 140_00L, 330_00L, 210_00L), colors.chartSeries[0]),
                    LineSeries("Fuel", listOf(120_00L, 230_00L, 140_00L, 140_00L, 160_00L, 195_00L), colors.chartSeries[1], dashed = true, marker = MarkerShape.SQUARE),
                    LineSeries("Shopping", listOf(85_00L, 10_00L, 380_00L, 30_00L, 115_00L, 85_00L), colors.chartSeries[2]),
                    LineSeries("Subscriptions", listOf(38_97L, 38_97L, 43_96L, 43_96L, 43_96L, 43_96L), colors.subscriptions),
                ),
            )
        }
        SectionCard(title = "Where it went", subtitle = "October") {
            val slices = listOf(
                ChartEntry("Groceries", 412f, colors.chartSeries[0], "£412.00"),
                ChartEntry("Bills", 380f, colors.chartSeries[1], "£380.00"),
                ChartEntry("Fuel", 195f, colors.chartSeries[2], "£195.00"),
                ChartEntry("Eating out", 142f, colors.chartSeries[3], "£142.00"),
                ChartEntry("Shopping", 85f, colors.chartSeries[4], "£85.00"),
                ChartEntry("Subscriptions", 44f, colors.chartSeries[6], "£44.00"),
            )
            DonutChart(entries = slices, centreValue = "£1,258.00", centreLabel = "spent")
            ChartLegend(entries = slices)
        }
    }

    @Composable
    private fun Progress() {
        // The tabs along the top of Payments.
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.rhys.financetracker.ui.components.PillTab("Everyone", selected = false, onClick = {})
            com.rhys.financetracker.ui.components.PillTab("Rhys", selected = true, onClick = {}, dot = FinanceTheme.colors.tileBlue)
            com.rhys.financetracker.ui.components.PillTab("Hannah", selected = false, onClick = {}, dot = FinanceTheme.colors.tileBlush)
            com.rhys.financetracker.ui.components.PillTab("Joint", selected = false, onClick = {})
        }
        val period = com.rhys.financetracker.domain.report.ProgressSchedule.current(java.time.LocalDate.of(2026, 10, 2))
        val together = com.rhys.financetracker.domain.report.ProgressReporter.build(
            period, "Together", "all",
            now = com.rhys.financetracker.domain.report.ProgressFigures(452_000, 301_500, 40_000, -12_000, mapOf("Eating out" to 9_000, "Groceries" to 52_000)),
            before = com.rhys.financetracker.domain.report.ProgressFigures(452_000, 338_000, 20_000, 6_000, mapOf("Eating out" to 24_000, "Groceries" to 55_000)),
            madeOn = java.time.LocalDate.of(2026, 10, 2),
        )!!
        val hannah = com.rhys.financetracker.domain.report.ProgressReporter.build(
            period, "Hannah", "2",
            now = com.rhys.financetracker.domain.report.ProgressFigures(186_000, 171_000, 0, 9_000, mapOf("Shopping" to 46_000)),
            before = com.rhys.financetracker.domain.report.ProgressFigures(186_000, 139_000, 10_000, 0, mapOf("Shopping" to 14_000)),
            madeOn = java.time.LocalDate.of(2026, 10, 2),
        )!!
        // The receipt screen's "Paid from": whose, then which account.
        val personRhys = PersonEntity(id = 1, name = "Rhys Evans", colorHex = "#1F5FD0")
        val personHannah = PersonEntity(id = 2, name = "Hannah", colorHex = "#B4307A")
        val personJoint = PersonEntity(id = 3, name = "Joint", colorHex = "#888888", isShared = true)
        fun account(id: Long, name: String, type: com.rhys.financetracker.domain.model.AccountType, personId: Long) =
            com.rhys.financetracker.data.local.entity.AccountEntity(id = id, name = name, type = type, personId = personId, colorHex = "#000000")
        com.rhys.financetracker.ui.components.PersonAccountPicker(
            label = "Paid from",
            people = listOf(personRhys, personHannah, personJoint),
            accounts = listOf(
                account(1, "Lloyds Current Account", com.rhys.financetracker.domain.model.AccountType.CURRENT, 1),
                account(3, "Zopa", com.rhys.financetracker.domain.model.AccountType.CREDIT_CARD, 1),
                account(5, "car", com.rhys.financetracker.domain.model.AccountType.LOAN, 1),
                account(2, "Lloyds Current Account", com.rhys.financetracker.domain.model.AccountType.CURRENT, 2),
                account(6, "Nationwide Current Account", com.rhys.financetracker.domain.model.AccountType.CURRENT, 3),
            ),
            selectedId = 1,
            onSelect = {},
        )
        SectionCard(title = together.title, subtitle = "Made 2 Oct") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.rhys.financetracker.ui.progress.ReportSummary(together, showTips = true)
                androidx.compose.material3.HorizontalDivider(color = FinanceTheme.colors.cardEdge)
                com.rhys.financetracker.ui.progress.ReportSummary(hannah, showTips = true)
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    /** A tip for something new, an everyday one, the New mark, and a balance with ±. */
    @Composable
    private fun Tips() {
        val book = com.rhys.financetracker.domain.tips.TipBook.ALL
        com.rhys.financetracker.ui.components.TipCardContent(book.first { it.id == "home-left-over" }, onGotIt = {}, onTipsOff = {})
        com.rhys.financetracker.ui.components.TipCardContent(book.first { it.id == "home-add" }, onGotIt = {}, onTipsOff = {})
        SectionCard(title = "Settings") {
            com.rhys.financetracker.ui.settings.SettingsItem(
                title = "Tips & reminders",
                subtitle = "Tips, New marks on what's new, and which pop-ups appear",
                trailing = { com.rhys.financetracker.ui.components.NewMark() },
            )
        }
        com.rhys.financetracker.ui.components.AmountField(
            label = "Balance (owed is a minus)",
            value = "-250.00",
            onValueChange = {},
            allowNegative = true,
            supportingText = "£250 owed is −250.00: type 25000, then tap ±.",
        )
    }

    @Composable
    private fun More() {
        val colors = FinanceTheme.colors
        SectionCard(title = "Each month", subtitle = "What each subscription came to") {
            val audible = listOf(899L, 899L, 899L, 899L, 899L, 899L)
            val spotify = listOf(1_199L, 1_199L, 1_199L, 1_199L, 1_199L, 1_199L)
            val uber = listOf(499L, 499L, 499L, 0L, 0L, 0L)
            val claude = listOf(0L, 0L, 1_800L, 1_800L, 1_800L, 1_800L)
            GroupedBarChart(
                groups = months.indices.map { i ->
                    val parts = listOf(
                        ChartEntry("Spotify", spotify[i].toFloat(), colors.chartSeries[0], Money.format(spotify[i])),
                        ChartEntry("Audible", audible[i].toFloat(), colors.chartSeries[1], Money.format(audible[i])),
                        ChartEntry("Claude", claude[i].toFloat(), colors.chartSeries[2], Money.format(claude[i])),
                        ChartEntry("Uber One", uber[i].toFloat(), colors.chartSeries[3], Money.format(uber[i])),
                    )
                    val total = parts.sumOf { it.value.toDouble() }.toFloat()
                    BarGroup(
                        com.rhys.financetracker.core.time.DateUtils.monthNameShort(months[i].monthValue),
                        listOf(ChartEntry("Total", total, colors.chartSeries[0], Money.format(total.toLong()), segments = parts.filter { it.value > 0f })),
                    )
                },
                height = 170.dp,
            )
        }
        SectionCard(title = "Owed at each month end") {
            LineChart(
                points = months.mapIndexed { i, m -> m.toString() to listOf(0L, 130_89L, 180_00L, 96_40L, 241_10L, 130_89L)[i] },
                lineColor = colors.chartCard,
                height = 150.dp,
            )
        }
        SectionCard(title = "Budgets") {
            ProgressBarRow("Groceries", "£412 of £500", 0.82f, colors.chartSeries[0])
            ProgressBarRow("Eating out", "£142 of £120", 1f, colors.chartOut, secondary = "£22 over")
            ProgressBarRow("Fuel", "£195 of £250", 0.78f, colors.chartIn)
        }
        androidx.compose.foundation.layout.Row {
            StatTile(label = "Spent this month", value = "£1,642.18", caption = "£230 less than last month", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            StatTile(label = "Saved", value = "£200.00", caption = "Put aside", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
    }
}
