package com.rhys.financetracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.data.local.entity.CategoryEntity

/**
 * Choosing a category: the field shows "Food › Takeaways", and tapping it
 * opens the groups — Food, Transport, Shopping — each opening up to what's
 * inside, with a search across the lot. A group can be chosen on its own too
 * ("Just Food") when nothing inside it fits better.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPicker(
    label: String,
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (CategoryEntity?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Not categorised",
    /** A first choice of no category at all, when there is one to offer. */
    noneLabel: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let { CategoryTree.labelOf(categories, it) } ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = selected?.let { { ColorDot(colorFromHex(it.colorHex)) } },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Over the field, so a tap anywhere on it opens the list rather than a keyboard.
        Box(Modifier.matchParentSize().clickable { open = true })
    }

    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet) {
            CategoryGroups(
                title = label,
                categories = categories,
                selectedId = selectedId,
                noneLabel = noneLabel,
                onSelect = {
                    onSelect(it)
                    open = false
                },
            )
        }
    }
}

@Composable
private fun CategoryGroups(
    title: String,
    categories: List<CategoryEntity>,
    selectedId: Long?,
    noneLabel: String?,
    onSelect: (CategoryEntity?) -> Unit,
) {
    val branches = remember(categories) { CategoryTree.of(categories) }
    var query by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf(setOfNotNull(CategoryTree.branchOf(branches, selectedId)?.head?.id)) }
    val shown = CategoryTree.search(branches, query)
    val searching = query.isNotBlank()

    Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search, e.g. takeaway or parking") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (noneLabel != null && !searching) {
                ChoiceRow(name = noneLabel, color = null, selected = selectedId == null, indent = false) { onSelect(null) }
            }
            if (shown.isEmpty()) {
                Text(
                    "Nothing called that. Add it in More → Categories.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            shown.forEach { branch ->
                val head = branch.head
                if (branch.children.isEmpty()) {
                    ChoiceRow(head.name, colorFromHex(head.colorHex), head.id == selectedId, indent = false) { onSelect(head) }
                    return@forEach
                }
                val isOpen = searching || head.id in opened
                GroupRow(
                    branch = branch,
                    isOpen = isOpen,
                    holdsSelected = head.id == selectedId || branch.children.any { it.id == selectedId },
                    onToggle = { opened = if (head.id in opened) opened - head.id else opened + head.id },
                )
                AnimatedVisibility(
                    visible = isOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        ChoiceRow("Just ${head.name}", colorFromHex(head.colorHex), head.id == selectedId, indent = true) {
                            onSelect(head)
                        }
                        branch.children.forEach { child ->
                            ChoiceRow(child.name, colorFromHex(child.colorHex), child.id == selectedId, indent = true) {
                                onSelect(child)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A group's heading: tap to open it up, or close it. */
@Composable
private fun GroupRow(branch: CategoryBranch, isOpen: Boolean, holdsSelected: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (isOpen) 180f else 0f, label = "category group")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(colorFromHex(branch.head.colorHex), size = 14.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                branch.head.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (holdsSelected) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                branch.children.take(PREVIEWED).joinToString(", ") { it.name } +
                    if (branch.children.size > PREVIEWED) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = if (isOpen) "Close ${branch.head.name}" else "Open ${branch.head.name}",
            modifier = Modifier.rotate(turn),
        )
    }
}

@Composable
private fun ChoiceRow(
    name: String,
    color: androidx.compose.ui.graphics.Color?,
    selected: Boolean,
    indent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 52.dp else 24.dp, end = 24.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            ColorDot(color, size = if (indent) 10.dp else 14.dp)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Chosen",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** How many of a group's categories its heading names. */
private const val PREVIEWED = 4
