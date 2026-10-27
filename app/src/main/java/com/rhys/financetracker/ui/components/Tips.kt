package com.rhys.financetracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.tips.Tip
import com.rhys.financetracker.domain.tips.TipBook
import com.rhys.financetracker.domain.tips.TipPlace
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Which tip each page shows, and what's still to point out as New. */
@HiltViewModel
class TipsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    fun tipFor(place: TipPlace): Flow<Tip?> = settingsRepository.settings
        .map { TipBook.next(place, it.tipsSeen, it.tipsOn, it.newPointersOffAt) }
        .distinctUntilChanged()

    fun isNew(id: String): Flow<Boolean> = settingsRepository.settings
        .map { TipBook.pointsOut(id, it.tipsSeen, it.newPointersOffAt) }
        .distinctUntilChanged()

    fun gotIt(tip: Tip) {
        viewModelScope.launch { settingsRepository.markTipSeen(tip.id) }
    }

    /** Everyday tips off; what's new is still pointed out. */
    fun tipsOff(tip: Tip) {
        viewModelScope.launch {
            settingsRepository.markTipSeen(tip.id)
            settingsRepository.setTipsOn(false)
        }
    }
}

/**
 * The tip for [place], when there is one: it slides in, and "Got it" puts it
 * away for good and brings the next. A new thing's tip says where it is; an
 * everyday one can turn tips off altogether.
 */
@Composable
fun TipCard(place: TipPlace, modifier: Modifier = Modifier, viewModel: TipsViewModel = hiltViewModel()) {
    val flow = remember(place) { viewModel.tipFor(place) }
    val tip by flow.collectAsStateWithLifecycle(initialValue = null)
    // The last one stays drawn while it slides away.
    var last by remember { mutableStateOf<Tip?>(null) }
    LaunchedEffect(tip) { tip?.let { last = it } }
    AnimatedVisibility(
        visible = tip != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val shown = last ?: return@AnimatedVisibility
        // The caller's spacing goes on the card, so a page with no tip has no gap.
        TipCardContent(shown, onGotIt = { viewModel.gotIt(shown) }, onTipsOff = { viewModel.tipsOff(shown) }, modifier = modifier)
    }
}

/** How a tip looks: a new thing's in the app's own colour, with New beside its title. */
@Composable
fun TipCardContent(tip: Tip, onGotIt: () -> Unit, onTipsOff: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (tip.isNew) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)) {
            Icon(
                if (tip.isNew) Icons.Outlined.AutoAwesome else Icons.Outlined.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(22.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tip.isNew) {
                        NewMark()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(tip.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(tip.text, style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!tip.isNew) TextButton(onClick = onTipsOff) { Text("Turn tips off") }
                    TextButton(onClick = onGotIt) { Text("Got it") }
                }
            }
        }
    }
}

/** "New" beside something added lately, until its tip has been seen. */
@Composable
fun NewBadge(id: String, modifier: Modifier = Modifier, viewModel: TipsViewModel = hiltViewModel()) {
    val flow = remember(id) { viewModel.isNew(id) }
    val isNew by flow.collectAsStateWithLifecycle(initialValue = false)
    if (isNew) NewMark(modifier)
}

/** The New mark itself. */
@Composable
internal fun NewMark(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Text(
            "New",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
