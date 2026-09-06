package com.mindfulscroll.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide behaviour switches, as opposed to the per-app thresholds that live in Room.
 * SharedPreferences for the same reason OnboardingPreferences uses it: a couple of values with no
 * query needs.
 *
 * Exposed both as a StateFlow (for Compose) and as a plain synchronous getter, because the
 * accessibility service reads this on the hot path - the instant a monitored app comes forward -
 * and must not wait on a coroutine to decide whether to show a prompt that is supposed to feel
 * instant. Both views are backed by the same SharedPreferences, and the service's process is the
 * only writer, so they cannot disagree.
 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mindful_scroll_settings", Context.MODE_PRIVATE)

    private val _isIntentionCaptureEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_INTENTION_CAPTURE, DEFAULT_INTENTION_CAPTURE),
    )

    /**
     * Whether the "what are you hoping to find?" prompt appears when a monitored app opens.
     * Off means the mindful pause still happens - the two are deliberately separable, because the
     * prompt fires on every open and some people will find that intrusive even though they want
     * the pause.
     */
    val isIntentionCaptureEnabled: StateFlow<Boolean> = _isIntentionCaptureEnabled.asStateFlow()

    fun intentionCaptureEnabledNow(): Boolean =
        prefs.getBoolean(KEY_INTENTION_CAPTURE, DEFAULT_INTENTION_CAPTURE)

    fun setIntentionCaptureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INTENTION_CAPTURE, enabled) }
        _isIntentionCaptureEnabled.value = enabled
    }

    private val _pauseDurationSeconds = MutableStateFlow(
        prefs.getInt(KEY_PAUSE_DURATION_SECONDS, DEFAULT_PAUSE_DURATION_SECONDS),
    )

    /**
     * How long the urge-surfing part of the pause screen runs before the outcome question
     * appears. It never gates the exits - both are live from the first frame - so this is the
     * length of an invitation, not of a lock.
     */
    val pauseDurationSeconds: StateFlow<Int> = _pauseDurationSeconds.asStateFlow()

    /** Read on the hot path when the overlay is about to be shown - see the class doc. */
    fun pauseDurationSecondsNow(): Int =
        prefs.getInt(KEY_PAUSE_DURATION_SECONDS, DEFAULT_PAUSE_DURATION_SECONDS)
            .coerceIn(MIN_PAUSE_DURATION_SECONDS, MAX_PAUSE_DURATION_SECONDS)

    fun setPauseDurationSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_PAUSE_DURATION_SECONDS, MAX_PAUSE_DURATION_SECONDS)
        prefs.edit { putInt(KEY_PAUSE_DURATION_SECONDS, clamped) }
        _pauseDurationSeconds.value = clamped
    }

    companion object {
        private const val KEY_INTENTION_CAPTURE = "intention_capture_enabled"

        /** On by default: the feature is the point of the app, and it is one tap to turn off. */
        private const val DEFAULT_INTENTION_CAPTURE = true

        private const val KEY_PAUSE_DURATION_SECONDS = "pause_duration_seconds"

        /**
         * Twenty seconds, from the urge-surfing evidence the pause is built on: a craving observed
         * rather than acted on starts subsiding within a minute or two, and the point is to be
         * present for the start of that curve, not to sit out the whole of it. Long enough to
         * notice the urge; short enough that it does not itself become the friction this app
         * stopped using.
         *
         * Public because the Settings slider and the tests need the same bounds this class clamps
         * to - two copies of a range is how a slider ends up offering a value the setter rejects.
         */
        const val DEFAULT_PAUSE_DURATION_SECONDS = 20

        const val MIN_PAUSE_DURATION_SECONDS = 5
        const val MAX_PAUSE_DURATION_SECONDS = 60
    }
}
