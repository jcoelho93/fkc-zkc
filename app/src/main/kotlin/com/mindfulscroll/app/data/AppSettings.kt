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

    private companion object {
        const val KEY_INTENTION_CAPTURE = "intention_capture_enabled"

        /** On by default: the feature is the point of the app, and it is one tap to turn off. */
        const val DEFAULT_INTENTION_CAPTURE = true
    }
}
