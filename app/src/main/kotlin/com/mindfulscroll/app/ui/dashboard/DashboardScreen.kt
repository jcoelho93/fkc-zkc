package com.mindfulscroll.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Today", style = MaterialTheme.typography.titleLarge)
        }
        if (state.today.isEmpty()) {
            item {
                Text(
                    "No scrolling recorded yet today in a monitored app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(state.today, key = { it.appLabel }) { app ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(app.appLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${app.scrollCount} scrolls · ${app.foregroundMinutes} min",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item {
            Text("Past 7 days", style = MaterialTheme.typography.titleLarge)
        }
        item {
            SimpleBarChart(bars = state.last7Days.map { it.label to it.scrollCount })
        }

        item {
            Text("Pause screens", style = MaterialTheme.typography.titleLarge)
        }
        item {
            OverlayOutcomeCard(state.overlayOutcomes)
        }
    }
}

@Composable
private fun OverlayOutcomeCard(summary: OverlayOutcomeSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Shown ${summary.shown} time(s) in the past 7 days",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Closed the app: ${summary.closedApp}   ·   Chose 5 more minutes: ${summary.continued}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
