package com.rhys.financetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Every way money gets into the app, in one place: the same + on Home,
 * Spending and Payments opens the same sheet, instead of an add button
 * here, a scan in More and an import in three other places.
 */
data class AddActions(
    val payment: () -> Unit,
    val scan: () -> Unit,
    val import: () -> Unit,
    val move: () -> Unit,
)

/** The + button, and the sheet of ways to add that it opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddButton(actions: AddActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    FloatingActionButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Default.Add, contentDescription = "Add")
    }
    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(
                    "Add",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                fun go(action: () -> Unit) {
                    open = false
                    action()
                }
                AddRow(Icons.Outlined.EditNote, "A payment", "Type in something you spent or received") {
                    go(actions.payment)
                }
                AddRow(Icons.Outlined.PhotoCamera, "Scan a receipt or screenshot", "A till receipt, an online order, a list of payments") {
                    go(actions.scan)
                }
                AddRow(Icons.Outlined.Description, "Import a statement", "A PDF, CSV or Excel file from your bank, or a spreadsheet") {
                    go(actions.import)
                }
                AddRow(Icons.Outlined.SwapHoriz, "Move money", "Between your own accounts, or to or from a person") {
                    go(actions.move)
                }
            }
        }
    }
}

@Composable
private fun AddRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
