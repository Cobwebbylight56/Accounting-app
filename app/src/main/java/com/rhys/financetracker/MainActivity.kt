package com.rhys.financetracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rhys.financetracker.data.export.ExportManager
import com.rhys.financetracker.data.export.ExportedFile
import com.rhys.financetracker.data.prefs.AppSettings
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.releases.ReleaseNotes
import com.rhys.financetracker.security.AppLockManager
import com.rhys.financetracker.security.BiometricAuthenticator
import com.rhys.financetracker.ui.intro.IntroScreen
import com.rhys.financetracker.ui.lock.LockScreen
import com.rhys.financetracker.ui.navigation.FinanceNavHost
import com.rhys.financetracker.ui.theme.FinanceTrackerTheme
import com.rhys.financetracker.ui.whatsnew.WhatsNewDialog
import com.rhys.financetracker.ui.whatsnew.currentVersion
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The only activity.
 *
 * It extends [FragmentActivity] rather than `ComponentActivity` because
 * `BiometricPrompt` needs a fragment host — that is the one thing the biometric
 * unlock cannot work without.
 *
 * Its jobs are:
 *  * apply the user's theme;
 *  * show the lock screen over everything when the app is locked;
 *  * host the navigation graph;
 *  * hand exported files to other apps.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var exportManager: ExportManager

    /**
     * A statement handed to the app from outside — "Open with" after a
     * download, or the share sheet.
     *
     * Held as state rather than read once, because [onNewIntent] can deliver
     * another one while the app is already open.
     */
    private val incomingFile = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        appLockManager.attach(lifecycleScope)
        incomingFile.value = fileFrom(intent)
        // Only once each time the app starts: not after rotating, not when
        // Android brings it back, not for a second window opened to import a
        // statement, and not when the app was opened with a statement at all
        // — that is someone wanting to get on with it.
        val freshStart = savedInstanceState == null && !introPlayed && incomingFile.value == null
        introPlayed = true

        setContent {
            val settings by settingsRepository.settings
                .collectAsState(initial = com.rhys.financetracker.data.prefs.AppSettings())
            val isLocked by appLockManager.isLocked.collectAsStateWithLifecycle()
            var introDone by rememberSaveable { mutableStateOf(!freshStart) }
            val reduceMotion = com.rhys.financetracker.ui.components.rememberReduceMotion()
            val introPlaying = !introDone && settings.showIntro && !reduceMotion
            // What changed since the version last used: shown once after an
            // update, after the intro and the lock, before anything else pops up.
            var whatsNew by remember { mutableStateOf<List<ReleaseNotes.Release>?>(null) }
            LaunchedEffect(Unit) { whatsNew = whatsNewToShow() }
            val whatsNewShowing = whatsNew?.isNotEmpty() == true

            FinanceTrackerTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.useDynamicColor,
            ) {
                // "Larger text" scales the whole UI rather than individual
                // labels, so nothing ends up mismatched.
                val density = LocalDensity.current
                val scaledDensity = if (settings.largeText) {
                    androidx.compose.ui.unit.Density(
                        density = density.density,
                        fontScale = density.fontScale * LARGE_TEXT_SCALE,
                    )
                } else {
                    density
                }

                CompositionLocalProvider(LocalDensity provides scaledDensity) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            if (isLocked) {
                                // Held back until the intro has played: the
                                // fingerprint prompt is the system's own window
                                // and would cover it. Nothing of the app's
                                // money is drawn underneath meanwhile.
                                if (!introPlaying) LockScreen(
                                    onUnlocked = { /* The lock manager drives this state. */ },
                                    onRequestBiometric = { onSuccess, onError ->
                                        BiometricAuthenticator.authenticate(
                                            activity = this@MainActivity,
                                            onSuccess = onSuccess,
                                            onFailure = onError,
                                        )
                                    },
                                )
                            } else {
                                val pendingFile by incomingFile.collectAsStateWithLifecycle()
                                CompositionLocalProvider(
                                    com.rhys.financetracker.ui.components.LocalIntroShowing provides
                                        (introPlaying || whatsNew == null || whatsNewShowing),
                                ) {
                                    FinanceNavHost(
                                        onShareFile = ::shareFile,
                                        // Consumed once, so returning to the app later
                                        // does not re-open the same statement.
                                        importFile = pendingFile,
                                        onImportFileHandled = { incomingFile.value = null },
                                    )
                                }
                                if (!introPlaying && whatsNewShowing) {
                                    WhatsNewDialog(
                                        releases = whatsNew.orEmpty(),
                                        onDismiss = {
                                            whatsNew = emptyList()
                                            lifecycleScope.launch { settingsRepository.setLastSeenVersion(currentVersion) }
                                        },
                                    )
                                }
                            }
                        }
                        // Drawn over everything, the lock screen included, and
                        // gone for good once it has played.
                        if (introPlaying) {
                            IntroScreen(onFinished = { introDone = true })
                        }
                    }
                }
            }
        }
    }

    /**
     * The notes to show for an update: every version since the one last
     * used. Nothing on a fresh install — there is nothing it changed — and
     * nothing once seen, which is then remembered.
     */
    private suspend fun whatsNewToShow(): List<ReleaseNotes.Release> {
        val seen = settingsRepository.lastSeenVersion()
        if (seen == currentVersion) return emptyList()
        val freshInstall = seen == null && runCatching {
            packageManager.getPackageInfo(packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
        }.getOrDefault(false)
        val notes = if (freshInstall) emptyList() else ReleaseNotes.since(seen, currentVersion)
        if (notes.isEmpty()) settingsRepository.setLastSeenVersion(currentVersion)
        return notes
    }

    /** A file arriving while the app is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fileFrom(intent)?.let { incomingFile.value = it }
    }

    /**
     * The file an incoming intent carries, if any.
     *
     * `VIEW` puts it in the data URI and `SEND` in an extra, and both reach
     * here because a statement can arrive either way — tapping a download uses
     * the first, sharing from a banking app the second.
     */
    private fun fileFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
            intent,
            Intent.EXTRA_STREAM,
            Uri::class.java,
        )
        else -> null
    }

    /** Opens the system share sheet for a file the app has just produced. */
    private fun shareFile(exported: ExportedFile) {
        val intent: Intent = exportManager.shareIntent(exported, exported.name)
        runCatching { startActivity(intent) }
    }

    private companion object {
        const val LARGE_TEXT_SCALE = 1.2f

        /** Set once the intro has had its chance this time the app is running. */
        var introPlayed = false
    }
}
