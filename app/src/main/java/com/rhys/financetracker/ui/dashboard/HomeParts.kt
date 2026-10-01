@file:OptIn(ExperimentalMaterial3Api::class)

package com.rhys.financetracker.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.South
import androidx.compose.material.icons.outlined.North
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme

/*
 * The home screen's own pieces, in the soft style it is drawn in: a greeting
 * with the person's picture, a tab per person, four pastel tiles and a list
 * of the month's figures.
 */

/** A round picture for a person: their initial on their colour. */
@Composable
internal fun Avatar(
    name: String?,
    colorHex: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = colorFromHex(colorHex).copy(alpha = 0.85f),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White)
            } else {
                Text(
                    text = name?.trim()?.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** The top of Home: menu, picture, "Hello Rhys", and the button to arrange the cards. */
@Composable
internal fun HomeHeader(
    state: DashboardState,
    onMenu: () -> Unit,
    onAvatar: () -> Unit,
    onCustomise: () -> Unit,
) {
    val person = state.people.firstOrNull { it.id == state.scope.personId }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Outlined.Menu, contentDescription = "Settings")
            }
            Spacer(Modifier.weight(1f))
            Avatar(
                name = person?.name,
                colorHex = person?.colorHex ?: "#93A7C1",
                icon = if (person == null) Icons.Outlined.Groups else null,
                modifier = Modifier.clickable(onClick = onAvatar),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hello ${person?.name?.substringBefore(' ') ?: "everyone"}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (person == null) "Your shared money" else "Welcome back!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCustomise) {
                Icon(Icons.Outlined.Tune, contentDescription = "Choose what Home shows")
            }
        }
    }
}

/**
 * One tab per person, then Shared.
 *
 * Only shown with two or more people: with one there is nothing to choose
 * between. The pencil beside Shared picks who it covers.
 */
