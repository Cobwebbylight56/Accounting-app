package com.rhys.financetracker.ui.subscriptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rhys.financetracker.ui.recurring.RecurringScreen

/**
 * Bills and subscriptions in one place, rather than a Subscriptions page, a
 * Regular payments page and bills found in statements on a third: what the
 * statements show is being paid, and what you have set up yourself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    onBack: () -> Unit,
    onOpenLedger: () -> Unit,
    onEditRule: (Long) -> Unit,
    onAddRule: () -> Unit,
    /** 0 for what the statements show, 1 for what is set up. */
    startTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Bills & subscriptions") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                SecondaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("In your statements") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Set up by you") })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 0) {
                SubscriptionsScreen(onBack = onBack, onOpenLedger = onOpenLedger, embedded = true)
            } else {
                RecurringScreen(onBack = onBack, onEditRule = onEditRule, onAddRule = onAddRule, embedded = true)
            }
        }
    }
}
