package com.mindfulscroll.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import kotlin.math.roundToInt

/**
 * "Ask what I'm looking for". The explanatory copy comes from the old Settings card unchanged.
 * Both lines prevent a specific misreading: that the prompt blocks the app, and that turning it
 * off turns off the pause.
 */
@Composable
fun IntentionCaptureSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.isIntentionCaptureEnabled.collectAsState()
    SettingsSubpage(title = "Ask what I'm looking for", onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "A small prompt when you open a monitored app. It never blocks the app - " +
                    "you can ignore it and keep scrolling.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
            Switch(checked = enabled, onCheckedChange = viewModel::setIntentionCaptureEnabled)
        }
        Text(
            "Turning this off keeps the pause screen; only the question at opening goes away.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * How long the pause screen's breathing phase runs before it asks whether you got what you came
 * for. A slider because there's no wrong answer to validate here, and no reason to make anyone
 * type a number.
 *
 * The copy says what this is NOT: it doesn't lock the screen. Someone reading "pause length"
 * would reasonably assume it's how long they're held there, which is exactly the countdown this
 * app removed, and they would then set it to the minimum for the wrong reason.
 */
@Composable
fun PauseLengthSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val seconds by viewModel.pauseDurationSeconds.collectAsState()
    SettingsSubpage(title = "Pause length", onBack = onBack) {
        Text(
            "$seconds seconds of breathing before the pause screen asks whether you got what " +
                "you came for.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Both buttons stay available the whole time - this never locks you out.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = seconds.toFloat(),
            onValueChange = { viewModel.setPauseDurationSeconds(it.toInt()) },
            valueRange = AppSettings.MIN_PAUSE_DURATION_SECONDS.toFloat()..
                AppSettings.MAX_PAUSE_DURATION_SECONDS.toFloat(),
            // One step per 5s: fine enough to tune, coarse enough to land on a round number.
            steps = (AppSettings.MAX_PAUSE_DURATION_SECONDS - AppSettings.MIN_PAUSE_DURATION_SECONDS) / 5 - 1,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * "When the pause appears": every monitored app with its threshold. This is the list that grows,
 * so it lives here instead of on the menu. Tapping an app opens its editor. The switch pauses
 * monitoring for that app without taking it off the list.
 */
@Composable
fun ThresholdListScreen(
    onBack: () -> Unit,
    onEditApp: (packageName: String) -> Unit,
    onChooseApps: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsState()
    SettingsSubpage(title = "When the pause appears", onBack = onBack) {
        ThresholdList(
            apps = apps,
            onEditApp = onEditApp,
            onMonitoredChange = viewModel::setMonitored,
            onChooseApps = onChooseApps,
        )
    }
}

/** Stateless half of [ThresholdListScreen]. */
@Composable
internal fun ThresholdList(
    apps: List<MonitoredAppEntity>,
    onEditApp: (packageName: String) -> Unit,
    onMonitoredChange: (MonitoredAppEntity, Boolean) -> Unit,
    onChooseApps: () -> Unit,
) {
    Text(
        "Each app pauses at whichever comes first: its scroll count or its minutes in a row.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    if (apps.isEmpty()) {
        Text(
            "No apps are on your list yet, so nothing is being monitored.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onChooseApps, modifier = Modifier.padding(top = 12.dp)) {
            Text("Choose apps to monitor")
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn {
            items(apps, key = { it.packageName }) { app ->
                SettingsRow(
                    title = app.appLabel,
                    subtitle = if (app.isMonitored) SettingsLabels.threshold(app) else "Not monitored right now",
                    value = null,
                    onClick = { onEditApp(app.packageName) },
                    trailing = {
                        Switch(checked = app.isMonitored, onCheckedChange = { onMonitoredChange(app, it) })
                    },
                )
                if (app != apps.last()) SettingsDivider()
            }
        }
    }
}

/**
 * One app's threshold, as two sliders. This replaces the old dialog with two raw number fields,
 * which asked for a number with no sense of what a reasonable one was, and made this setting a
 * different interaction from the pause length next to it.
 */
@Composable
fun ThresholdEditorScreen(
    packageName: String,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsState()
    val app = apps.firstOrNull { it.packageName == packageName }
    SettingsSubpage(title = app?.appLabel ?: "Threshold", onBack = onBack) {
        if (app == null) {
            // Briefly true while the list loads. Permanently true only if the app was just removed
            // from the list, in which case there's nothing left to edit.
            Text("This app isn't on your list.", style = MaterialTheme.typography.bodyMedium)
        } else {
            ThresholdEditor(
                app = app,
                onSave = { scrolls, minutes -> viewModel.updateThresholds(app.packageName, scrolls, minutes) },
            )
        }
    }
}

/**
 * Stateless half of [ThresholdEditorScreen]. Saves when a slider is released rather than on every
 * step of a drag, since every step would be a database write.
 */
@Composable
internal fun ThresholdEditor(app: MonitoredAppEntity, onSave: (scrollThreshold: Int, timeThresholdMinutes: Int) -> Unit) {
    var scrolls by remember(app.packageName) { mutableFloatStateOf(app.scrollThreshold.toFloat()) }
    var minutes by remember(app.packageName) { mutableFloatStateOf(app.timeThresholdMinutes.toFloat()) }
    fun save() = onSave(scrolls.roundToInt(), minutes.roundToInt())

    Text(
        "The pause appears at whichever comes first.",
        style = MaterialTheme.typography.bodyLarge,
    )

    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text("${scrolls.roundToInt()} scrolls", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = scrolls,
            onValueChange = { scrolls = ThresholdRanges.snapScrolls(it) },
            onValueChangeFinished = ::save,
            valueRange = ThresholdRanges.scrolls(app.scrollThreshold),
        )
        Text(
            "A scroll is one swipe. Many feeds report no scrolling at all, so the minutes below " +
                "are what reliably brings the pause.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text("${minutes.roundToInt()} minutes in a row", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = minutes,
            onValueChange = { minutes = it.roundToInt().toFloat() },
            onValueChangeFinished = ::save,
            valueRange = ThresholdRanges.minutes(app.timeThresholdMinutes),
        )
        Text(
            "Continuous time in the app, not total time today.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Slider ranges for the threshold editor: scrolls 5 to 200, minutes 1 to 60. A value set before
 * the sliders existed (the old dialog accepted any number) widens its range to fit, rather than
 * being silently clamped the first time the editor opens.
 *
 * Values are snapped in code rather than with Slider `steps`. Material 3 draws a tick mark per
 * step, and 39 or 59 of them turned the track into a dotted line.
 */
internal object ThresholdRanges {
    private const val MIN_SCROLLS = 5
    private const val MAX_SCROLLS = 200
    private const val SCROLL_STEP = 5
    private const val MIN_MINUTES = 1
    private const val MAX_MINUTES = 60

    fun scrolls(current: Int): ClosedFloatingPointRange<Float> =
        minOf(MIN_SCROLLS, current).toFloat()..maxOf(MAX_SCROLLS, current).toFloat()

    fun minutes(current: Int): ClosedFloatingPointRange<Float> =
        minOf(MIN_MINUTES, current).toFloat()..maxOf(MAX_MINUTES, current).toFloat()

    /** Nearest multiple of 5, never below the minimum: round numbers are easier to land on. */
    fun snapScrolls(value: Float): Float =
        maxOf(MIN_SCROLLS, (value / SCROLL_STEP).roundToInt() * SCROLL_STEP).toFloat()
}
