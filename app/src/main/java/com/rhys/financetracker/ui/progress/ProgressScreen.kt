package com.rhys.financetracker.ui.progress

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.report.ProgressReport
import com.rhys.financetracker.domain.report.money
import com.rhys.financetracker.ui.components.LocalIntroShowing
import com.rhys.financetracker.ui.components.PopupTurns
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.rememberReduceMotion
import com.rhys.financetracker.ui.theme.Bubble
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The progress reports kept so far, and the ones not yet seen. */
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Every report kept, newest first. */
    val reports: StateFlow<List<ProgressReport>> = settingsRepository.settings
        .map { it.progressReports }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The reports the pop-up hasn't shown yet. */
    val unseen: StateFlow<List<ProgressReport>> = settingsRepository.settings
        .map { s -> s.progressReports.filter { it.key in s.progressUnseen } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markSeen() {
        viewModelScope.launch { settingsRepository.markProgressSeen() }
    }
}

/**
 * At the start of each month and halfway through: how it went, for the two
 * of you together and for each of you — after the opening animation, and
 * not on top of any other pop-up. Kept under More → Progress reports.
 */
@Composable
fun ProgressPopup(onOpenReports: () -> Unit, viewModel: ProgressViewModel = hiltViewModel()) {
    val unseen by viewModel.unseen.collectAsStateWithLifecycle()
    val introShowing = LocalIntroShowing.current
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(unseen.isNotEmpty(), introShowing, PopupTurns.open) {
        if (open || unseen.isEmpty() || introShowing || PopupTurns.open) return@LaunchedEffect
        delay(POPUP_DELAY_MS)
        if (PopupTurns.open) return@LaunchedEffect
        PopupTurns.open = true
        open = true
    }
    if (!open) return
    DisposableEffect(Unit) { onDispose { PopupTurns.open = false } }
    val close = {
        open = false
        viewModel.markSeen()
    }
    // The household first, then each person.
    val shown = unseen.sortedBy { if (it.key.endsWith("|all")) 0 else 1 }
    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Icons.Outlined.Insights, contentDescription = null) },
        title = { Text(shown.firstOrNull()?.title ?: "How you're doing") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                shown.forEachIndexed { i, report ->
                    if (i > 0) HorizontalDivider(color = FinanceTheme.colors.cardEdge)
                    ReportSummary(report, showTips = shown.size == 1)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = {
                close()
                onOpenReports()
            }) { Text("See the details") }
        },
    )
}

/** Every report kept, newest first, a card for each month or half-month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onBack: () -> Unit, viewModel: ProgressViewModel = hiltViewModel()) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val periods = reports.groupBy { it.key.substringBefore('|') }.values.toList()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress reports") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (periods.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Insights,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Text("No reports yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "At the start of each month, and again halfway through, the app compares how it's " +
                                "going with the month before — spending, saving and what's owed — and says " +
                                "whether you're doing better or need to improve. They're kept here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(periods, key = { it.first().key.substringBefore('|') }) { period ->
                val first = period.first()
                SectionCard(title = first.title, subtitle = "Made ${com.rhys.financetracker.core.time.DateUtils.formatShort(first.madeOn)}") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        period.sortedBy { if (it.key.endsWith("|all")) 0 else 1 }.forEachIndexed { i, report ->
                            if (i > 0) HorizontalDivider(color = FinanceTheme.colors.cardEdge)
                            ReportSummary(report, showTips = true)
                        }
                    }
                }
            }
        }
    }
}

/** Whose it is and the verdict, what it comes to, each figure against before, and what to do. */
@Composable
internal fun ReportSummary(report: ProgressReport, showTips: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                report.scope,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            VerdictPill(report.verdict)
        }
        Text(report.headline, style = MaterialTheme.typography.bodyMedium)
        report.lines.forEach { FigureRow(it) }
        if (showTips && report.tips.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                report.tips.forEach { tip ->
                    Row {
                        Box(
                            Modifier
                                .padding(top = 7.dp, end = 8.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(verdictBubble(report.verdict).brush),
                        )
                        Text(
                            tip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The verdict in a bubble: mint for better, ocean for the same, sunset for needs improving. */
@Composable
private fun VerdictPill(verdict: ProgressReport.Verdict) {
    val reduceMotion = rememberReduceMotion()
    val pop = remember { Animatable(if (reduceMotion) 1f else 0.6f) }
    LaunchedEffect(verdict) { if (!reduceMotion) pop.animateTo(1f, tween(450)) }
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .clip(RoundedCornerShape(50))
            .background(verdictBubble(verdict).brush)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            verdict.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = FinanceTheme.colors.onTile,
        )
    }
}

@Composable
private fun verdictBubble(verdict: ProgressReport.Verdict): Bubble = when (verdict) {
    ProgressReport.Verdict.BETTER -> FinanceTheme.colors.bubbleMint
    ProgressReport.Verdict.STEADY -> FinanceTheme.colors.bubbleOcean
    ProgressReport.Verdict.WORSE -> FinanceTheme.colors.bubbleSunset
}

/** "Spent  £1,800 · £300 less" — green when it's better, red when it's worse. */
@Composable
private fun FigureRow(line: ProgressReport.Line) {
    val colors = FinanceTheme.colors
    val tint = when (line.better) {
        true -> colors.positive
        false -> colors.negative
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val owed = line.label.startsWith("Owed")
    val move = line.nowMinor - line.beforeMinor
    val figure = if (owed) {
        when {
            line.nowMinor > 0 -> "up ${money(line.nowMinor)}"
            line.nowMinor < 0 -> "down ${money(line.nowMinor)}"
            else -> "no change"
        }
    } else {
        money(line.nowMinor)
    }
    val change = when {
        owed -> null
        line.better == null && move == 0L -> "the same"
        line.better == null -> "about the same"
        move > 0 -> "${money(move)} more"
        else -> "${money(move)} less"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (line.better == null) colors.chartGrid else tint))
        Spacer(Modifier.width(8.dp))
        Text(line.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(figure, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (change != null) {
            Text(
                "  ·  $change",
                style = MaterialTheme.typography.bodySmall,
                color = if (line.better == null) MaterialTheme.colorScheme.onSurfaceVariant else tint,
            )
        }
    }
}

private const val POPUP_DELAY_MS = 900L
