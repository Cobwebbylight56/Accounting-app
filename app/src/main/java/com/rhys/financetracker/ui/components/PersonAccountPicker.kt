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
import com.rhys.financetracker.data.local.projection.AccountOption

/**
 * Whose money, then which account: the people as tabs, and under them only
 * that person's accounts — the ones a payment comes out of (current accounts
 * and cards), with savings and loans a tap away. Choosing a person picks
 * their account to pay from straight away, so it's usually one tap.
 *
 * Where money goes to ([everyKind]) — a saver, a card being paid off — all
 * of that person's accounts are shown at once. Where an account is optional,
 * [noneLabel] offers none at all.
 */
/** The same, for the lighter account rows most forms hold. */
@Composable
@JvmName("PersonAccountOptionPicker")
fun PersonAccountPicker(
    label: String,
    people: List<PersonEntity>,
    accounts: List<AccountOption>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    everyKind: Boolean = false,
    noneLabel: String? = null,
    onNone: () -> Unit = {},
) {
    val asAccounts = remember(accounts) {
        accounts.map { option ->
            AccountEntity(
                id = option.id,
                name = option.name,
                type = option.type,
                personId = option.personId,
                colorHex = option.colorHex ?: "#888888",
                holding = option.holding,
                isShared = option.isShared,
            )
        }
    }
    PersonAccountPicker(label, people, asAccounts, selectedId, onSelect, modifier, everyKind, noneLabel, onNone)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonAccountPicker(
    label: String,
    people: List<PersonEntity>,
    accounts: List<AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    everyKind: Boolean = false,
    noneLabel: String? = null,
    onNone: () -> Unit = {},
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
    // And never hide the one already chosen, an ISA's own payment, say.
    val chosenHidden = selected != null && owners.keyOf(selected) == groupKey && !AccountOwners.paysFrom(selected)
    val shown = if (everyKind || showAll || paying.isEmpty() || chosenHidden) theirs else paying

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
            if (noneLabel != null) PillTab(label = noneLabel, selected = selected == null, onClick = onNone)
            shown.forEach { account ->
                PillTab(label = account.name, selected = account.id == selectedId, onClick = { onSelect(account.id) })
            }
        }
        if (!everyKind && !chosenHidden && paying.isNotEmpty() && theirs.size > paying.size) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Only accounts to pay from" else "Show savings and loans too")
            }
        }
    }
}
