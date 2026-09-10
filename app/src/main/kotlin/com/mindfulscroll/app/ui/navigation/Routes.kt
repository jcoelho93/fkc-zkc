package com.mindfulscroll.app.ui.navigation

import android.net.Uri

object Routes {
    const val WELCOME = "welcome"
    const val PERMISSIONS = "permissions"
    const val APP_SELECTION = "app_selection"
    const val MAIN = "main"
    const val DASHBOARD = "main/dashboard"
    const val SETTINGS = "main/settings"
    const val DIAGNOSTICS = "main/diagnostics"

    /**
     * The app picker reached from Settings, as opposed to [APP_SELECTION], which is the
     * onboarding step. Same screen, different entry: this one has somewhere to go back to.
     */
    const val EDIT_MONITORED_APPS = "main/settings/apps"

    // Settings subpages (#26). Like EDIT_MONITORED_APPS, these are OUTER routes that pop back to
    // MAIN with the Settings tab still selected, not screens inside the tab.
    const val SETTINGS_INTENTION = "main/settings/intention"
    const val SETTINGS_PAUSE_LENGTH = "main/settings/pause-length"
    const val SETTINGS_THRESHOLDS = "main/settings/thresholds"

    const val ARG_PACKAGE_NAME = "packageName"
    const val SETTINGS_THRESHOLD_EDITOR = "main/settings/thresholds/{$ARG_PACKAGE_NAME}"

    fun thresholdEditor(packageName: String): String = "main/settings/thresholds/${Uri.encode(packageName)}"
}
