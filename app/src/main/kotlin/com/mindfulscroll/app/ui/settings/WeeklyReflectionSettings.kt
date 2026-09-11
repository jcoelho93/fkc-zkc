package com.mindfulscroll.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mindfulscroll.app.reflection.WeeklyPromptToggle

object WeeklyReflectionSettingsTags {
    const val SWITCH = "weekly-prompt-switch"
}

/**
 * "Weekly reflection prompt" (#6, #33). The only place the app asks for POST_NOTIFICATIONS, and
 * only as the direct result of the user turning this on - see WeeklyPromptToggle for the rules.
 * A declined request leaves the switch off with a line saying why; nothing asks again unless the
 * switch is flipped again.
 */
@Composable
fun WeeklyReflectionSettingsScreen(
    onBack: () -> Unit,
    onOpenReflection: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.isWeeklyReflectionPromptEnabled.collectAsState()
    val context = LocalContext.current
    var permissionDeclined by rememberSaveable { mutableStateOf(false) }
    var notificationsBlocked by remember { mutableStateOf(!notificationsAllowed(context)) }

    // Re-read on return from Android's own notification settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsBlocked = !notificationsAllowed(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDeclined = !granted
        notificationsBlocked = !notificationsAllowed(context)
        viewModel.applyWeeklyPromptAction(WeeklyPromptToggle.onPermissionResult(granted))
    }

    SettingsSubpage(title = "Weekly reflection", onBack = onBack) {
        WeeklyReflectionSettings(
            enabled = enabled,
            permissionDeclined = permissionDeclined,
            notificationsBlocked = notificationsBlocked,
            onSwitchChanged = { wantOn ->
                val action = WeeklyPromptToggle.onSwitchChanged(
                    wantOn = wantOn,
                    sdkInt = Build.VERSION.SDK_INT,
                    permissionGranted = hasNotificationPermission(context),
                )
                if (action == WeeklyPromptToggle.Action.REQUEST_PERMISSION) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    permissionDeclined = false
                    viewModel.applyWeeklyPromptAction(action)
                }
            },
            onOpenReflection = onOpenReflection,
            onOpenNotificationSettings = { openNotificationSettings(context) },
        )
    }
}

/** Stateless half of [WeeklyReflectionSettingsScreen]. */
@Composable
internal fun WeeklyReflectionSettings(
    enabled: Boolean,
    permissionDeclined: Boolean,
    notificationsBlocked: Boolean,
    onSwitchChanged: (Boolean) -> Unit,
    onOpenReflection: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "One notification on Sunday evening, when the past 7 days have something in them. " +
                "No sound, no dot on the app icon, and nothing follows if you swipe it away.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
        Switch(
            checked = enabled,
            onCheckedChange = onSwitchChanged,
            modifier = Modifier.testTag(WeeklyReflectionSettingsTags.SWITCH),
        )
    }
    Text(
        "The reflection is on the Dashboard either way. This only decides whether a notification " +
            "mentions it.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 16.dp),
    )

    val note = when {
        permissionDeclined ->
            "Android didn't give Mindful Scroll notification access, so the prompt stays off."
        enabled && notificationsBlocked ->
            "Notifications from Mindful Scroll are off in Android settings, so the prompt can't arrive."
        else -> null
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        OutlinedButton(onClick = onOpenNotificationSettings, modifier = Modifier.padding(top = 8.dp)) {
            Text("Notification settings")
        }
    }

    OutlinedButton(onClick = onOpenReflection, modifier = Modifier.padding(top = 24.dp)) {
        Text("Open the reflection")
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun notificationsAllowed(context: Context): Boolean =
    hasNotificationPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}
