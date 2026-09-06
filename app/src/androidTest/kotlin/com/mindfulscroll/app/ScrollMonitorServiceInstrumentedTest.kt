package com.mindfulscroll.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ScrollEventDetectionTest checks whether Compose fires scroll-related accessibility events
 * *in general*, using UiAutomation's own built-in system-wide event listener - it never
 * actually exercises our declared ScrollMonitorService (its manifest <service> entry, its
 * accessibility_service_config.xml, its Hilt injection). This test closes that gap: it
 * enables the real service exactly the way a real user does (writing to
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, scripted instead of tapped) and checks,
 * via ServiceDiagnostics (readable in-process through a Hilt entry point), whether it
 * connects and receives so much as a single TYPE_WINDOW_STATE_CHANGED event - about as
 * basic and unconditional an accessibility event as exists, so if this doesn't fire the
 * problem is in event delivery/registration, not anything scroll-specific.
 *
 * This test spent several CI rounds failing at its own first step - "service never connected" -
 * while the settings read back exactly as written and AccessibilityManagerService logged
 * nothing at all. The cause turned out to be the test harness, not the app: a connected
 * UiAutomation suppresses every other accessibility service on the device unless it is created
 * with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, and AMS implements that suppression by
 * quietly not binding those services. Silent by design, which is why widening the logcat grep
 * kept turning up nothing. See AccessibilityServiceHarness for the flag; that silence is also
 * why this test dumps `dumpsys accessibility` when the connection step fails.
 *
 * Note this was never the bug from the original report - there the service *did* connect
 * ("Connected: Yes" as a settled state) and simply received no events. Suppression only ever
 * blocked this test from reaching the question it exists to ask.
 *
 * This class lives in `androidTest` (not `androidTestDebug`) and deliberately touches nothing
 * debug-only, so `connectedReleaseAndroidTest` runs it against the R8-minified build. That is
 * the only way to answer "does the service still resolve its event mask after minification?",
 * since the resolved mask is a runtime value the system computes from the shipped APK.
 */
