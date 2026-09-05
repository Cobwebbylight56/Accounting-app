package com.rhys.financetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.time.DateUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Reusable form controls.
 *
 * Every field here shows its error underneath rather than only colouring the
 * border, so the reason a form will not save is always readable — including by
 * a screen reader.
 */

/** A labelled text field with optional error text. */
@Composable
fun LabelledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        prefix = prefix?.let { { Text(it) } },
        supportingText = when {
            error != null -> {
                { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            supportingText != null -> {
                { Text(supportingText) }
            }
            else -> null
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
        ),
    )
}

/**
 * The money field.
 *
 * The keyboard is set to decimal and the currency symbol is shown as a prefix,
 * so the user types `24.99` rather than having to remember not to type "£".
 */
@Composable
fun AmountField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    currencySymbol: String = "£",
) {
    LabelledTextField(
        label = label,
        value = value,
        onValueChange = { input ->
            // Accept only what can be part of an amount, so the parser never
            // sees rubbish and the user cannot type a letter by accident.
            onValueChange(input.filter { it.isDigit() || it == '.' || it == ',' || it == '-' })
        },
        modifier = modifier,
        placeholder = "0.00",
        error = error,
        keyboardType = KeyboardType.Decimal,
        prefix = currencySymbol,
    )
}

/** A field that opens the Material date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    allowClear: Boolean = false,
    onClear: (() -> Unit)? = null,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = date?.let { DateUtils.format(it) } ?: "Not set",
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        trailingIcon = {
            Row {
                if (allowClear && date != null && onClear != null) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                Icon(Icons.Default.CalendarMonth, contentDescription = "Choose a date")
            }
        },
        modifier = modifier
            .fillMaxWidth()
            // The field itself is read-only, so the whole row is the target.
            .clickable { showPicker = true },
        enabled = false,
        colors = readOnlyFieldColors(),
    )

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: DateUtils.today())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            onDateChange(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showPicker = false
                    },
                ) { Text("Choose") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

/** A generic dropdown for choosing one item from a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    error: String? = null,
    /** Composable so callers can pass a theme-aware colour, e.g. `colorFromHex`. */
    optionColor: (@Composable (T) -> Color?)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Nothing to choose from yet") },
                    onClick = { expanded = false },
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            optionColor?.invoke(option)?.let {
                                ColorDot(it)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(optionLabel(option))
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Two or three mutually exclusive choices, e.g. Income / Expense / Transfer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(optionLabel(option), maxLines = 1)
            }
        }
    }
}

/** A horizontal row of filter chips, for multi-select filters. */
@Composable
fun <T> FilterChipRow(
    options: List<T>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(optionLabel(option)) },
                leadingIcon = if (option in selected) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

/** A grid of colour swatches, used when creating a category, person or goal. */
@Composable
fun ColorPicker(
    colors: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 48.dp),
            modifier = Modifier.fillMaxWidth().height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(colors) { hex ->
                val color = colorFromHex(hex)
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onSelect(hex) },
                    shape = CircleShape,
                    color = color,
                ) {
                    if (hex.equals(selected, ignoreCase = true)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Colours that keep a disabled read-only field looking like an enabled one, so
 * the date field does not appear greyed out just because it is not typable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun readOnlyFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    disabledTextColor = MaterialTheme.colorScheme.onSurface,
    disabledBorderColor = MaterialTheme.colorScheme.outline,
    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
