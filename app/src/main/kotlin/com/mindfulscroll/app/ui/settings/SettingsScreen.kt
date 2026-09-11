package com.mindfulscroll.app.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.ui.navigation.Routes

/**
 * Settings as a menu of grouped rows, each showing its current value and opening a focused
 * subpage (#26). It used to be one flat list: global settings, bare navigation buttons and one
 * card per monitored app, all at the same weight. It grew with every app added, and there was no
 * way to tell which settings applied everywhere and which applied to one app.
 *
 * The only list that grows now is the per-app one, behind "When the pause appears", which is the
 * one place a long list is expected.
 *
 * [onOpenPage] navigates the OUTER nav host to one of the Routes.SETTINGS_* pages (or the app
 * picker), and [onOpenDiagnostics] the tab's own nav host, as before.
 */
@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit = {},
    onOpenPage: (route: String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsState()
    val intentionCaptureEnabled by viewModel.isIntentionCaptureEnabled.collectAsState()
    val pauseDurationSeconds by viewModel.pauseDurationSeconds.collectAsState()
    val grayscalePermissionGranted = rememberGrayscalePermissionGranted()
    val weeklyPromptEnabled by viewModel.isWeeklyReflectionPromptEnabled.collectAsState()

    SettingsMenu(
        apps = apps,
        intentionCaptureEnabled = intentionCaptureEnabled,
        pauseDurationSeconds = pauseDurationSeconds,
        grayscalePermissionGranted = grayscalePermissionGranted,
        weeklyReflectionPromptEnabled = weeklyPromptEnabled,
        onOpenPage = onOpenPage,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

/** Stateless, so it can be tested without Hilt. */
@Composable
internal fun SettingsMenu(
    apps: List<MonitoredAppEntity>,
    intentionCaptureEnabled: Boolean,
    pauseDurationSeconds: Int,
    grayscalePermissionGranted: Boolean,
    onOpenPage: (route: String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    weeklyReflectionPromptEnabled: Boolean = false,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        item {
            SettingsSection(title = "When you open an app") {
                SettingsRow(
                    title = "Ask what I'm looking for",
                    value = SettingsLabels.onOff(intentionCaptureEnabled),
                    onClick = { onOpenPage(Routes.SETTINGS_INTENTION) },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Grayscale",
                    value = SettingsLabels.grayscale(apps, grayscalePermissionGranted),
                    onClick = { onOpenPage(Routes.SETTINGS_GRAYSCALE) },
                )
            }
        }
        item {
            SettingsSection(title = "The pause") {
                SettingsRow(
                    title = "Pause length",
                    value = SettingsLabels.pauseLength(pauseDurationSeconds),
                    onClick = { onOpenPage(Routes.SETTINGS_PAUSE_LENGTH) },
                )
                SettingsDivider()
                SettingsRow(
                    title = "When the pause appears",
                    value = SettingsLabels.thresholdSummary(apps),
                    onClick = { onOpenPage(Routes.SETTINGS_THRESHOLDS) },
                )
            }
        }
        item {
            SettingsSection(title = "Reflection") {
                SettingsRow(
                    title = "Weekly reflection prompt",
                    value = SettingsLabels.onOff(weeklyReflectionPromptEnabled),
                    onClick = { onOpenPage(Routes.SETTINGS_WEEKLY_REFLECTION) },
                )
            }
        }
        item {
            SettingsSection(title = "Apps") {
                SettingsRow(
                    title = "Monitored apps",
                    value = SettingsLabels.monitoredApps(apps),
                    onClick = { onOpenPage(Routes.EDIT_MONITORED_APPS) },
                )
            }
        }
        item {
            SettingsSection(title = null) {
                SettingsRow(
                    title = "Diagnostics",
                    subtitle = "Is scroll detection actually working?",
                    value = null,
                    onClick = onOpenDiagnostics,
                )
            }
        }
    }
}
