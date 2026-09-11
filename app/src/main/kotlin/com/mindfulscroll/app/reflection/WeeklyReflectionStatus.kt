package com.mindfulscroll.app.reflection

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What one run of the weekly job did. Shown on the Diagnostics screen; never shown as a nudge. */
enum class WeeklyPromptResult(val description: String) {
    SKIPPED_PROMPT_OFF("not posted: the weekly prompt is off"),
    SKIPPED_NOTHING_RECORDED("not posted: nothing recorded in the past 7 days"),
    SKIPPED_BLOCKED("not posted: notifications are blocked"),
    POSTED_AND_SHOWING("posted, and showing in the notification shade"),
    POSTED_NOT_SHOWING("posted, but NOT showing afterwards"),
    ERROR("the job threw before it could decide"),
}

/**
 * The last weekly run, persisted. The job runs in whatever process WorkManager starts, usually
 * with no screen open, so an in-memory counter like ServiceDiagnostics' would be gone by the time
 * anyone looked. Without this, "the prompt never came" would be indistinguishable between prompt
 * off, nothing to show, notifications blocked, and a post the system dropped.
 */
@Singleton
class WeeklyReflectionStatus @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("weekly_reflection_status", Context.MODE_PRIVATE)

    data class LastRun(val atMillis: Long, val result: WeeklyPromptResult, val detail: String?)

    fun record(result: WeeklyPromptResult, detail: String?, atMillis: Long) {
        prefs.edit {
            putLong(KEY_AT, atMillis)
            putString(KEY_RESULT, result.name)
            putString(KEY_DETAIL, detail)
        }
    }

    fun lastRun(): LastRun? {
        val name = prefs.getString(KEY_RESULT, null) ?: return null
        val result = WeeklyPromptResult.entries.firstOrNull { it.name == name } ?: return null
        return LastRun(prefs.getLong(KEY_AT, 0L), result, prefs.getString(KEY_DETAIL, null))
    }

    private companion object {
        const val KEY_AT = "last_run_at_millis"
        const val KEY_RESULT = "last_run_result"
        const val KEY_DETAIL = "last_run_detail"
    }
}
