package com.mindfulscroll.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A handful of onboarding booleans. Plain SharedPreferences is deliberately used instead of
 * DataStore or Room here - it's the right-sized tool for a few flags with no query needs.
 */
@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mindful_scroll_onboarding", Context.MODE_PRIVATE)

    var hasCompletedAppSelection: Boolean
        get() = prefs.getBoolean(KEY_APP_SELECTION_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_APP_SELECTION_DONE, value) }

    var hasSeenPermissionIntro: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_INTRO_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_PERMISSION_INTRO_SEEN, value) }

    private companion object {
        const val KEY_APP_SELECTION_DONE = "app_selection_done"
        const val KEY_PERMISSION_INTRO_SEEN = "permission_intro_seen"
    }
}
