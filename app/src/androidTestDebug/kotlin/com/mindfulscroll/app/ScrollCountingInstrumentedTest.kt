package com.mindfulscroll.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device half of #25: scroll counting against a real flingable list, through the real
 * service, on the real event stream.
 *
 * Two assertions, one per cause in the issue:
 *
 * 1. A screen whose content keeps changing while nobody touches it must not keep accruing scrolls.
 *    TYPE_WINDOW_CONTENT_CHANGED fires for any subtree change, so an autoplaying feed could once
 *    cross a scroll threshold with the phone lying on a table.
 * 2. N deliberate swipes count as roughly N (±20%), not the ~3x a fixed 300 ms debounce produced
 *    by re-opening inside each fling's deceleration tail.
 *
 * The feed is FeedProbeActivity (a ListView) from the test apk: a foreign package the service can monitor,
 * which the app's own package never can be. Thresholds are set out of reach so the pause screen
 * cannot appear mid-test and swallow the events being counted.
 */
@RunWith(AndroidJUnit4::class)
class ScrollCountingInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness

    /** The test apk's package: what FeedProbeActivity's windows report as their package. */
    private val feedPackage: String = InstrumentationRegistry.getInstrumentation().context.packageName

    @Before
    fun setUp() {
        harness = AccessibilityServiceHarness()
        val entryPoint = EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        )
        // The prompt would sit over the bottom of the feed; it is not what is under test here.
        entryPoint.appSettings().setIntentionCaptureEnabled(false)
        runBlocking {
            entryPoint.monitoredAppRepository().applySelection(
                listOf(
                    MonitoredAppEntity(
                        packageName = feedPackage,
                        appLabel = "Feed probe",
                        isMonitored = true,
                        scrollThreshold = 10_000,
                        timeThresholdMinutes = 600,
                        addedAtMillis = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        assertTrue("service never connected", harness.enableServiceAndAwaitConnection())
        assertTrue(
            "service never picked up $feedPackage as monitored",
            harness.pollUntil(10_000, "monitored-list") {
                feedPackage in harness.diagnostics.state.value.monitoredPackages
            },
        )
    }

    @After
    fun tearDown() {
        if (!::harness.isInitialized) return
        // Restored for the same reason IntentionPromptInstrumentedTest restores it:
        // SharedPreferences outlive the process, and leaving it off would silently disable the
        // prompt for every later test.
        EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        ).appSettings().setIntentionCaptureEnabled(true)
        harness.pressHome()
        harness.disableService()
    }

    private fun launchFeed(animate: Boolean) {
        Log.i(
            TAG,
            harness.shell(
                // A fresh task every time: reusing one left by the previous test would keep ITS extras,
                // so a still-animating feed would be "brought to the front" for the swipe test.
                "am start -W --activity-clear-task -n $feedPackage/com.mindfulscroll.app.FeedProbeActivity " +
                    "--ez ${FeedProbeActivity.EXTRA_ANIMATE} $animate",
            ),
        )
        assertTrue(
            "the feed never became the monitored foreground session. state=${harness.diagnostics.state.value}",
            harness.pollUntil(15_000, "feed-session") {
                harness.diagnostics.state.value.activeSessionPackage == feedPackage
            },
        )
    }

    @Test
    fun anUntouchedScreenWhoseContentKeepsChangingDoesNotAccrueScrolls() {
        launchFeed(animate = true)
        // Let whatever the launch itself emitted settle into its one swipe first.
        Thread.sleep(3_000)

        val before = harness.diagnostics.state.value
        Thread.sleep(OBSERVATION_MILLIS)
        val after = harness.diagnostics.state.value

        // The precondition, asserted: if the ticker produced no events at all, a flat count below
        // would prove nothing.
        val churnEvents = after.rawContentChangedEventCount - before.rawContentChangedEventCount
        assertTrue(
            "The animating feed produced no TYPE_WINDOW_CONTENT_CHANGED events in " +
                "${OBSERVATION_MILLIS}ms, so this test measured nothing. before=$before after=$after",
            churnEvents > 5,
        )
        assertEquals(
            "Scrolls were counted while nobody touched the screen: $churnEvents content-changed " +
                "events over ${OBSERVATION_MILLIS}ms of animation advanced the count. log=${after.recentLog}",
            before.countedScrollTicks,
            after.countedScrollTicks,
        )
    }

    @Test
    fun deliberateSwipesCountAsRoughlyOneEach() {
        launchFeed(animate = false)
        Thread.sleep(2_000)

        val before = harness.diagnostics.state.value
        val (width, height) = screenSize()
        val settleTimes = mutableListOf<Long>()
        repeat(SWIPES) {
            // An upward fling through the middle of the feed, then wait for the list to come to
            // rest before the next one: swipe, read, swipe. Waiting is not a convenience. Without
            // touch events there is no way to split a scroll that never stops moving, so a second
            // fling landing while the first is still decelerating is part of the same swipe by
            // definition (see ScrollGestureCoalescer). This test pins "one settled fling = one
            // scroll", and the unit tests pin what happens to swipes that overlap.
            harness.shell("input swipe ${width / 2} ${height * 3 / 4} ${width / 2} ${height / 4} 300")
            settleTimes += awaitScrollEventsToSettle()
        }
        val after = harness.diagnostics.state.value

        val counted = after.countedScrollTicks - before.countedScrollTicks
        val rawEvents = (after.rawScrollEventCount - before.rawScrollEventCount) +
            (after.rawContentChangedEventCount - before.rawContentChangedEventCount)
        Log.i(
            TAG,
            "$SWIPES swipes -> counted=$counted raw=$rawEvents " +
                "viaViewScrolled=${after.countedScrollTicksViaViewScrolled - before.countedScrollTicksViaViewScrolled} " +
                "viaContentChanged=${after.countedScrollTicksViaContentChanged - before.countedScrollTicksViaContentChanged} " +
                "folded=${after.scrollEventsFoldedIntoSwipe - before.scrollEventsFoldedIntoSwipe} " +
                "settle times ms=$settleTimes",
        )

        assertTrue(
            "The feed emitted only $rawEvents scroll-related events for $SWIPES swipes, so the " +
                "coalescing was never exercised. state=$after",
            rawEvents > SWIPES * 2,
        )
        val lowest = (SWIPES * 0.8).toLong()
        val highest = (SWIPES * 1.2).toLong()
        assertTrue(
            "$SWIPES deliberate swipes counted as $counted scrolls, outside the ±20% band " +
                "[$lowest, $highest] from $rawEvents raw events. log=${after.recentLog}",
            counted in lowest..highest,
        )
    }

    /**
     * Waits until no scroll-related event has arrived for comfortably longer than the idle gap,
     * and returns how long that took after the swipe - roughly the fling's deceleration tail.
     */
    private fun awaitScrollEventsToSettle(): Long {
        val start = System.currentTimeMillis()
        fun rawTotal() = harness.diagnostics.state.value.let { it.rawScrollEventCount + it.rawContentChangedEventCount }
        var lastTotal = rawTotal()
        var lastChangeAt = start
        while (System.currentTimeMillis() - lastChangeAt < SETTLED_AFTER_MILLIS) {
            assertTrue(
                "The feed never stopped emitting scroll events for ${SETTLED_AFTER_MILLIS}ms " +
                    "within ${SETTLE_TIMEOUT_MILLIS}ms of a swipe, so swipes cannot be told apart " +
                    "here at all. state=${harness.diagnostics.state.value}",
                System.currentTimeMillis() - start < SETTLE_TIMEOUT_MILLIS,
            )
            Thread.sleep(100)
            val total = rawTotal()
            if (total != lastTotal) {
                lastTotal = total
                lastChangeAt = System.currentTimeMillis()
            }
        }
        return lastChangeAt - start
    }

    private fun screenSize(): Pair<Int, Int> {
        // "Physical size: 1080x2340"
        val size = harness.shell("wm size").substringAfterLast(':').trim().split('x')
        return size[0].trim().toInt() to size[1].trim().toInt()
    }

    private companion object {
        const val TAG = "ScrollCountingTest"
        const val SWIPES = 10
        const val OBSERVATION_MILLIS = 8_000L

        /** Quiet for this long means the fling is over - comfortably past the 800 ms idle gap. */
        const val SETTLED_AFTER_MILLIS = 1_500L
        const val SETTLE_TIMEOUT_MILLIS = 15_000L
    }
}
