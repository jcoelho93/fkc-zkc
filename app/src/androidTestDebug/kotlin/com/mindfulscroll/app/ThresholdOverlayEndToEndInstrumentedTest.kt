package com.mindfulscroll.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.OverlayEventEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole chain, in one test: a monitored app comes to the foreground, real foreground time
 * accumulates, the time threshold crosses, and the interruption overlay actually draws.
 *
 * Everything else in the suite covers a link in isolation. ThresholdEvaluatorTest covers the
 * arithmetic with no device at all; OverlayRenderInstrumentedTest drives OverlayController.show()
 * directly, which is deliberate - the window mechanics are the part that kept breaking, and
 * driving them directly keeps that test fast enough to run on both variants every push. What
 * nothing covered until now is the wiring *between* them, which is where both of this project's
 * silent failures actually lived:
 *
 *  - the threshold only ever being evaluated from inside the scroll handler, so an app that fires
 *    no scroll events (every Compose feed - see ScrollEventDetectionTest) could never cross it;
 *  - the overlay being built on the application context instead of the service's, so the window
 *    was rejected at the moment it was finally needed.
 *
 * Both would pass every isolated test in this suite. Neither would survive this one.
 *
 * ## Why this test takes a real minute
 *
 * `timeThresholdMinutes` is whole minutes and ThresholdEvaluator requires it to be positive, so
 * one minute is the floor - and it is real wall-clock time, because the service reads
 * System.currentTimeMillis() directly. A test-only threshold override or an injected clock would
 * make this fast, and both were rejected: the seam would sit exactly where the bug this test
 * exists to catch would hide, and a test that stubs out the clock cannot tell you that a delayed
 * coroutine actually fires on a device. One slow test is the cheaper trade.
 *
 * It is therefore in `androidTestDebug`, not `androidTest`, so it costs that minute once per push
 * rather than twice. Nothing here is R8-sensitive: it asserts on our own code's behaviour, not on
 * what survived minification, which is what the release run is for.
 */
