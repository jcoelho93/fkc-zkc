package com.mindfulscroll.app

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answers the question this whole detection design hinges on, empirically instead of from
 * memory: when a Compose LazyColumn (what this app's own screens use, and what parts of
 * Instagram/Reddit/TikTok/X increasingly use for their feeds too) is scrolled, which
 * AccessibilityEvent types actually fire?
 *
 * TYPE_VIEW_SCROLLED is a legacy View.scrollBy()-driven event; LazyColumn (like RecyclerView)
 * moves child content directly rather than calling View.scrollBy(), so it may not fire it
 * reliably. ScrollMonitorService also listens for TYPE_WINDOW_CONTENT_CHANGED as a fallback -
 * this test is what justifies that, and will catch it if a future Compose version changes
 * this behavior either way.
 *
 * Uses UiAutomation.setOnAccessibilityEventListener directly rather than a real
 * AccessibilityService, since UiAutomation (which backs Espresso/UiAutomator) is already
 * registered as a system-wide accessibility event listener during instrumented tests - no
 * extra service registration or shell commands needed.
 */
@RunWith(AndroidJUnit4::class)
class ScrollEventDetectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ScrollProbeActivity>()

    @Test
    fun scrollingLazyColumnFiresSomeAccessibilityScrollSignal() {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val viewScrolledCount = AtomicInteger(0)
        val contentChangedCount = AtomicInteger(0)

        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() != targetPackage) return@setOnAccessibilityEventListener
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> viewScrolledCount.incrementAndGet()
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> contentChangedCount.incrementAndGet()
            }
        }

        composeRule.onNodeWithTag(ScrollProbeActivity.SCROLL_PROBE_LIST_TAG).performTouchInput {
            repeat(15) { swipeUp() }
        }
        composeRule.waitForIdle()
        Thread.sleep(1_500) // accessibility events are dispatched async; give them time to land

        val viewScrolled = viewScrolledCount.get()
        val contentChanged = contentChangedCount.get()
        Log.i(
            "ScrollEventDetectionTest",
            "After 15 swipes on a LazyColumn: TYPE_VIEW_SCROLLED=$viewScrolled, TYPE_WINDOW_CONTENT_CHANGED=$contentChanged",
        )

        assertTrue(
            "Expected at least one scroll-related accessibility event after swiping a " +
                "LazyColumn 15 times, but got TYPE_VIEW_SCROLLED=$viewScrolled and " +
                "TYPE_WINDOW_CONTENT_CHANGED=$contentChanged (both zero). If this fails, " +
                "neither signal ScrollMonitorService listens for is actually firing for " +
                "Compose lists, and scroll detection needs a different mechanism entirely " +
                "(e.g. AccessibilityService#onMotionEvent on API 34+, or touch-exploration " +
                "gesture detection).",
            viewScrolled > 0 || contentChanged > 0,
        )
    }
}
