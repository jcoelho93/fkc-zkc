package com.mindfulscroll.app

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answered the question this whole detection design hinges on, empirically instead of from
 * memory: when a Compose LazyColumn (what this app's own screens use, and what parts of
 * Instagram/Reddit/TikTok/X increasingly use for their feeds too) is scrolled, which
 * AccessibilityEvent types actually fire?
 *
 * Answer, confirmed on a real emulator (API 30): neither. Scrolling a LazyColumn 15 times
 * fires zero TYPE_VIEW_SCROLLED and zero TYPE_WINDOW_CONTENT_CHANGED events. This is why
 * ScrollMonitorService treats the time-based half of its threshold as the reliable signal
 * (checked on its own independent schedule) and scroll count as best-effort only - see the
 * README's detection notes and ScrollMonitorService's class doc.
 *
 * This test now only logs the counts rather than asserting on them: they're a known,
 * accepted platform characteristic, not a bug to keep failing the build over. It stays in
 * the suite (and keeps running on every CI push) so a change in either direction - a future
 * Compose/AGP version starting to fire one of these, or the harness itself breaking (activity
 * fails to launch, gesture fails to perform) - shows up in the log/test report instead of
 * silently going unnoticed.
 *
 * Uses UiAutomation.setOnAccessibilityEventListener directly rather than a real
 * AccessibilityService, since UiAutomation (which backs Espresso/UiAutomator) is already
 * registered as a system-wide accessibility event listener during instrumented tests - no
 * extra service registration or shell commands needed.
 *
 * The listener is cleared in a finally block as ordinary hygiene - it is a process-wide
 * registration on a shared UiAutomation instance. It was once believed to be what stopped
 * ScrollMonitorServiceInstrumentedTest's real AccessibilityService from connecting; it isn't.
 * That is caused by UiAutomation suppressing other accessibility services whenever it is
 * connected without FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES - independent of whether any
 * listener is set. See that test's setUp() for the fix.
 */
@RunWith(AndroidJUnit4::class)
class ScrollEventDetectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ScrollProbeActivity>()

    @Test
    fun logsWhichAccessibilityEventsFireWhenScrollingALazyColumn() {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val viewScrolledCount = AtomicInteger(0)
        val contentChangedCount = AtomicInteger(0)

        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.packageName?.toString() != targetPackage) return@setOnAccessibilityEventListener
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> viewScrolledCount.incrementAndGet()
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> contentChangedCount.incrementAndGet()
                }
            }

            // Exercising the harness itself (activity launch, tag lookup, gesture injection) is
            // the part that should fail loudly if something regresses - JUnit fails the test on
            // any uncaught exception here, no explicit assertion needed for that.
            composeRule.onNodeWithTag(ScrollProbeActivity.SCROLL_PROBE_LIST_TAG).performTouchInput {
                repeat(15) { swipeUp() }
            }
            composeRule.waitForIdle()
            Thread.sleep(1_500) // accessibility events are dispatched async; give them time to land
        } finally {
            uiAutomation.setOnAccessibilityEventListener(null)
        }

        val viewScrolled = viewScrolledCount.get()
        val contentChanged = contentChangedCount.get()
        Log.i(
            "ScrollEventDetectionTest",
            "After 15 swipes on a LazyColumn: TYPE_VIEW_SCROLLED=$viewScrolled, " +
                "TYPE_WINDOW_CONTENT_CHANGED=$contentChanged " +
                "(both zero was expected as of this test's last update - see the class doc)",
        )
    }
}