@RunWith(AndroidJUnit4::class)
class ScrollMonitorServiceInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness

    @Before
    fun setUp() {
        harness = AccessibilityServiceHarness()
    }

    @After
    fun tearDown() {
        // See the note on OverlayRenderInstrumentedTest.tearDown: a throwing @After hides the
        // @Before failure that caused it.
        if (!::harness.isInitialized) return
        harness.disableService()
    }

    @Test
    fun realServiceConnectsAndReceivesWindowStateChangedEvents() {
        val diagnostics = harness.diagnostics

        if (!harness.enableServiceAndAwaitConnection()) {
            // `dumpsys accessibility` lists the bound services and any UiAutomation service that
            // is suppressing them - i.e. it distinguishes "never registered", "registered but not
            // bound" and "suppressed" directly, instead of leaving them to be inferred.
            Log.i(TAG, "dumpsys accessibility:\n" + harness.dumpsysAccessibility())
            throw AssertionError(
                "ScrollMonitorService never connected within 30s after enabling " +
                    "${harness.componentName}. If the settings logged above read back correctly, " +
                    "this points to a genuine binding/registration problem (check the manifest " +
                    "<service> declaration and accessibility_service_config.xml); if they don't " +
                    "match what was written, the settings writes themselves aren't taking effect.",
            )
        }

        // Connecting is not the same as being subscribed to anything. If the config XML never
        // reached the system the mask is 0, the service looks perfectly healthy, and no event is
        // ever dispatched - the exact silent failure that cost this project several rounds. Assert
        // it separately so the symptom is named rather than showing up as "no events, cause unknown".
        val resolvedEventTypes = diagnostics.state.value.resolvedEventTypes
        assertTrue(
            "ScrollMonitorService connected but the system resolved an event-type mask of 0x" +
                "${resolvedEventTypes.toString(16)} for it, so AccessibilityManagerService will " +
                "never dispatch it a single event. This means accessibility_service_config.xml " +
                "never reached the system - almost always a wrong <meta-data> android:name in " +
                "AndroidManifest.xml (it must be exactly \"android.accessibilityservice\"). " +
                "Resolved info: ${diagnostics.state.value.resolvedServiceInfo}",
            resolvedEventTypes != 0,
        )

        // Asserted exactly, not merely "non-zero". A partially-resolved mask is its own silent
        // failure: losing typeWindowStateChanged alone would leave scroll counting apparently
        // healthy while foreground tracking stops - and foreground time is the trustworthy half
        // of the threshold for every Compose-feed app. The expected value is built from the
        // platform constants rather than hard-coded to 0x1820 so a failure can name the missing
        // event type instead of just printing two hex numbers.
        assertEquals(
            "The system resolved event-type mask 0x${resolvedEventTypes.toString(16)} for " +
                "ScrollMonitorService, but accessibility_service_config.xml asks for 0x" +
                "${AccessibilityMasks.EXPECTED_EVENT_TYPES.toString(16)}. Missing: " +
                AccessibilityMasks.describe(
                    AccessibilityMasks.EXPECTED_EVENT_TYPES and resolvedEventTypes.inv(),
                ) +
                "; unexpected extras: " +
                AccessibilityMasks.describe(
                    resolvedEventTypes and AccessibilityMasks.EXPECTED_EVENT_TYPES.inv(),
                ) +
                ". Resolved info: ${diagnostics.state.value.resolvedServiceInfo}",
            AccessibilityMasks.EXPECTED_EVENT_TYPES,
            resolvedEventTypes,
        )

        Log.i(TAG, "Service connected, launching an activity to trigger a window-state-change event")
        // MainActivity rather than the debug-only ScrollProbeActivity, so this test runs unchanged
        // against the release variant - and launched through `am start` rather than
        // ActivityScenario, for the R8 reason documented on AccessibilityServiceHarness.
        Log.i(TAG, "am start said: " + harness.launchMainActivity())

        val gotForegroundEvent = harness.pollUntil(15_000, "foreground-event") {
            harness.diagnostics.state.value.currentForegroundPackage != null
        }
        harness.pressHome()

        val finalState = diagnostics.state.value
        Log.i(
            TAG,
            "currentForegroundPackage=${finalState.currentForegroundPackage} " +
                "totalEventCount=${finalState.totalEventCount} " +
                "resolvedServiceInfo=${finalState.resolvedServiceInfo} recentLog=${finalState.recentLog}",
        )

        if (!gotForegroundEvent) {
            // Three independent views of the same question, because the counters alone can't
            // separate the candidate causes:
            //   - totalEventCount (above) separates "never dispatched to us" from "dispatched
            //     and discarded by our own `when`".
            //   - the system's own AccessibilityServiceInfo shows the event mask AMS dispatches
            //     against, which is what the XML *became*, not what it says.
            //   - dumpsys accessibility shows AMS's whole picture: bound services, and any
            //     UiAutomation service sitting alongside ours.
            Log.i(TAG, "enabled services per AccessibilityManager: " + harness.describeEnabledServices())
            Log.i(TAG, "dumpsys accessibility:\n" + harness.dumpsysAccessibility())
        }

        assertTrue(
            "ScrollMonitorService connected but never received a single " +
                "TYPE_WINDOW_STATE_CHANGED event within 15s of launching an activity " +
                "(currentForegroundPackage stayed null; totalEventCount=${finalState.totalEventCount}; " +
                "resolvedServiceInfo=${finalState.resolvedServiceInfo}; recent log: ${finalState.recentLog}). " +
                "This is a basic, virtually-always-fires event, so if this fails the service " +
                "isn't receiving ANY accessibility events at all - the bug is in event " +
                "delivery/registration, not anything scroll-specific.",
            gotForegroundEvent,
        )
    }

    private companion object {
        const val TAG = "ScrollMonitorServiceTest"
    }
}