@Composable
internal fun PersonTabs(
    individuals: List<PersonEntity>,
    scope: DashboardScope,
    onPerson: (Long) -> Unit,
    onShared: () -> Unit,
    onChooseShared: () -> Unit,
) {
    if (individuals.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        individuals.forEach { person ->
            TabPill(
                label = person.name.substringBefore(' '),
                selected = !scope.isShared && scope.personId == person.id,
                colorHex = person.colorHex,
                onClick = { onPerson(person.id) },
            )
        }
        TabPill(
            label = "Shared",
            selected = scope.isShared,
            colorHex = null,
            onClick = onShared,
        )
        if (scope.isShared) {
            IconButton(onClick = onChooseShared) {
                Icon(Icons.Outlined.Edit, contentDescription = "Choose who Shared covers")
            }
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, colorHex: String?, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) colors.tileBlue else MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (colorHex != null) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(colorFromHex(colorHex), CircleShape),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onTile else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Picks the people the Shared tab covers. */
@Composable
internal fun SharedPeopleDialog(
    individuals: List<PersonEntity>,
    selected: Set<Long>?,
    onSave: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(selected ?: individuals.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who does Shared cover?") },
        text = {
            Column {
                Text(
                    text = "Their money is added together on the Shared tab, along with " +
                        "anything held jointly. Everyone keeps their own tab either way.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                individuals.forEach { person ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chosen = if (person.id in chosen) chosen - person.id else chosen + person.id
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = person.id in chosen,
                            onCheckedChange = { on ->
                                chosen = if (on) chosen + person.id else chosen - person.id
                            },
                        )
                        Text(person.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(chosen); onDismiss() },
                enabled = chosen.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The four tiles: Available, Saved, Owed and take-home, in two staggered
 * columns — short then tall on the left, tall then short on the right.
 */
@Composable
internal fun HomeTiles(state: DashboardState, onOpenAccounts: () -> Unit) {
    val colors = FinanceTheme.colors
    val summary = state.summary
    val income = state.incomeInScope
    val owed = -summary.totalLiabilitiesMinor
    Column {
        // Looking back, the tiles hold that month's closing balances; saying so
        // stops them being read as today's.
        if (!state.isCurrentMonth) {
            Text(
                text = "Balances at the end of ${DateUtils.formatMonth(state.month)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeTile(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    value = Money.format(summary.totalBalanceMinor),
                    label = "Available",
                    color = colors.tileBlue,
                    height = SHORT_TILE,
                    onClick = onOpenAccounts,
                )
                HomeTile(
                    icon = Icons.Outlined.CreditCard,
                    value = Money.format(owed.coerceAtLeast(0L)),
                    label = if (owed > 0L) "Owed on loans and cards" else "Nothing owed",
                    color = colors.tileBlush,
                    height = TALL_TILE,
                    onClick = onOpenAccounts,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeTile(
                    icon = Icons.Outlined.Savings,
                    value = Money.format(summary.totalSavingsMinor),
                    label = when {
                        summary.savingsNetMinor > 0L ->
                            "Saved · ${Money.formatCompact(summary.savingsNetMinor)} put aside this month"
                        else -> "Saved"
                    },
                    color = colors.tileSage,
                    height = TALL_TILE,
                    onClick = onOpenAccounts,
                )
                val monthly = income.netMonthlyMinor
                HomeTile(
                    icon = Icons.Outlined.Payments,
                    value = Money.format(monthly ?: summary.monthIncomeMinor),
                    label = if (monthly != null) "Take-home a month" else "Money in this month",
                    color = colors.tileMist,
                    height = SHORT_TILE,
                    onClick = null,
                )
            }
        }
    }
}

/** Longer than this ("£12,345.67" and up) and a tile's figure drops a size. */
private const val TILE_FULL_SIZE_CHARS = 9

private val SHORT_TILE = 118.dp
private val TALL_TILE = 168.dp

@Composable
private fun HomeTile(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color,
    height: Dp,
    onClick: (() -> Unit)?,
) {
    val onTile = FinanceTheme.colors.onTile
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(20.dp),
        color = color,
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .border(1.5.dp, onTile.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = onTile, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            // Exact to the penny: a balance is the one figure people check
            // against their banking app. Long ones step down a size to fit.
            Text(
                text = value,
                style = if (value.length > TILE_FULL_SIZE_CHARS) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = onTile,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = onTile.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The month as a short list: money in, money out, put aside and what is
 * left — each with its own coloured square, like the tiles above.
 */
@Composable
internal fun MonthList(state: DashboardState) {
    val colors = FinanceTheme.colors
    val summary = state.summary
    val month = DateUtils.formatMonth(state.month)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (state.isCurrentMonth) "This month" else month,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        MonthRow(Icons.Outlined.South, "Money in", month, summary.monthIncomeMinor, colors.tileBlue)
        MonthRow(Icons.Outlined.North, "Money out", month, summary.monthExpenseMinor, colors.tileBlush)
        MonthRow(Icons.Outlined.Savings, "Put aside", month, summary.savingsNetMinor, colors.tileSage)
        if (summary.loanPaymentsMinor != 0L) {
            MonthRow(
                Icons.Outlined.CreditCard,
                "Paid off loans",
                month,
                summary.loanPaymentsMinor,
                colors.tileBlush,
            )
        }
        MonthRow(
            Icons.Outlined.AccountBalance,
            "Left to spend",
            "After the bills still to come",
            summary.disposableMinor,
            colors.tileMist,
        )
    }
}

@Composable
private fun MonthRow(icon: ImageVector, title: String, subtitle: String, amountMinor: Long, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(color, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = FinanceTheme.colors.onTile)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = Money.format(amountMinor),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (amountMinor < 0L) FinanceTheme.colors.negative else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Everything owed, in one place: credit cards, pay-later plans (PayPal Pay
 * in 3, Klarna…) and loans, each with what is left and how far through it is.
 *
 * A loan or pay-later plan leaves this list — and the app — the day it is
 * paid off. A card stays: £0 is its normal state.
 */
@Composable
internal fun LoansCard(state: DashboardState, onOpenAccounts: () -> Unit) {
    val owed = state.accounts.filter { it.isLiability }
    if (owed.isEmpty()) return
    val order = listOf(AccountType.CREDIT_CARD, AccountType.PAY_LATER, AccountType.LOAN, AccountType.MORTGAGE)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Cards, loans and pay later",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenAccounts) { Text("All") }
        }
        owed.sortedBy { order.indexOf(it.account.type).let { i -> if (i < 0) order.size else i } }
            .forEach { item ->
                val left = (-item.balanceMinor).coerceAtLeast(0L)
                val limit = item.account.creditLimitMinor?.takeIf { it > 0L }
                val isCard = item.account.type == AccountType.CREDIT_CARD
                val original = if (isCard) {
                    null
                } else {
                    limit ?: (-item.account.openingBalanceMinor).takeIf { it > 0L }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = FinanceTheme.colors.tileBlush.copy(alpha = 0.45f),
                    onClick = onOpenAccounts,
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.account.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = item.account.type.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = if (isCard) "${Money.format(left)} owed" else "${Money.format(left)} left",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        val total = if (isCard) limit else original
                        if (total != null && total >= left && total > 0L) {
                            val share = if (isCard) {
                                left.toFloat() / total
                            } else {
                                (total - left).toFloat() / total
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { share.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = FinanceTheme.colors.tileBlue,
                                trackColor = MaterialTheme.colorScheme.surface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (isCard) {
                                    "${(share * 100).toInt()}% of the ${Money.format(total)} limit used"
                                } else {
                                    "${(share * 100).toInt()}% paid off of ${Money.format(total)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
    }
}

/**
 * The first thing a new install shows: a welcome and one round button,
 * which starts setting up the first person.
 */
@Composable
internal fun WelcomeScreen(onStart: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .background(colors.tileSage, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Savings,
                contentDescription = null,
                tint = colors.onTile,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(36.dp))
        Text(
            text = "Welcome to your money",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Start with yourself: your name, your pay, your accounts and anything " +
                "you are paying off. Everyone else gets their own tab the same way.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Surface(
            shape = CircleShape,
            color = colors.tileBlue,
            onClick = onStart,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Start",
                    tint = colors.onTile,
                )
            }
        }
    }
}
