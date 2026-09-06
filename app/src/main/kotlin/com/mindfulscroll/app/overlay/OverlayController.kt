package com.mindfulscroll.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single full-screen interruption overlay - the pause screen. This window type, rather than
 * SYSTEM_ALERT_WINDOW, is what lets it appear without the separate "draw over other apps"
 * permission.
 *
 * All the window mechanics (and the reasons they are the way they are) live in
 * [AccessibilityWindow]; this class is only the interruption-specific parts: the layout params,
 * the content, and which diagnostics counters the results land in.
 */
@Singleton
class OverlayController @Inject constructor(
    private val diagnostics: ServiceDiagnostics,
) {
    private val window = AccessibilityWindow(
        label = "interruption overlay",
        // "Added" and "drawn" are counted in the same place on purpose: they are meant to be read
        // against each other on the Diagnostics screen, and two counters describing one window can
        // only be compared if one owner increments both.
        onAdded = { addedAtMillis ->
            diagnostics.update {
                it.copy(
                    overlaysShownCount = it.overlaysShownCount + 1,
                    lastOverlayAddedAtMillis = addedAtMillis,
                    lastOverlayRender = null,
                    lastOverlayError = null,
                )
            }
        },
        onRendered = { description ->
            diagnostics.update {
                it.copy(
                    overlaysRenderedCount = it.overlaysRenderedCount + 1,
                    lastOverlayRender = description,
                )
            }
            diagnostics.log("Overlay rendered: $description")
        },
        onFailed = { reason ->
            diagnostics.update { it.copy(lastOverlayError = reason) }
            diagnostics.log("Overlay problem: $reason")
        },
    )

    /** Why the last [show] failed, or null if the last one worked. */
    val lastFailureReason: String? get() = window.lastFailureReason

    /** What the last overlay window actually did after being added. */
    val lastRenderDescription: String? get() = window.lastRenderDescription

    /** Called by ScrollMonitorService from onServiceConnected. */
    fun attach(service: Context) = window.attach(service)

    /** Called by ScrollMonitorService from onDestroy - the token dies with the service. */
    fun detach() = window.detach()

    fun isShowing(): Boolean = window.isShowing()

    /**
     * @return true only if the window was actually added. The caller must not treat a false return
     * as "overlay is up" - see the isOverlayShowing handling in ScrollMonitorService.
     */
    fun show(
        state: OverlayUiState,
        onCloseApp: () -> Unit,
        onContinue: () -> Unit,
    ): Boolean = window.show(
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0, // No FLAG_NOT_FOCUSABLE: the overlay must intercept touches and fully block
            // interaction with the app underneath - that's the point of it. This is the opposite
            // of the intention prompt, which must never take input away from the app. It also
            // kept the keyboard reachable for the typed-phrase gate; that gate is gone (#3), but
            // the flag is unchanged because focusability is what blocks the app underneath, not
            // a detail of the removed content.
            PixelFormat.TRANSLUCENT,
        ),
    ) {
        InterruptionOverlayScreen(
            state = state,
            onCloseApp = onCloseApp,
            onContinue = onContinue,
        )
    }

    fun hide() = window.hide()
}
