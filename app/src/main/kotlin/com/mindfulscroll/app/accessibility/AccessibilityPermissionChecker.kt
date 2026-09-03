package com.mindfulscroll.app.accessibility

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * Whether the user has flipped Mindful Scroll's accessibility service on in system Settings.
 * There is no runtime-requestable permission for this - Android only lets us deep-link to the
 * Accessibility settings screen and then poll this check when the user returns to the app.
 */
object AccessibilityPermissionChecker {

    fun isScrollMonitorServiceEnabled(context: Context): Boolean {
        val expectedComponent = "${context.packageName}/${ScrollMonitorService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            if (component.equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }
}
