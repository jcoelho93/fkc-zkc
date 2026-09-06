package com.mindfulscroll.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulscroll.app.overlay.OverlayUiState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answers the question commit 7e7cfe4 ("built on the wrong context") left open: the overlay
 * window no longer throws BadTokenException, but does anything actually appear on screen?
 *
 * Those are genuinely different questions, and this project has already been burned twice by
 * treating "no exception" as "it worked". WindowManager.addView() returns void and succeeds
 * long before the window is laid out, sized or composed, so `overlaysShownCount` on its own
 * cannot tell a visible pause screen from a window that was accepted and then never drawn.
 *
 * So this test asserts on two independent witnesses, from opposite sides of the boundary:
 *
 *  1. OUR side - OverlayController's first-draw watcher reports a frame with a non-zero size,
 *     which is what ServiceDiagnostics surfaces as "overlay windows actually drawn" on the
 *     in-app Diagnostics screen. Failing this means the window never rendered.
 *  2. THE SYSTEM's side - AccessibilityManagerService lists a window of type
 *     TYPE_ACCESSIBILITY_OVERLAY among the windows currently on the display. Failing this
 *     while (1) passes would mean we drew into something the window manager doesn't consider
 *     to be on screen.
 *
 * It drives OverlayController directly rather than waiting out a real threshold crossing:
 * the threshold logic is already covered by ThresholdEvaluatorTest, and the device-only part
 * that keeps breaking is the window itself. Driving it directly makes this deterministic and
 * fast enough to run on both the debug and the release (R8-minified) variant on every push.
 */
@RunWith(AndroidJUnit4::class)
class OverlayRenderInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness

    @Before
    fun setUp() {
        harness = AccessibilityServiceHarness()
        // getWindows() returns an empty list unless the querying accessibility service asks for
        // interactive windows; without this the system-side check below would "fail" for a
        // reason that has nothing to do with our overlay.
        val info = harness.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        harness.uiAutomation.serviceInfo = info
    }

    @After
    fun tearDown() {
        // Guarded because an @After that throws REPLACES the @Before failure that caused it, and
        // the setUp failure is always the one worth reading. This exact masking cost a full
        // emulator round: two tests reported "lateinit property harness has not been initialized"
        // and the real exception was nowhere in the report or the logcat.
        if (!::harness.isInitialized) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { harness.overlayController.hide() }
        harness.disableService()
    }

    @Test
    fun overlayWindowIsAddedAndActuallyDrawsAFrame() {
        // Evaluated before the assert rather than inline in its message, so the dumpsys reflects
        // the state after the connection attempt instead of before it.
        val connected = harness.enableServiceAndAwaitConnection()
        assertTrue(
            "ScrollMonitorService never connected, so OverlayController was never handed the " +
                "service context that carries the TYPE_ACCESSIBILITY_OVERLAY window token. " +
                "Nothing about the overlay can be tested until that works - see " +
                "ScrollMonitorServiceInstrumentedTest, which isolates that step. " +
                "dumpsys accessibility:\n${if (connected) "" else harness.dumpsysAccessibility()}",
            connected,
        )

        val controller = harness.overlayController
        val diagnostics = harness.diagnostics
        val renderedBefore = diagnostics.state.value.overlaysRenderedCount

        var added = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            added = controller.show(
                state = OverlayUiState(
                    appLabel = "Test app",
                    scrollCount = 42,
                    sessionMinutes = 11,
                ),
                onCloseApp = {},
                onContinue = {},
            )
        }

        assertTrue(
            "OverlayController.show() returned false - WindowManager rejected the " +
                "TYPE_ACCESSIBILITY_OVERLAY window outright. Reason: ${controller.lastFailureReason}",
            added,
        )
        assertNull(
            "show() returned true but recorded a failure reason, which should be impossible",
            controller.lastFailureReason,
        )

        // Witness 1: our own first-draw watcher. The watchdog inside OverlayController rewrites
        // lastOverlayRender with a complaint if no frame lands within its window, so a timeout
        // here comes with the reason already attached rather than as a bare "condition not met".
        val drew = harness.pollUntil(10_000, "overlay-first-draw") {
            diagnostics.state.value.overlaysRenderedCount > renderedBefore
        }
        val renderDescription = diagnostics.state.value.lastOverlayRender
        Log.i(TAG, "lastOverlayRender=$renderDescription")
        assertTrue(
            "The overlay window was added but never drew a non-zero-sized frame within 10s, so " +
                "nothing was on screen for the user to see. This is exactly the failure mode " +
                "\"overlays shown\" alone cannot distinguish from success. OverlayController " +
                "reported: $renderDescription",
            drew,
        )

        // Witness 2: the system's own window list.
        val sawSystemWindow = harness.pollUntil(5_000, "system-window-list") {
            harness.uiAutomation.windows.any {
                it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }
        }
        assertTrue(
            "OverlayController reported a drawn frame ($renderDescription) but " +
                "AccessibilityManagerService lists no TYPE_ACCESSIBILITY_OVERLAY window on the " +
                "display, so what we drew is not a window the system considers to be on screen. " +
                "Windows the system does see:\n${describeSystemWindows()}",
            sawSystemWindow,
        )

        // A composition that blew up (a plausible R8 casualty on the release variant) takes the
        // process with it, so surviving this far with the service still connected is itself the
        // assertion that the overlay's Compose content ran.
        assertTrue(
            "The overlay rendered but ScrollMonitorService is no longer connected - the service " +
                "process very likely died while composing the overlay. Recent log: " +
                "${diagnostics.state.value.recentLog}",
            diagnostics.state.value.isServiceConnected,
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.hide() }
        assertFalse("hide() left the overlay attached", controller.isShowing())

        val windowGone = harness.pollUntil(5_000, "overlay-window-removed") {
            harness.uiAutomation.windows.none {
                it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }
        }
        assertTrue(
            "hide() removed our view but the system still lists a TYPE_ACCESSIBILITY_OVERLAY " +
                "window, which would leave the user's screen blocked. Windows the system sees:" +
                "\n${describeSystemWindows()}",
            windowGone,
        )
    }

    private fun describeSystemWindows(): String {
        val windows = harness.uiAutomation.windows
        if (windows.isEmpty()) return "  <none - getWindows() returned an empty list>"
        return windows.joinToString(separator = "\n") { window ->
            "  type=${window.type} id=${window.id} active=${window.isActive} " +
                "focused=${window.isFocused} title=${window.title}"
        }
    }

    private companion object {
        const val TAG = "OverlayRenderTest"
    }
}
