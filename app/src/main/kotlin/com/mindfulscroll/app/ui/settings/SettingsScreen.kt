package com.mindfulscroll.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mindfulscroll.app.data.entity.MonitoredAppEntity

@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit = {},
    onEditMonitoredApps: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsState()
    val intentionCaptureEnabled by viewModel.isIntentionCaptureEnabled.collectAsState()
    var editingApp by remember { mutableStateOf<MonitoredAppEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            IntentionCaptureCard(
                enabled = intentionCaptureEnabled,
                onEnabledChange = viewModel::setIntentionCaptureEnabled,
            )
        }
        item {
            TextButton(onClick = onOpenDiagnostics) {
                Text("Diagnostics: is scroll detection actually working?")
            }
        }
        item {
            TextButton(onClick = onEditMonitoredApps) {
                Text(if (apps.isEmpty()) "Choose apps to monitor" else "Add or remove apps")
            }
        }
        if (apps.isEmpty()) {
            item {
                Text(
                    "No apps are on your list yet, so nothing is being monitored. " +
                        "Use \"Choose apps to monitor\" above to add some.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(apps, key = { it.packageName }) { app ->
            AppSettingsRow(
                app = app,
                onMonitoredChange = { viewModel.setMonitored(app, it) },
                onEditClick = { editingApp = app },
            )
        }
    }

    editingApp?.let { app ->
        AppThresholdDialog(
            app = app,
            onDismiss = { editingApp = null },
            onSave = { scrollThreshold, timeMinutes ->
                viewModel.updateThresholds(app.packageName, scrollThreshold, timeMinutes)
                editingApp = null
            },
        )
    }
}

@Composable
private fun IntentionCaptureCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ask what I'm looking for", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A small prompt when you open a monitored app. It never blocks the app - " +
                            "you can ignore it and keep scrolling.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                "Turning this off keeps the pause screen; only the question at opening goes away.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AppSettingsRow(
    app: MonitoredAppEntity,
    onMonitoredChange: (Boolean) -> Unit,
    onEditClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appLabel, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${app.scrollThreshold} scrolls or ${app.timeThresholdMinutes} min",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = app.isMonitored, onCheckedChange = onMonitoredChange)
            }
            TextButton(onClick = onEditClick) {
                Text("Edit threshold")
            }
        }
    }
}

@Composable
private fun AppThresholdDialog(
    app: MonitoredAppEntity,
    onDismiss: () -> Unit,
    onSave: (scrollThreshold: Int, timeThresholdMinutes: Int) -> Unit,
) {
    var scrollText by remember { mutableStateOf(app.scrollThreshold.toString()) }
    var timeText by remember { mutableStateOf(app.timeThresholdMinutes.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.appLabel) },
        text = {
            Column {
                OutlinedTextField(
                    value = scrollText,
                    onValueChange = { scrollText = it.filter(Char::isDigit) },
                    label = { Text("Scroll threshold") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Text(
                    "Minutes below is continuous foreground time, not total time today.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it.filter(Char::isDigit) },
                    label = { Text("Time threshold (minutes)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val scroll = scrollText.toIntOrNull()?.coerceAtLeast(1) ?: app.scrollThreshold
                    val minutes = timeText.toIntOrNull()?.coerceAtLeast(1) ?: app.timeThresholdMinutes
                    onSave(scroll, minutes)
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
