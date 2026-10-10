package com.rhys.financetracker.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.BuildConfig
import com.rhys.financetracker.data.releases.ReleaseNotes
import java.time.format.DateTimeFormatter

/** This build's version, as release notes name it: "1.52.0", without "(build 123)" or "-debug". */
val currentVersion: String get() = ReleaseNotes.versionOf(BuildConfig.VERSION_NAME)

/**
 * Shown once after an update: the version now on the phone and what changed
 * since the one last used. Anything older than the last few is a tap away in
 * Settings → What's new.
 */
@Composable
fun WhatsNewDialog(releases: List<ReleaseNotes.Release>, onDismiss: () -> Unit) {
    val shown = releases.take(SHOWN_IN_DIALOG)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.NewReleases, contentDescription = null) },
        title = { Text("Updated to $currentVersion") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "What's new",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                shown.forEach { release -> ReleaseBlock(release, showVersion = shown.size > 1) }
                if (releases.size > shown.size) {
                    Text(
                        "And ${releases.size - shown.size} more ${if (releases.size - shown.size == 1) "update" else "updates"} " +
                            "— see Settings → What's new.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

/** Every version's notes, newest first, with the one on the phone marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What's new") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "You have Finance Tracker $currentVersion.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ReleaseNotes.ALL, key = { it.version }) { release ->
                val isThis = release.version == currentVersion
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isThis) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        ReleaseBlock(release, showVersion = true, thisOne = isThis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseBlock(release: ReleaseNotes.Release, showVersion: Boolean, thisOne: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showVersion) {
            Text(
                "${release.version} · ${DATE.format(release.date)}" + if (thisOne) " · on this phone" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(release.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        release.changes.forEach { change ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text(change, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** How many updates' notes the pop-up shows before pointing to the full list. */
private const val SHOWN_IN_DIALOG = 3
