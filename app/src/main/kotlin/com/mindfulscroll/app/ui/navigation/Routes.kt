package com.mindfulscroll.app.ui.navigation

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
}
