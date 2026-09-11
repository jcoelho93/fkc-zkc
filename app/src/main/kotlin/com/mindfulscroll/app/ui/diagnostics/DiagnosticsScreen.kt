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
import com.mindfulscroll.app.stats.ScrollGestureCoalescer
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
    var resumeCount by remember { mutableStateOf(0) }
    val weeklyPromptEnabled by viewModel.weeklyPromptEnabled.collectAsState()
    var weeklyLastRun by remember { mutableStateOf(viewModel.weeklyLastRun()) }
    var weeklyBlockedReason by remember { mutableStateOf(viewModel.weeklyBlockedReason()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityGranted = AccessibilityPermissionChecker.isScrollMonitorServiceEnabled(context)
                usageAccessGranted = UsageAccessChecker.isUsageAccessGranted(context)
                resumeCount++
                weeklyLastRun = viewModel.weeklyLastRun()
                weeklyBlockedReason = viewModel.weeklyBlockedReason()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    // Re-read from the system whenever the service reports anything and on every resume, so what
    // this shows is the setting as it is now, not what the service last believed it wrote.
    val grayscale = remember(state, resumeCount) { viewModel.grayscaleSnapshot() }

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
                    LabelValueRow("Scrolls counted (one per swipe)", state.countedScrollTicks.toString())
                    LabelValueRow("  swipe opened by TYPE_VIEW_SCROLLED", state.countedScrollTicksViaViewScrolled.toString())
                    LabelValueRow("  swipe opened by TYPE_WINDOW_CONTENT_CHANGED", state.countedScrollTicksViaContentChanged.toString())
                    LabelValueRow("Events folded into a swipe already counted", state.scrollEventsFoldedIntoSwipe.toString())
                    LabelValueRow("Scheduled threshold checks fired", state.scheduledThresholdChecksFired.toString())
                    LabelValueRow("Keyboard windows ignored (not an app switch)", state.keyboardWindowEventsIgnored.toString())
                    LabelValueRow("Monitored-app opens counted", state.monitoredAppOpensCounted.toString())
                    LabelValueRow("Overlay windows added", state.overlaysShownCount.toString())
                    LabelValueRow("Overlay windows actually drawn", state.overlaysRenderedCount.toString())
                    LabelValueRow("Last overlay render", state.lastOverlayRender ?: "(no overlay attempted yet)")
                    LabelValueRow("Last overlay error", state.lastOverlayError ?: "(none)")
                    Text(
                        "If both raw counters stay at 0 while you scroll a monitored app, the " +
                            "OS isn't delivering scroll-related accessibility events to us at all - " +
                            "check the accessibility service is really enabled above. If the raw " +
                            "counters climb but neither \"scrolls counted\" nor \"events folded\" " +
                            "does, the events aren't matching the foreground/monitored package - " +
                            "check that above too. Events far outnumbering scrolls is normal: one " +
                            "swipe emits many, and it only counts once it follows " +
                            "${ScrollGestureCoalescer.IDLE_GAP_MILLIS} ms of stillness.",
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
                    LabelValueRow("Pause outcomes answered", state.pauseOutcomesAnsweredCount.toString())
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
                DiagnosticsCard(title = "Grayscale (colour correction)") {
                    LabelValueRow("WRITE_SECURE_SETTINGS", if (grayscale.permissionGranted) "Granted" else "NOT granted (grant over adb to use grayscale)")
                    LabelValueRow(
                        "Grayscale on screen right now (read from the system)",
                        when {
                            grayscale.system == null -> "(could not read the setting)"
                            grayscale.system.isGrayscale -> "Yes"
                            grayscale.system.enabled -> "No - colour correction is on in mode ${grayscale.system.mode ?: "unset"}, not grayscale"
                            else -> "No"
                        },
                    )
                    LabelValueRow(
                        "Applied by this app",
                        grayscale.appliedByApp?.let { "Yes (will restore mode ${it.previousMode ?: "unset"})" } ?: "No",
                    )
                    LabelValueRow("Applied / restored (read back)", "${state.grayscaleAppliedCount} / ${state.grayscaleRestoredCount}")
                    LabelValueRow("Last apply", state.lastGrayscaleApply ?: "(none yet)")
                    LabelValueRow("Last restore", state.lastGrayscaleRestore ?: "(none yet)")
                    LabelValueRow("Last skipped", state.lastGrayscaleSkip ?: "(none)")
                    LabelValueRow("Last grayscale error", state.lastGrayscaleError ?: "(none)")
                    Text(
                        "\"On screen right now\" is read from Android's settings, not from this app's own " +
                            "record. If it says Yes while no monitored app with grayscale is in front, the " +
                            "restore did not happen - \"last grayscale error\" says why. Colour correction " +
                            "turned on outside this app is never changed.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item {
                DiagnosticsCard(title = "Weekly reflection prompt") {
                    LabelValueRow("Prompt", if (weeklyPromptEnabled) "On" else "Off")
                    LabelValueRow("Notifications can be shown", weeklyBlockedReason?.let { "NO - $it" } ?: "Yes")
                    LabelValueRow(
                        "Last weekly run",
                        weeklyLastRun?.let { run ->
                            val at = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(run.atMillis))
                            "$at - ${run.result.description}" + (run.detail?.let { " ($it)" } ?: "")
                        } ?: "(not run yet)",
                    )
                    Text(
                        "Runs once a week while the prompt is on. \"Posted\" alone means Android " +
                            "accepted it; \"showing\" means it was in the notification shade " +
                            "straight afterwards.",
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
