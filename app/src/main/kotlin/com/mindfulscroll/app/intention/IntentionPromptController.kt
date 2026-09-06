package com.mindfulscroll.app.intention

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.overlay.AccessibilityWindow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "what are you hoping to find?" prompt shown when a monitored app comes to the foreground.
 *
 * Deliberately the opposite of the interruption overlay in every way that matters. That one is
 * full-screen, focusable, and exists to block; this one is a small strip at the bottom that must
 * never take a single touch or keystroke away from the app underneath. It fires on every open, so
 * the moment it costs the user anything it stops being a prompt and becomes friction - and this
 * app already has a place for friction.
 *
 * Hence FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL: taps outside the card go straight through to
 * the feed, and no keyboard is ever stolen. The one exception is the free-text path, which cannot
 * work without focus, so [requestTextEntry] drops NOT_FOCUSABLE on the live window - and only
 * after the user has explicitly asked to type.
 */
@Singleton
class IntentionPromptController @Inject constructor(
    private val diagnostics: ServiceDiagnostics,
) {
    private val window = AccessibilityWindow(
        label = "intention prompt",
        onAdded = { addedAtMillis ->
            diagnostics.update {
                it.copy(
                    intentionPromptsShownCount = it.intentionPromptsShownCount + 1,
                    lastIntentionPromptAddedAtMillis = addedAtMillis,
                    lastIntentionPromptRender = null,
                    lastIntentionPromptError = null,
                )
            }
        },
        onRendered = { description ->
            diagnostics.update {
                it.copy(
                    intentionPromptsRenderedCount = it.intentionPromptsRenderedCount + 1,
                    lastIntentionPromptRender = description,
                )
            }
        },
        onFailed = { reason ->
            diagnostics.update { it.copy(lastIntentionPromptError = reason) }
            diagnostics.log("Intention prompt problem: $reason")
        },
    )

    val lastFailureReason: String? get() = window.lastFailureReason

    fun attach(service: Context) = window.attach(service)

    fun detach() = window.detach()

    fun isShowing(): Boolean = window.isShowing()

    /**
     * @return true only if the window was added; rendering is reported through diagnostics.
     * Callbacks fire on the main thread and must not block - the caller does the Room work.
     */
    fun show(
        appLabel: String,
        onAnswer: (IntentionKind, String?) -> Unit,
        onDismiss: () -> Unit,
    ): Boolean = window.show(
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Bottom, because that is where a thumb already is mid-scroll, and because the top of
            // a feed is where the content the user came for actually is.
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        },
    ) {
        IntentionPromptScreen(
            appLabel = appLabel,
            onAnswer = onAnswer,
            onDismiss = onDismiss,
            onRequestTextEntry = ::requestTextEntry,
        )
    }

    /**
     * Makes the live prompt focusable so the IME can open. Only called when the user taps the one
     * chip that needs typing - until then the window is deliberately incapable of taking input.
     */
    private fun requestTextEntry() {
        window.updateParams { params ->
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
    }

    fun hide() = window.hide()
}
