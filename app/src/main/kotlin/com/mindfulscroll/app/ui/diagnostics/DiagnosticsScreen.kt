package com.mindfulscroll.app.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import com.mindfulscroll.app.accessibility.AccessibilityPermissionChecker
import com.mindfulscroll.app.stats.UsageAccessChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var accessibilityGranted by remember {
        mutableStateOf(AccessibilityPermissionChecker.isScrollMonitorServiceEnabled(context))
    }
    var usageAccessGranted by remember {
        mutableStateOf(UsageAccessChecker.isUsageAccessGranted(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityGranted = AccessibilityPermissionChecker.isScrollMonitorServiceEnabled(context)
                usageAccessGranted = UsageAccessChecker.isUsageAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DiagnosticsCard(title = "Permissions") {
                    LabelValueRow("Accessibility service", if (accessibilityGranted) "Enabled" else "NOT enabled")
                    LabelValueRow("Usage access", if (usageAccessGranted) "Granted" else "NOT granted")
                }
            }

            item {
                DiagnosticsCard(title = "Service state") {
                    LabelValueRow(
                        "Connected",
                        if (state.isServiceConnected) "Yes (since ${state.serviceConnectedAtMillis?.let { timeFormat.format(Date(it)) }})" else "No",
                    )
                    LabelValueRow("Monitored packages", if (state.monitoredPackages.isEmpty()) "(none selected)" else state.monitoredPackages.joinToString())
                    LabelValueRow("Current foreground app", state.currentForegroundPackage ?: "(none)")
                    LabelValueRow("Resolved serviceInfo", state.resolvedServiceInfo ?: "(not connected yet)")
                }
            }

            item {
                DiagnosticsCard(title = "Event counters (since service last (re)started)") {
                    LabelValueRow("All events delivered (any type, any app)", state.totalEventCount.toString())
                    LabelValueRow("Raw TYPE_VIEW_SCROLLED (any app)", state.rawScrollEventCount.toString())
                    LabelValueRow("Raw TYPE_WINDOW_CONTENT_CHANGED (any app)", state.rawContentChangedEventCount.toString())
                    LabelValueRow("Scroll ticks counted", state.countedScrollTicks.toString())
                    LabelValueRow("Scheduled threshold checks fired", state.scheduledThresholdChecksFired.toString())
                    LabelValueRow("Overlay windows added", state.overlaysShownCount.toString())
                    LabelValueRow("Overlay windows actually drawn", state.overlaysRenderedCount.toString())
                    LabelValueRow("Last overlay render", state.lastOverlayRender ?: "(no overlay attempted yet)")
                    LabelValueRow("Last overlay error", state.lastOverlayError ?: "(none)")
                    Text(
                        "If both raw counters stay at 0 while you scroll a monitored app, the " +
                            "OS isn't delivering scroll-related accessibility events to us at all - " +
                            "check the accessibility service is really enabled above. If the raw " +
                            "counters climb but \"scroll ticks counted\" doesn't, the events aren't " +
                            "matching the foreground/monitored package - check that above too.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "\"Added\" and \"actually drawn\" are separate on purpose. Added only means " +
                            "WindowManager accepted the overlay; drawn means it put real pixels on " +
                            "screen. If added climbs while drawn stays behind, the pause screen is " +
                            "being created and never shown - \"last overlay render\" above says what " +
                            "the window did instead.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                DiagnosticsCard(title = "Intention prompt (on app open)") {
                    LabelValueRow("Prompt windows added", state.intentionPromptsShownCount.toString())
                    LabelValueRow("Prompt windows actually drawn", state.intentionPromptsRenderedCount.toString())
                    LabelValueRow("Prompts answered", state.intentionsAnsweredCount.toString())
                    LabelValueRow("Last prompt render", state.lastIntentionPromptRender ?: "(no prompt attempted yet)")
                    LabelValueRow("Last prompt error", state.lastIntentionPromptError ?: "(none)")
                    Text(
                        "Answered being far below drawn is not a fault - the prompt is meant to be " +
                            "ignorable, and \"opened it with nothing in mind\" is recorded too. Drawn " +
                            "staying below added is the number that means something is broken.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                DiagnosticsCard(title = "Active session") {
                    LabelValueRow("Package", state.activeSessionPackage ?: "(none)")
                    LabelValueRow("Scroll count", state.activeSessionScrollCount.toString())
                }
            }

            item {
                Text("Recent activity", style = MaterialTheme.typography.titleLarge)
            }
            if (state.recentLog.isEmpty()) {
                item { Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(state.recentLog) { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
