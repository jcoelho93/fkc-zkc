package com.mindfulscroll.app.ui.reflection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.reflection.AppReflection
import com.mindfulscroll.app.reflection.WeeklyReflection
import com.mindfulscroll.app.ui.dashboard.SimpleBarChart

object ReflectionTags {
    const val CHART = "reflection-chart"
    const val EMPTY = "reflection-empty"
    const val PREVIOUS_WEEK = "reflection-previous-week"
    const val NEXT_WEEK = "reflection-next-week"
}

/**
 * The weekly reflection (#6): per app, what the user said when opening it, next to how the
 * pause's "did you get it?" went on those visits.
 *
 * It shows and does not interpret. One week of someone's own answers is not evidence of anything
 * (CLAUDE.md, the 60-90 day rule), so there is no verdict, no trend arrow and no "compared with
 * last week" - only counts, each with the number it is out of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReflectionScreen(onBack: () -> Unit, viewModel: ReflectionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weekly reflection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        ReflectionReport(
            state = state,
            onPreviousWeek = viewModel::previousWeek,
            onNextWeek = viewModel::nextWeek,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Stateless, so it can be tested without Hilt or a database. */
@Composable
internal fun ReflectionReport(
    state: ReflectionUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reflection = state.reflection
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            WeekSwitcher(state, onPreviousWeek, onNextWeek)
        }
        item {
            Text(
                "What you said when opening each app, next to how the pause's question went on " +
                    "those visits. Every time the question appeared is counted, answered or not.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when {
            reflection == null -> Unit
            reflection.isEmpty -> item { EmptyWeek(state.intentionCaptureEnabled) }
            else -> {
                item { AnswersChart(reflection) }
                items(reflection.apps, key = { it.packageName }) { app ->
                    AppCard(app, label = state.labelFor(app.packageName))
                }
                item {
                    Text(
                        "The pause asks \"did you get it?\" only on visits where you answered " +
                            "at opening.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekSwitcher(state: ReflectionUiState, onPreviousWeek: () -> Unit, onNextWeek: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onPreviousWeek,
            enabled = state.canGoBack,
            modifier = Modifier.testTag(ReflectionTags.PREVIOUS_WEEK),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier week")
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ReflectionCopy.weekHeading(state.weekOffset), style = MaterialTheme.typography.titleMedium)
            state.reflection?.let {
                Text(
                    ReflectionCopy.windowLabel(it.window),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        IconButton(
            onClick = onNextWeek,
            enabled = state.canGoForward,
            modifier = Modifier.testTag(ReflectionTags.NEXT_WEEK),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later week")
        }
    }
}

@Composable
private fun EmptyWeek(intentionCaptureEnabled: Boolean) {
    Column(modifier = Modifier.testTag(ReflectionTags.EMPTY)) {
        Text("Nothing recorded for these days.", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (intentionCaptureEnabled) {
                "This fills in as the question at opening and the pause come up."
            } else {
                "The question at opening is off in Settings, so there are no answers to set " +
                    "side by side."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * All apps together: how often each answer was given at opening, with the unanswered ones as a
 * bar of their own rather than left out. Only answers that were given get a bar, which also keeps
 * the labels readable.
 */
@Composable
private fun AnswersChart(reflection: WeeklyReflection) {
    val bars = IntentionKind.entries
        .map { ReflectionCopy.kindChartLabel(it) to reflection.saidAtOpening(it) }
        .filter { it.second > 0 } +
        listOfNotNull(
            (ReflectionCopy.NO_ANSWER to reflection.promptsUnanswered).takeIf { it.second > 0 },
        )
    Column(modifier = Modifier.testTag(ReflectionTags.CHART)) {
        Text("Answers at opening, all apps", style = MaterialTheme.typography.titleMedium)
        SimpleBarChart(
            bars = bars,
            labelStyle = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AppCard(app: AppReflection, label: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(ReflectionCopy.appSummary(app), style = MaterialTheme.typography.bodyMedium)

            app.byIntention.forEach { row ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text(ReflectionCopy.kindLabel(row.kind), style = MaterialTheme.typography.bodyLarge)
                Text(
                    ReflectionCopy.saidLine(row.saidAtOpening, app.promptsShown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(ReflectionCopy.pauseLine(row.atPause), style = MaterialTheme.typography.bodyMedium)
            }

            if (app.pausesAfterUnansweredPrompt > 0 || app.pausesWithoutPrompt > 0) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            }
            if (app.pausesAfterUnansweredPrompt > 0) {
                Text(
                    ReflectionCopy.pausesAfterUnansweredLine(app.pausesAfterUnansweredPrompt),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (app.pausesWithoutPrompt > 0) {
                Text(
                    ReflectionCopy.pausesWithoutPromptLine(app.pausesWithoutPrompt),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
