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
import androidx.compose.material3.RadioButton
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
import com.mindfulscroll.app.data.entity.FrictionMode
import com.mindfulscroll.app.data.entity.MonitoredAppEntity

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsState()
    var editingApp by remember { mutableStateOf<MonitoredAppEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        if (apps.isEmpty()) {
            item {
                Text(
                    "No apps selected yet. Pick apps to monitor from onboarding, or reinstall " +
                        "to redo that step.",
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
            onSave = { scrollThreshold, timeMinutes, frictionMode ->
                viewModel.updateThresholds(app.packageName, scrollThreshold, timeMinutes)
                viewModel.updateFrictionMode(app.packageName, frictionMode)
                editingApp = null
            },
        )
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
                        "${app.scrollThreshold} scrolls or ${app.timeThresholdMinutes} min · " +
                            if (app.frictionMode == FrictionMode.COUNTDOWN) "10s countdown" else "typed phrase",
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
    onSave: (scrollThreshold: Int, timeThresholdMinutes: Int, frictionMode: FrictionMode) -> Unit,
) {
    var scrollText by remember { mutableStateOf(app.scrollThreshold.toString()) }
    var timeText by remember { mutableStateOf(app.timeThresholdMinutes.toString()) }
    var frictionMode by remember { mutableStateOf(app.frictionMode) }

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

                Text(
                    "\"5 more minutes\" friction",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                FrictionModeOption(
                    label = "10-second countdown",
                    selected = frictionMode == FrictionMode.COUNTDOWN,
                    onSelect = { frictionMode = FrictionMode.COUNTDOWN },
                )
                FrictionModeOption(
                    label = "Type a short phrase (stronger friction)",
                    selected = frictionMode == FrictionMode.TYPED_PHRASE,
                    onSelect = { frictionMode = FrictionMode.TYPED_PHRASE },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val scroll = scrollText.toIntOrNull()?.coerceAtLeast(1) ?: app.scrollThreshold
                    val minutes = timeText.toIntOrNull()?.coerceAtLeast(1) ?: app.timeThresholdMinutes
                    onSave(scroll, minutes, frictionMode)
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

@Composable
private fun FrictionModeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}
