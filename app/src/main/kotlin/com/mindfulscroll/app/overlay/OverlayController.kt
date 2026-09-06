package com.mindfulscroll.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mindfulscroll.app.R
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
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
 *
 * A successful addView() is NOT evidence the user saw anything. addView() only hands the view
 * to WindowManager; a window can be added and then never laid out, sized 0x0, or never
 * composed, and every counter we had would still read "overlay shown". Since this project has
 * already lost two rounds to failures that looked exactly like success, the controller watches
 * for the window's first actual draw and records it (size + latency) into [ServiceDiagnostics],
 * and if no frame arrives within [RENDER_WATCHDOG_MILLIS] it says so in as many words. See
 * OverlayRenderInstrumentedTest, which asserts on that evidence rather than on addView().
 */
@Singleton
class OverlayController @Inject constructor(
    private val diagnostics: ServiceDiagnostics,
) {

    /** Themed wrapper around the service, so Compose can resolve theme attributes. */
    private var overlayContext: Context? = null

    /** Deliberately obtained from the raw service context - that is what carries the token. */
    private var windowManager: WindowManager? = null

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null

    /** Set once the current overlay window has produced a real, non-zero-sized frame. */
    private var overlayRendered = false

    /** Why the last [show] call failed, or null if the last one worked. */
    var lastFailureReason: String? = null
        private set

    /**
     * What the last overlay window actually did after being added: its first-draw size and
     * latency, or the watchdog's complaint that it never drew. Null before the first attempt.
     * Read by tests and shown on the Diagnostics screen - this, not [lastFailureReason], is
     * what distinguishes "on screen" from "handed to WindowManager and lost".
     */
    var lastRenderDescription: String? = null
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
     * A true return means "WindowManager accepted the view"; whether it then rendered is
     * reported asynchronously through [lastRenderDescription] and ServiceDiagnostics.
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

        val addedAtMillis = System.currentTimeMillis()
        return runCatching { wm.addView(view, params) }
            .fold(
                onSuccess = {
                    lastFailureReason = null
                    lastRenderDescription = null
                    diagnostics.update {
                        it.copy(lastOverlayAddedAtMillis = addedAtMillis, lastOverlayRender = null)
                    }
                    watchForFirstDraw(view, addedAtMillis)
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

    /**
     * Records the window's first real frame - the only evidence that survives the failure mode
     * where addView() succeeds and nothing ever appears. A width/height of 0 is treated as
     * "not rendered yet" rather than a draw, because a 0x0 window draws exactly as invisibly as
     * a missing one.
     */
    private fun watchForFirstDraw(view: ComposeView, addedAtMillis: Long) {
        overlayRendered = false

        // Registration has to wait for attachment. Immediately after addView() the view is not
        // attached yet (ViewRootImpl.setView only schedules the traversal that attaches it), and
        // getViewTreeObserver() on a detached view hands back a throwaway "floating" observer
        // whose listeners are merged into the real one only for some listener types. Registering
        // on that would produce a watcher that silently never fires - the same shape of bug this
        // watcher exists to catch.
        val registerDrawListener = {
            val listener = ViewTreeObserver.OnDrawListener {
                val width = view.width
                val height = view.height
                // A 0x0 window draws exactly as invisibly as a missing one, so it doesn't count.
                if (!overlayRendered && width > 0 && height > 0) {
                    overlayRendered = true
                    recordRendered(width, height, System.currentTimeMillis() - addedAtMillis)
                }
                // Deliberately not self-removing: removeOnDrawListener() throws when called from
                // inside onDraw(), and every later call is a no-op thanks to the flag. hide()
                // detaches it.
            }
            drawListener = listener
            runCatching { view.viewTreeObserver.addOnDrawListener(listener) }
                .onFailure { error ->
                    drawListener = null
                    val reason = "could not observe the overlay's draws: " +
                        "${error.javaClass.simpleName}: ${error.message}"
                    lastRenderDescription = reason
                    diagnostics.update { it.copy(lastOverlayRender = reason) }
                    diagnostics.log("Overlay render watcher failed: $reason")
                    Log.e(TAG, "Overlay render watcher failed", error)
                }
        }

        if (view.isAttachedToWindow) {
            registerDrawListener()
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    registerDrawListener()
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }

        // The whole point of this class's history: if nothing draws, say so instead of leaving
        // "overlays shown: 1" standing as if the user saw something. Posted to the main looper
        // rather than to the view, so it fires even if the view never attaches at all.
        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (!overlayRendered && composeView === view) {
                    val complaint = "addView() succeeded but the window drew no frame within " +
                        "${RENDER_WATCHDOG_MILLIS}ms (view is ${view.width}x${view.height}, " +
                        "attached=${view.isAttachedToWindow}) - the overlay was almost certainly " +
                        "never visible to the user"
                    lastRenderDescription = complaint
                    diagnostics.update { it.copy(lastOverlayRender = complaint) }
                    diagnostics.log("Overlay NOT rendered: $complaint")
                    Log.e(TAG, "Overlay not rendered: $complaint")
                }
            },
            RENDER_WATCHDOG_MILLIS,
        )
    }

    private fun recordRendered(width: Int, height: Int, latencyMillis: Long) {
        val description = "${width}x$height, first frame ${latencyMillis}ms after addView()"
        lastRenderDescription = description
        diagnostics.update {
            it.copy(
                overlaysRenderedCount = it.overlaysRenderedCount + 1,
                lastOverlayRender = description,
            )
        }
        diagnostics.log("Overlay rendered: $description")
        Log.d(TAG, "Overlay rendered: $description")
    }

    fun hide() {
        val view = composeView ?: return
        drawListener?.let { listener ->
            runCatching { view.viewTreeObserver.removeOnDrawListener(listener) }
                .onFailure { Log.w(TAG, "Could not remove the overlay draw listener", it) }
        }
        drawListener = null
        overlayRendered = false
        // Wrapped because removeView() throws if the window is already gone (the service was
        // killed, the display changed), and reported because an overlay that fails to come down
        // leaves the user's screen blocked - a loud symptom with a silent cause otherwise.
        runCatching { windowManager?.removeView(view) }
            .onFailure { error ->
                diagnostics.log("Overlay window could not be removed: ${error.javaClass.simpleName}: ${error.message}")
                Log.w(TAG, "Failed to remove the overlay window", error)
            }
        lifecycleOwner?.onDestroy()
        composeView = null
        lifecycleOwner = null
    }

    private companion object {
        const val TAG = "MindfulScroll"

        /** How long a window gets to produce its first frame before we call it a failure. */
        const val RENDER_WATCHDOG_MILLIS = 2_000L
    }
}