@RunWith(AndroidJUnit4::class)
class ThresholdOverlayEndToEndInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness

    /**
     * The Settings app stands in for a feed app, as it does in IntentionPromptInstrumentedTest:
     * the service reacts to a TYPE_WINDOW_STATE_CHANGED from a package it was told to monitor, and
     * nothing in that path cares which app it is. It is also reliably present on every emulator
     * image, and - useful here - it sits still once opened, so the minute this test spends waiting
     * is a genuinely idle minute.
     */
    private val monitoredPackage = "com.android.settings"

    @Before
    fun setUp() {
        harness = AccessibilityServiceHarness()
        harness.keepScreenAwake()
        val entryPoint = EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        )
        // Left ON, which is the shipping default. The prompt appears at the start of the very
        // session this test is timing, so switching it off would quietly excuse the overlay from
        // having to coexist with it - and "two of our windows in one session" is precisely the
        // interaction that has no other coverage.
        entryPoint.appSettings().setIntentionCaptureEnabled(true)
        runBlocking {
            entryPoint.monitoredAppRepository().applySelection(
                listOf(
                    MonitoredAppEntity(
                        packageName = monitoredPackage,
                        appLabel = "Settings",
                        isMonitored = true,
                        addedAtMillis = System.currentTimeMillis(),
                    ),
                ),
            )
            // Set through updateThresholds rather than in the entity above, because
            // applySelection deliberately PRESERVES the thresholds of an app already on the list
            // (#19) - so a row left behind by an earlier run would silently keep its old values
            // and this test would be measuring a threshold it did not set.
            //
            // The scroll threshold is absurdly high rather than merely high: Settings fires
            // plenty of TYPE_WINDOW_CONTENT_CHANGED events, each of which counts as a scroll
            // tick. If scroll count could plausibly reach the limit, a pass would not prove the
            // TIME half of the threshold works - and the time half is the only one a Compose feed
            // can ever cross.
            entryPoint.monitoredAppRepository().updateThresholds(
                packageName = monitoredPackage,
                scrollThreshold = 1_000_000,
                timeThresholdMinutes = THRESHOLD_MINUTES,
            )
            // A leftover session row for this package from an earlier test would carry an older
            // sessionStartMillis, and the threshold would appear to cross immediately. That would
            // be a pass this test had not earned.
            entryPoint.scrollStatsRepository().clearSession(monitoredPackage)
        }
    }

    @After
    fun tearDown() {
        // Guarded: a throwing @After replaces the @Before failure that caused it, and the setUp
        // failure is always the one worth reading.
        if (!::harness.isInitialized) return
        harness.releaseScreen()
        harness.pressHome()
        harness.disableService()
    }

    @Test
    fun foregroundTimeAloneCrossesTheThresholdAndDrawsTheOverlay() {
        val diagnostics = harness.diagnostics

        val connected = harness.enableServiceAndAwaitConnection()
        assertTrue(
            "ScrollMonitorService never connected, so no part of the chain can be tested. " +
                "ScrollMonitorServiceInstrumentedTest isolates this step. dumpsys accessibility:\n" +
                if (connected) "" else harness.dumpsysAccessibility(),
            connected,
        )

        // The service learns its monitored list reactively from Room, so wait for it rather than
        // racing it - otherwise a slow emulator produces "the threshold never fired" for a reason
        // that has nothing to do with thresholds.
        assertTrue(
            "The service never picked up $monitoredPackage as monitored; it saw " +
                "${diagnostics.state.value.monitoredPackages}",
            harness.pollUntil(10_000, "monitored-list") {
                monitoredPackage in diagnostics.state.value.monitoredPackages
            },
        )

        val renderedBefore = diagnostics.state.value.overlaysRenderedCount
        val scheduledChecksBefore = diagnostics.state.value.scheduledThresholdChecksFired
        val overlayEventsBefore = readOverlayEvents().size

        Log.i(TAG, "launching $monitoredPackage: " + harness.shell("am start -W -a android.settings.SETTINGS"))

        // Link 1: the foreground change was seen and a session clock started. Asserted separately
        // so a failure names which link broke instead of only reporting that no overlay appeared.
        val sessionStarted = harness.pollUntil(15_000, "session-start") {
            diagnostics.state.value.activeSessionPackage == monitoredPackage &&
                diagnostics.state.value.activeSessionStartMillis != null
        }
        assertTrue(
            "$monitoredPackage came to the foreground but the service never opened a session for " +
                "it (activeSessionPackage=${diagnostics.state.value.activeSessionPackage}, " +
                "currentForegroundPackage=${diagnostics.state.value.currentForegroundPackage}). " +
                "The threshold clock is the session clock, so nothing downstream can happen. " +
                "Log: ${diagnostics.state.value.recentLog}",
            sessionStarted,
        )
        val sessionStartMillis = requireNotNull(diagnostics.state.value.activeSessionStartMillis)

        // Link 2: the overlay DREW. Not that show() returned, and not that a window was added -
        // this project has been burned twice by reading those as "the user saw something", which
        // is why ServiceDiagnostics counts added and drawn separately at all.
        //
        // The wait is the threshold plus generous slack: the delayed check is armed when the app
        // comes forward, and an emulator under load can be late by several seconds without
        // anything being wrong.
        val timeoutMillis = THRESHOLD_MINUTES * 60_000L + 45_000L
        val drew = harness.pollUntil(timeoutMillis, "threshold-overlay-render") {
            diagnostics.state.value.overlaysRenderedCount > renderedBefore
        }
        val state = diagnostics.state.value
        Log.i(
            TAG,
            "after waiting out the threshold: added=${state.overlaysShownCount} " +
                "rendered=${state.overlaysRenderedCount} " +
                "scheduledChecks=${state.scheduledThresholdChecksFired} " +
                "render=${state.lastOverlayRender} error=${state.lastOverlayError} " +
                "countedScrollTicks=${state.countedScrollTicks}",
        )

        assertTrue(
            "$monitoredPackage sat in the foreground for longer than its ${THRESHOLD_MINUTES}-minute " +
                "threshold and the interruption overlay never drew a frame. " +
                "Overlay windows added=${state.overlaysShownCount}, actually drawn=" +
                "${state.overlaysRenderedCount}, scheduled threshold checks fired=" +
                "${state.scheduledThresholdChecksFired}, lastOverlayRender=${state.lastOverlayRender}, " +
                "lastOverlayError=${state.lastOverlayError}. If checks fired but nothing was added, " +
                "the threshold arithmetic or the session clock is wrong; if a window was added but " +
                "never drew, see OverlayRenderInstrumentedTest. Log: ${state.recentLog}",
            drew,
        )
        assertNull(
            "The overlay drew, but the service also recorded an overlay failure - one of the two " +
                "is lying and both are load-bearing. lastOverlayError=${state.lastOverlayError}",
            state.lastOverlayError,
        )

        // Link 3: it was the SCHEDULED check that got there, not a scroll event. This is the
        // regression guard for the original bug, and it is not implied by the overlay appearing:
        // Settings emits content-changed events, any one of which would also have evaluated the
        // threshold and produced an identical-looking pass while the delayed path was dead.
        assertTrue(
            "The overlay appeared, but the delayed threshold check never fired (still at " +
                "${state.scheduledThresholdChecksFired}, was $scheduledChecksBefore). Something " +
                "else evaluated the threshold - almost certainly an incoming accessibility event. " +
                "That path does not exist in a Compose feed, which fires none, so this app would " +
                "be back to a threshold that can never be crossed in the apps it is built for. " +
                "Log: ${state.recentLog}",
            state.scheduledThresholdChecksFired > scheduledChecksBefore,
        )

        // Link 4: the crossing was persisted, with the session time that caused it. The overlay
        // being on screen and the record of it existing are separate facts, and the weekly report
        // (#6) reads the record, not the screen.
        val events = readOverlayEvents()
        assertEquals(
            "Exactly one overlay event should have been recorded for this crossing. Rows for " +
                "$monitoredPackage today: $events",
            overlayEventsBefore + 1,
            events.size,
        )
        val event = events.first()
        assertNotNull("no overlay event row was written at all", event)
        assertEquals(
            "the overlay event was filed against the wrong package",
            monitoredPackage,
            event.packageName,
        )
        assertEquals(
            "The overlay was recorded as already resolved, but nobody has answered it yet. " +
                "A row that starts anywhere but PENDING makes 'shown and ignored' uncountable.",
            OverlayChoice.PENDING,
            event.choice,
        )
        assertTrue(
            "The overlay fired at ${event.sessionTimeMillisAtTrigger}ms of session time, which is " +
                "under the ${THRESHOLD_MINUTES}-minute threshold it is supposed to have crossed. " +
                "The session clock and the threshold disagree, which means the overlay can " +
                "interrupt someone early - the one thing this app must not do casually. " +
                "Session started at $sessionStartMillis.",
            event.sessionTimeMillisAtTrigger >= THRESHOLD_MINUTES * 60_000L,
        )
    }

    /** Today's overlay events for the monitored package, newest first. */
    private fun readOverlayEvents(): List<OverlayEventEntity> {
        val repository = EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        ).scrollStatsRepository()
        val today = repository.todayEpochDay()
        return runBlocking {
            repository.observeOverlayEventsForRange(today, today).first()
        }.filter { it.packageName == monitoredPackage }
    }

    private companion object {
        const val TAG = "ThresholdOverlayE2ETest"

        /** The floor: whole minutes, and ThresholdEvaluator rejects zero. See the class doc. */
        const val THRESHOLD_MINUTES = 1
    }
}
