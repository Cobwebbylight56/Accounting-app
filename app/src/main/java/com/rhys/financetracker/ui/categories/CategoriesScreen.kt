package com.rhys.financetracker.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.ColorPicker
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SegmentedChoice
import com.rhys.financetracker.ui.components.colorFromHex

/**
 * Categories, grouped into parents and their children.
 *
 * Built-in categories can be renamed and recoloured but not deleted, because
 * reports and the spreadsheet importer fall back to them; archiving hides one
 * without breaking anything that refers to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onEditCategory: (Long) -> Unit,
    onAddCategory: () -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCategory) {
                Icon(Icons.Default.Add, contentDescription = "Add a category")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SegmentedChoice(
                options = CategoryKind.entries,
                selected = state.kind,
                onSelect = viewModel::setKind,
                optionLabel = { it.displayName },
                modifier = Modifier.padding(16.dp),
            )

            LazyColumn(
                contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.groups.forEach { group ->
                    item(key = "parent-${group.parent.id}") {
                        CategoryRow(
                            category = group.parent,
                            isChild = false,
                            onClick = { onEditCategory(group.parent.id) },
                            onDuplicate = { viewModel.duplicate(group.parent.id) },
                            onArchive = {
                                viewModel.archive(group.parent.id, !group.parent.isArchived)
                            },
                            onDelete = { pendingDelete = group.parent.id },
                        )
                    }
                    items(
                        count = group.children.size,
                        key = { index -> "child-${group.children[index].id}" },
                    ) { index ->
                        val child = group.children[index]
                        CategoryRow(
                            category = child,
                            isChild = true,
                            onClick = { onEditCategory(child.id) },
                            onDuplicate = { viewModel.duplicate(child.id) },
                            onArchive = { viewModel.archive(child.id, !child.isArchived) },
                            onDelete = { pendingDelete = child.id },
                        )
                    }
                }
            }
        }
    }

    if (pendingDelete != 0L) {
        ConfirmDialog(
            title = "Delete this category?",
            message = "Transactions that used it stay exactly as they are — they simply " +
                "become uncategorised, so no money disappears from your reports.",
            confirmLabel = "Delete",
            isDestructive = true,
            alternativeLabel = "Archive instead",
            onAlternative = { viewModel.archive(pendingDelete, true) },
            onConfirm = { viewModel.delete(pendingDelete) },
            onDismiss = { pendingDelete = 0L },
        )
    }
}

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    isChild: Boolean,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = if (isChild) 24.dp else 0.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(colorFromHex(category.colorHex), size = if (isChild) 10.dp else 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name + if (category.isArchived) " (archived)" else "",
                    style = if (isChild) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                )
                category.monthlyBudgetMinor?.let {
                    Text(
                        text = "Budget ${Money.format(it)} a month",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions")
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { onClick(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Duplicate") },
                onClick = { onDuplicate(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(if (category.isArchived) "Restore" else "Archive") },
                onClick = { onArchive(); showMenu = false },
            )
            if (!category.isSystem) {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { onDelete(); showMenu = false },
                )
            }
        }
    }
}

/** Add or edit one category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditScreen(
    onBack: () -> Unit,
    viewModel: CategoryEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.form.isSaved) { if (state.form.isSaved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New category" else "Edit category") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.form.errorSummary?.let {
                ErrorBanner(message = it, onDismiss = viewModel::clearError)
            }

            LabelledTextField(
                label = "Name",
                value = state.form.name,
                onValueChange = { text -> viewModel.update { it.copy(name = text) } },
                placeholder = "Electricity",
            )

            SegmentedChoice(
                options = CategoryKind.entries,
                selected = state.form.kind,
                onSelect = { kind -> viewModel.update { it.copy(kind = kind, parentId = null) } },
                optionLabel = { it.displayName },
            )

            DropdownField(
                label = "Inside",
                options = state.possibleParents,
                selected = state.possibleParents.firstOrNull { it.id == state.form.parentId },
                onSelect = { parent -> viewModel.update { it.copy(parentId = parent.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
                placeholder = "Top level",
            )

            AmountField(
                label = "Monthly budget (optional)",
                value = state.form.monthlyBudgetText,
                onValueChange = { text -> viewModel.update { it.copy(monthlyBudgetText = text) } },
            )

            ColorPicker(
                colors = DefaultData.PALETTE,
                selected = state.form.colorHex,
                onSelect = { hex -> viewModel.update { it.copy(colorHex = hex) } },
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.isNew) "Add category" else "Save changes")
            }
        }
    }
}
