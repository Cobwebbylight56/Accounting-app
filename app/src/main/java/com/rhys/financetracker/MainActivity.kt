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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.rhys.financetracker.security.AppLockManager
import com.rhys.financetracker.security.BiometricAuthenticator
import com.rhys.financetracker.ui.intro.IntroScreen
import com.rhys.financetracker.ui.lock.LockScreen
import com.rhys.financetracker.ui.navigation.FinanceNavHost
import com.rhys.financetracker.ui.theme.FinanceTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

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
                                FinanceNavHost(
                                    onShareFile = ::shareFile,
                                    // Consumed once, so returning to the app later
                                    // does not re-open the same statement.
                                    importFile = pendingFile,
                                    onImportFileHandled = { incomingFile.value = null },
                                )
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
