package com.mindfulscroll.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.grayscale.DaltonizerSettings

/**
 * Whether WRITE_SECURE_SETTINGS is held, re-checked every time the screen resumes: the grant
 * happens outside the app (over adb), so the usual way to see it take effect is to run the
 * command and come back.
 */
@Composable
internal fun rememberGrayscalePermissionGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(DaltonizerSettings.isPermissionGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = DaltonizerSettings.isPermissionGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/** "Grayscale" (#27): which monitored apps are shown in black and white while open. */
@Composable
fun GrayscaleSettingsScreen(
    onBack: () -> Unit,
    onChooseApps: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apps by viewModel.apps.collectAsState()
    val granted = rememberGrayscalePermissionGranted()
    val packageName = LocalContext.current.packageName
    SettingsSubpage(title = "Grayscale", onBack = onBack) {
        GrayscaleSettings(
            apps = apps,
            permissionGranted = granted,
            grantCommand = DaltonizerSettings.grantCommand(packageName),
            onGrayscaleChange = viewModel::setGrayscaleEnabled,
            onChooseApps = onChooseApps,
        )
    }
}

/**
 * Stateless half of [GrayscaleSettingsScreen].
 *
 * The setup card is the honest part. The permission can only be granted from a computer, so until
 * it is, the switches are saved but change nothing - and the page says so, rather than letting a
 * switch that does nothing look like one that works.
 */
@Composable
internal fun GrayscaleSettings(
    apps: List<MonitoredAppEntity>,
    permissionGranted: Boolean,
    grantCommand: String,
    onGrayscaleChange: (packageName: String, enabled: Boolean) -> Unit,
    onChooseApps: () -> Unit,
) {
    Text(
        "Shows an app in black and white while it's open. Colour comes back as soon as you leave it.",
        style = MaterialTheme.typography.bodyLarge,
    )

    if (!permissionGranted) {
        GrayscaleSetupCard(grantCommand)
    }

    Text(
        "It covers the whole screen while the app is open, including anything pulled down over it. " +
            "If colour correction is already on in Android's accessibility settings, it's left as it is.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
    )

    if (apps.isEmpty()) {
        Text("No apps are on your list yet.", style = MaterialTheme.typography.bodyMedium)
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
                    subtitle = if (app.isMonitored) null else "Not monitored right now",
                    value = null,
                    onClick = { onGrayscaleChange(app.packageName, !app.grayscaleEnabled) },
                    trailing = {
                        Switch(
                            checked = app.grayscaleEnabled,
                            onCheckedChange = { onGrayscaleChange(app.packageName, it) },
                        )
                    },
                )
                if (app != apps.last()) SettingsDivider()
            }
        }
    }
}

@Composable
private fun GrayscaleSetupCard(grantCommand: String) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("One-time setup, from a computer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android only lets an app turn grayscale on and off once it has been allowed from a " +
                    "computer with adb. Until then, the switches below are saved but have no effect.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            SelectionContainer(modifier = Modifier.padding(top = 12.dp)) {
                Text(grantCommand, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(grantCommand)) }) {
                Text("Copy command")
            }
            Text(
                "The permission is called WRITE_SECURE_SETTINGS. Mindful Scroll uses it only to " +
                    "switch Android's colour correction to grayscale and back.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
