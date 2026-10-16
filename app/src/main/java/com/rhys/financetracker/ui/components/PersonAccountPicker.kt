package com.rhys.financetracker.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity

/**
 * Whose money, then which account: the people as tabs, and under them only
 * that person's accounts — the ones a payment comes out of (current accounts
 * and cards), with savings and loans a tap away. Choosing a person picks
 * their account to pay from straight away, so it's usually one tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonAccountPicker(
    label: String,
    people: List<PersonEntity>,
    accounts: List<AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val owners = remember(people, accounts) { AccountOwners.of(people, accounts) }
    if (owners.groups.isEmpty()) return
    val selected = accounts.firstOrNull { it.id == selectedId }
    var groupKey by remember(selectedId, owners) {
        mutableStateOf(selected?.let { owners.keyOf(it) } ?: owners.groups.first().key)
    }
    var showAll by remember { mutableStateOf(false) }
    val theirs = accounts.filter { owners.keyOf(it) == groupKey }
    val paying = theirs.filter { AccountOwners.paysFrom(it) }
    // Nothing to pay from: their savings are the only choice, so show them.
    val shown = if (showAll || paying.isEmpty()) theirs else paying

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (owners.groups.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                owners.groups.forEach { group ->
                    PillTab(
                        label = group.label,
                        selected = group.key == groupKey,
                        dot = group.colorHex?.let { colorFromHex(it) },
                        onClick = {
                            groupKey = group.key
                            // Their account to pay from, chosen for them.
                            val first = accounts.filter { owners.keyOf(it) == group.key }
                                .let { all -> all.firstOrNull { AccountOwners.paysFrom(it) } ?: all.firstOrNull() }
                            if (first != null && owners.keyOf(selected) != group.key) onSelect(first.id)
                        },
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { account ->
                PillTab(label = account.name, selected = account.id == selectedId, onClick = { onSelect(account.id) })
            }
        }
        if (paying.isNotEmpty() && theirs.size > paying.size) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Only accounts to pay from" else "Show savings and loans too")
            }
        }
    }
}
