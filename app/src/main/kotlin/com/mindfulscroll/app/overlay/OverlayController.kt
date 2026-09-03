package com.mindfulscroll.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single full-screen TYPE_ACCESSIBILITY_OVERLAY window. This window type - rather
 * than SYSTEM_ALERT_WINDOW - is what lets us show the interruption screen without asking for
 * the separate "draw over other apps" permission: any window added with this type by a process
 * that owns a currently-enabled AccessibilityService is allowed by the system.
 */
@Singleton
class OverlayController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val windowManager: WindowManager by lazy {
        context.getSystemService(WindowManager::class.java)
    }

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    fun isShowing(): Boolean = composeView != null

    fun show(
        state: OverlayUiState,
        onCloseApp: () -> Unit,
        onContinue: () -> Unit,
    ) {
        hide() // Defensive: never stack two overlay windows.

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

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                composeView = null
                lifecycleOwner?.onDestroy()
                lifecycleOwner = null
            }
    }

    fun hide() {
        val view = composeView ?: return
        runCatching { windowManager.removeView(view) }
        lifecycleOwner?.onDestroy()
        composeView = null
        lifecycleOwner = null
    }
}
