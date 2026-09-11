package com.mindfulscroll.app.reflection

import android.content.Context
import android.os.Build
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.stats.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What flipping the weekly-prompt switch should do. Pure, so the opt-in rules are pinned by unit
 * tests rather than by whoever last edited the Settings screen:
 *
 * - The permission is only ever requested because the user just turned the prompt on. Nothing
 *   else in the app asks for it: not onboarding, not app start, not the weekly job.
 * - A denial leaves the prompt off and is the end of it. The switch is how to ask again, and the
 *   reflection itself needs no permission at all.
 */
internal object WeeklyPromptToggle {

    enum class Action { TURN_ON, TURN_OFF, REQUEST_PERMISSION }

    fun onSwitchChanged(wantOn: Boolean, sdkInt: Int, permissionGranted: Boolean): Action = when {
        !wantOn -> Action.TURN_OFF
        // Below API 33 notifications need no runtime permission.
        sdkInt < Build.VERSION_CODES.TIRAMISU -> Action.TURN_ON
        permissionGranted -> Action.TURN_ON
        else -> Action.REQUEST_PERMISSION
    }

    fun onPermissionResult(granted: Boolean): Action = if (granted) Action.TURN_ON else Action.TURN_OFF
}

/** The one place the setting and the weekly job change together, so they cannot disagree. */
@Singleton
class WeeklyReflectionPrompt @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val notifier: ReflectionNotifier,
) {
    fun setEnabled(enabled: Boolean) {
        appSettings.setWeeklyReflectionPromptEnabled(enabled)
        // Created as soon as the prompt is on, so the channel is there to adjust in Android's own
        // settings before the first notification ever arrives.
        if (enabled) notifier.ensureChannel()
        WorkScheduler.syncWeeklyReflection(context, enabled)
    }
}
