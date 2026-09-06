package com.mindfulscroll.app.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mindfulscroll.app.R

/**
 * One TYPE_ACCESSIBILITY_OVERLAY window: adding it, composing into it, proving it drew, and taking
 * it down again. Not a singleton - each window that needs to exist owns an instance.
 *
 * Extracted rather than copied. Every hard-won detail in here was paid for by a bug that produced
 * no error at all, and a second copy would have to relearn all of them:
 *
 *  - The context MUST be the running AccessibilityService. The system hands the window token for
 *    this type to the service, so addView() from the application context fails with
 *    BadTokenException every single time. That is why the context arrives through [attach].
 *  - addView() succeeding is not evidence anything appeared. It returns long before the window is
 *    laid out, sized or composed, so a window can be accepted and then never drawn while every
 *    counter reads like success. Hence the first-draw watcher and the watchdog.
 *  - The draw listener can only be registered once the view is attached. Immediately after
 *    addView() it is not, and getViewTreeObserver() on a detached view returns a throwaway
 *    observer - registering there yields a watcher that silently never fires, which is precisely
 *    the failure this watcher exists to catch.
 *
 * Callers receive what happened through [onAdded]/[onRendered]/[onFailed] rather than this class
 * writing diagnostics itself, because each window's counters mean different things and merging
 * them would make both unreadable.
 */
class AccessibilityWindow(
    private val label: String,
    private val onAdded: (addedAtMillis: Long) -> Unit = {},
    private val onRendered: (description: String) -> Unit = {},
    private val onFailed: (reason: String) -> Unit = {},
) {

    /** Themed wrapper around the service, so Compose can resolve theme attributes. */
    private var overlayContext: Context? = null

    /** Deliberately obtained from the raw service context - that is what carries the token. */
    private var windowManager: WindowManager? = null

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null
    private var rendered = false

    /** Why the last [show] failed, or null if it worked. */
    var lastFailureReason: String? = null
        private set

    /** What the last window did after being added: first-draw size and latency, or the watchdog's complaint. */
    var lastRenderDescription: String? = null
        private set

    fun attach(service: Context) {
        overlayContext = ContextThemeWrapper(service, R.style.Theme_MindfulScroll)
        windowManager = service.getSystemService(WindowManager::class.java)
    }

    fun detach() {
        hide()
        overlayContext = null
        windowManager = null
    }

    fun isShowing(): Boolean = composeView != null

    /**
     * @return true only if WindowManager accepted the view. Whether it then rendered is reported
     * asynchronously through [onRendered] - callers must not read a true return as "on screen".
     */
    fun show(params: WindowManager.LayoutParams, content: @Composable () -> Unit): Boolean {
        hide() // Defensive: never stack two windows for the same slot.

        val context = overlayContext
        val wm = windowManager
        if (context == null || wm == null) {
            lastFailureReason = "no accessibility service attached (attach() was never called, " +
                "or the service was destroyed)"
            Log.e(TAG, "Cannot show $label: $lastFailureReason")
            onFailed(lastFailureReason!!)
            return false
        }

        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { content() }
        }
        composeView = view

        val addedAtMillis = System.currentTimeMillis()
        return runCatching { wm.addView(view, params) }
            .fold(
                onSuccess = {
                    lastFailureReason = null
                    lastRenderDescription = null
                    onAdded(addedAtMillis)
                    watchForFirstDraw(view, addedAtMillis)
                    true
                },
                onFailure = { error ->
                    // Never swallow this. A silently-failing window is indistinguishable from a
                    // trigger that never fired, and the two have completely different fixes.
                    lastFailureReason = "${error.javaClass.simpleName}: ${error.message}"
                    Log.e(TAG, "Failed to add $label as TYPE_ACCESSIBILITY_OVERLAY", error)
                    onFailed(lastFailureReason!!)
                    composeView = null
                    lifecycleOwner?.onDestroy()
                    lifecycleOwner = null
                    false
                },
            )
    }

    /**
     * Applies [transform] to the live window's layout params. Used to make a deliberately
     * non-focusable window focusable once the user asks to type into it - see
     * IntentionPromptController.
     */
    fun updateParams(transform: (WindowManager.LayoutParams) -> Unit) {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        transform(params)
        runCatching { windowManager?.updateViewLayout(view, params) }
            .onFailure { Log.e(TAG, "Could not update $label window params", it) }
    }

    private fun watchForFirstDraw(view: ComposeView, addedAtMillis: Long) {
        rendered = false

        val registerDrawListener = {
            val listener = ViewTreeObserver.OnDrawListener {
                val width = view.width
                val height = view.height
                // A 0x0 window draws exactly as invisibly as a missing one, so it doesn't count.
                if (!rendered && width > 0 && height > 0) {
                    rendered = true
                    val description = "${width}x$height, first frame " +
                        "${System.currentTimeMillis() - addedAtMillis}ms after addView()"
                    lastRenderDescription = description
                    onRendered(description)
                    Log.d(TAG, "$label rendered: $description")
                }
                // Deliberately not self-removing: removeOnDrawListener() throws when called from
                // inside onDraw(), and later calls are a no-op thanks to the flag. hide() detaches it.
            }
            drawListener = listener
            runCatching { view.viewTreeObserver.addOnDrawListener(listener) }
                .onFailure { error ->
                    drawListener = null
                    val reason = "could not observe $label's draws: " +
                        "${error.javaClass.simpleName}: ${error.message}"
                    lastRenderDescription = reason
                    onFailed(reason)
                    Log.e(TAG, "Render watcher failed for $label", error)
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

        // If nothing draws, say so, instead of leaving a "shown" count standing as if the user saw
        // something. Posted to the main looper rather than to the view, so it fires even if the
        // view never attaches at all.
        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (!rendered && composeView === view) {
                    val complaint = "addView() succeeded but $label drew no frame within " +
                        "${RENDER_WATCHDOG_MILLIS}ms (view is ${view.width}x${view.height}, " +
                        "attached=${view.isAttachedToWindow}) - it was almost certainly never " +
                        "visible to the user"
                    lastRenderDescription = complaint
                    onFailed(complaint)
                    Log.e(TAG, "Not rendered: $complaint")
                }
            },
            RENDER_WATCHDOG_MILLIS,
        )
    }

    fun hide() {
        val view = composeView ?: return
        drawListener?.let { listener ->
            runCatching { view.viewTreeObserver.removeOnDrawListener(listener) }
                .onFailure { Log.w(TAG, "Could not remove the $label draw listener", it) }
        }
        drawListener = null
        rendered = false
        // Wrapped because removeView() throws if the window is already gone (service killed,
        // display changed), and reported because a window that fails to come down can leave the
        // user's screen blocked - a loud symptom with an otherwise silent cause.
        runCatching { windowManager?.removeView(view) }
            .onFailure { error ->
                onFailed("$label window could not be removed: ${error.javaClass.simpleName}: ${error.message}")
                Log.w(TAG, "Failed to remove the $label window", error)
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
