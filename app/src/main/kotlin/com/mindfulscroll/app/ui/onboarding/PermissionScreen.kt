package com.mindfulscroll.app.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mindfulscroll.app.accessibility.AccessibilityPermissionChecker
import com.mindfulscroll.app.stats.UsageAccessChecker

@Composable
fun PermissionScreen(onContinue: () -> Unit) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Two permissions, both on-device",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Both are required for Mindful Scroll to work. Neither one lets any data " +
                "leave your phone - this app has no network permission at all.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        PermissionCard(
            title = "Accessibility service",
            granted = accessibilityGranted,
            explanation = "Lets Mindful Scroll notice scroll gestures inside the apps you " +
                "choose to monitor, and show the pause screen. It never reads what's on " +
                "screen, and only watches apps you've explicitly selected. You'll land on " +
                "Android's Accessibility settings list - find \"Mindful Scroll\" and turn it on.",
            buttonLabel = "Open Accessibility settings",
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )

        Spacer(Modifier.height(16.dp))

        PermissionCard(
            title = "Usage access",
            granted = usageAccessGranted,
            explanation = "Lets Mindful Scroll read how long monitored apps have been in the " +
                "foreground, for your own daily/weekly stats. It cannot see which apps you " +
                "have installed beyond that, or anything about apps you haven't chosen to monitor.",
            buttonLabel = "Open Usage access settings",
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri()))
            },
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onContinue,
            enabled = accessibilityGranted && usageAccessGranted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    explanation: String,
    buttonLabel: String,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(text = explanation, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel)
                }
            }
        }
    }
}
