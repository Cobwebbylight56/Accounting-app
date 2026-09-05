package com.rhys.financetracker.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.ui.components.LabelledTextField

/**
 * The lock screen.
 *
 * It covers the whole app rather than being a destination, so there is no way
 * to navigate around it, and no financial figure is on screen behind it.
 *
 * When biometrics are enabled the prompt appears by itself as soon as the
 * screen opens — the common case should need no taps at all.
 */
@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    onRequestBiometric: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(state.isUnlocked) {
        if (state.isUnlocked) onUnlocked()
    }

    LaunchedEffect(state.allowsBiometric) {
        if (state.allowsBiometric) {
            onRequestBiometric(
                { viewModel.unlockWithBiometric() },
                { message -> viewModel.reportError(message) },
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(88.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Finance Tracker is locked",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            if (state.requiresPin) {
                LabelledTextField(
                    label = "PIN",
                    value = pin,
                    onValueChange = { text ->
                        pin = text.filter(Char::isDigit)
                        viewModel.clearError()
                    },
                    keyboardType = KeyboardType.NumberPassword,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.unlockWithPin(pin)
                        pin = ""
                    },
                    enabled = pin.length >= 4 && !state.isLockedOut,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("Unlock")
                }
            }

            if (state.allowsBiometric) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        onRequestBiometric(
                            { viewModel.unlockWithBiometric() },
                            { message -> viewModel.reportError(message) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Use fingerprint or face")
                }
            }
        }
    }
}
