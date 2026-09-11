package com.mindfulscroll.app.ui.dashboard

import androidx.compose.foundation.clickable
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
fun DashboardScreen(
    onOpenReflection: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
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
                    "Nothing recorded yet today in a monitored app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(state.today, key = { it.appLabel }) { app ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(app.appLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${app.foregroundMinutes} min · ${opensLabel(app.openCount)} · ${app.scrollCount} scrolls",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        item {
            Text("Past 7 days", style = MaterialTheme.typography.titleLarge)
        }
        // Time and opens as two charts, never one number: how long and how often move
        // independently, and a drop in one can hide a habit unchanged in the other (#28).
        item {
            ChartSection(
                title = "Minutes in monitored apps",
                bars = state.last7Days.map { it.label to it.foregroundMinutes },
            )
        }
        item {
            ChartSection(
                title = "Times opened",
                bars = state.last7Days.map { it.label to it.openCount },
                footnote = if (state.last7Days.any { it.openCount == null }) {
                    "– is a day from before opens were counted."
                } else {
                    null
                },
            )
        }
        item {
            ChartSection(
                title = "Scrolls",
                bars = state.last7Days.map { it.label to it.scrollCount },
                footnote = "Best-effort: many feeds report no scrolling at all.",
            )
        }

        item {
            Text("Pause screens", style = MaterialTheme.typography.titleLarge)
        }
        item {
            OverlayOutcomeCard(state.overlayOutcomes)
        }

        // Needs no permission and no setting: the weekly notification only points here.
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenReflection),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Weekly reflection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "What you said when opening each app, next to what you felt you got.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartSection(title: String, bars: List<Pair<String, Int?>>, footnote: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        SimpleBarChart(bars = bars, modifier = Modifier.padding(top = 8.dp))
        footnote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** "opened 1 time", "opened 5 times", or - for a day from before opens were counted - says so. */
internal fun opensLabel(openCount: Int?): String = when (openCount) {
    null -> "opens not counted today"
    1 -> "opened 1 time"
    else -> "opened $openCount times"
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
