package com.mindfulscroll.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mindfulscroll.app.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single full-screen TYPE_ACCESSIBILITY_OVERLAY window. This window type - rather
 * than SYSTEM_ALERT_WINDOW - is what lets us show the interruption screen without asking for
 * the separate "draw over other apps" permission.
 *
 * The context this uses MUST be the running AccessibilityService itself, not the application
 * context. The system hands the window token for this type to the accessibility service, so
 * addView() from the application context fails with BadTokenException ("permission denied for
 * this window type") every single time - which is what this class used to do, silently. That
 * is why the context arrives through [attach] from ScrollMonitorService rather than through
 * constructor injection.
 */
@Singleton
class OverlayController @Inject constructor() {

    /** Themed wrapper around the service, so Compose can resolve theme attributes. */
    private var overlayContext: Context? = null

    /** Deliberately obtained from the raw service context - that is what carries the token. */
    private var windowManager: WindowManager? = null

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    /** Why the last [show] call failed, for the Diagnostics screen. Null after a successful show. */
    var lastFailureReason: String? = null
        private set

    /** Called by ScrollMonitorService from onServiceConnected. */
    fun attach(service: Context) {
        overlayContext = ContextThemeWrapper(service, R.style.Theme_MindfulScroll)
        windowManager = service.getSystemService(WindowManager::class.java)
    }

    /** Called by ScrollMonitorService from onDestroy - the token dies with the service. */
    fun detach() {
        hide()
        overlayContext = null
        windowManager = null
    }

    fun isShowing(): Boolean = composeView != null

    /**
     * @return true only if the window was actually added. The caller must not treat a false
     * return as "overlay is up" - see the isOverlayShowing handling in ScrollMonitorService.
     */
    fun show(
        state: OverlayUiState,
        onCloseApp: () -> Unit,
        onContinue: () -> Unit,
    ): Boolean {
        hide() // Defensive: never stack two overlay windows.

        val context = overlayContext
        val wm = windowManager
        if (context == null || wm == null) {
            lastFailureReason = "no accessibility service attached (attach() was never called, " +
                "or the service was destroyed)"
            Log.e(TAG, "Cannot show overlay: $lastFailureReason")
            return false
        }

        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                InterruptionOverlayScreen(
                    state = state,
                    onCloseApp = onCloseApp,
                    onContinue = onContinue,
                )
            }
        }
        composeView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0, // No FLAG_NOT_FOCUSABLE: the overlay must be able to intercept touches
            // and keyboard input (needed for the typed-phrase friction mode) and must
            // fully block interaction with the app underneath - that's the point of it.
            PixelFormat.TRANSLUCENT,
        )

        return runCatching { wm.addView(view, params) }
            .fold(
                onSuccess = {
                    lastFailureReason = null
                    true
                },
                onFailure = { error ->
                    // Never swallow this. A silently-failing overlay is indistinguishable from a
                    // threshold that never fired, and the two have completely different fixes.
                    lastFailureReason = "${error.javaClass.simpleName}: ${error.message}"
                    Log.e(TAG, "Failed to add TYPE_ACCESSIBILITY_OVERLAY window", error)
                    composeView = null
                    lifecycleOwner?.onDestroy()
                    lifecycleOwner = null
                    false
                },
            )
    }

    fun hide() {
        val view = composeView ?: return
        runCatching { windowManager?.removeView(view) }
        lifecycleOwner?.onDestroy()
        composeView = null
        lifecycleOwner = null
    }

    private companion object {
        const val TAG = "MindfulScroll"
    }
}
