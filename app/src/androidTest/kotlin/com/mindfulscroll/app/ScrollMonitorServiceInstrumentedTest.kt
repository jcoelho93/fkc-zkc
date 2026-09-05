package com.mindfulscroll.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulscroll.app.accessibility.DiagnosticsEntryPoint
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * via ServiceDiagnostics (readable in-process through a debug-only Hilt entry point), whether
 * it connects and receives so much as a single TYPE_WINDOW_STATE_CHANGED event - about as
 * basic and unconditional an accessibility event as exists, so if this doesn't fire the
 * problem is in event delivery/registration, not anything scroll-specific.
 *
 * This test spent several CI rounds failing at its own first step - "service never connected" -
 * while the settings read back exactly as written and AccessibilityManagerService logged
 * nothing at all. The cause turned out to be the test harness, not the app: a connected
 * UiAutomation suppresses every other accessibility service on the device unless it is created
 * with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, and AMS implements that suppression by
 * quietly not binding those services. Silent by design, which is why widening the logcat grep
 * kept turning up nothing. See setUp() for the flag; that silence is also why this test now
 * dumps `dumpsys accessibility` when the connection step fails.
 *
 * Note this was never the bug from the original report - there the service *did* connect
 * ("Connected: Yes" as a settled state) and simply received no events. Suppression only ever
 * blocked this test from reaching the question it exists to ask.
 */
@RunWith(AndroidJUnit4::class)
class ScrollMonitorServiceInstrumentedTest {

    private lateinit var diagnostics: ServiceDiagnostics
    private lateinit var componentName: String
    private lateinit var uiAutomation: UiAutomation
    private lateinit var targetContext: Context

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        targetContext = context
        diagnostics = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DiagnosticsEntryPoint::class.java,
        ).serviceDiagnostics()
        componentName = "${context.packageName}/com.mindfulscroll.app.accessibility.ScrollMonitorService"
        // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES is load-bearing, not a tweak: by default a
        // connected UiAutomation suppresses every other accessibility service on the device, and
        // AccessibilityManagerService enforces that by silently declining to bind them (see
        // UiAutomationManager.suppressingAccessibilityServicesLocked - there is no log, no error,
        // the service simply never connects). Every instrumented test holds a UiAutomation, and
        // this test needs one anyway to run `settings put`, so without this flag the test's own
        // harness is what prevents the service it is trying to enable from ever starting.
        uiAutomation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @After
    fun tearDown() {
        runShellCommand("settings put secure enabled_accessibility_services ''")
    }

    @Test
    fun realServiceConnectsAndReceivesWindowStateChangedEvents() {
        // Service list first, master switch last - the write to accessibility_enabled is
        // what should trigger AccessibilityManagerService to (re)read the service list, so
        // the list needs to already be correct when that happens.
        runShellCommand("settings put secure enabled_accessibility_services $componentName")
        runShellCommand("settings put secure accessibility_enabled 1")

        val readBackServices = runShellCommandForOutput("settings get secure enabled_accessibility_services")
        val readBackEnabled = runShellCommandForOutput("settings get secure accessibility_enabled")
        Log.i(
            "ScrollMonitorServiceTest",
            "After writing settings - enabled_accessibility_services=\"$readBackServices\" accessibility_enabled=\"$readBackEnabled\" (expected component: $componentName)",
        )

        val connected = pollUntilWithProgress(timeoutMillis = 30_000, logTag = "connect") {
            diagnostics.state.value.isServiceConnected
        }
        if (!connected) {
            // `dumpsys accessibility` lists the bound services and any UiAutomation service that
            // is suppressing them - i.e. it distinguishes "never registered", "registered but not
            // bound" and "suppressed" directly, instead of leaving them to be inferred.
            Log.i("ScrollMonitorServiceTest", "dumpsys accessibility:\n" + runShellCommandForOutput("dumpsys accessibility"))
            fail(
                "ScrollMonitorService never connected within 30s after enabling " +
                    "$componentName. Settings read back as: " +
                    "enabled_accessibility_services=\"$readBackServices\", " +
                    "accessibility_enabled=\"$readBackEnabled\". If those values look correct, " +
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

        Log.i("ScrollMonitorServiceTest", "Service connected, launching an activity to trigger a window-state-change event")
        val scenario = ActivityScenario.launch(ScrollProbeActivity::class.java)

        val gotForegroundEvent = pollUntilWithProgress(timeoutMillis = 15_000, logTag = "foreground-event") {
            diagnostics.state.value.currentForegroundPackage != null
        }
        scenario.close()

        val finalState = diagnostics.state.value
        Log.i(
            "ScrollMonitorServiceTest",
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
            Log.i("ScrollMonitorServiceTest", "enabled services per AccessibilityManager: " + describeEnabledServices())
            Log.i("ScrollMonitorServiceTest", "dumpsys accessibility:\n" + runShellCommandForOutput("dumpsys accessibility"))
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

    private fun pollUntilWithProgress(
        timeoutMillis: Long,
        logTag: String,
        intervalMillis: Long = 500,
        condition: () -> Boolean,
    ): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMillis
        var lastLoggedSecond = -1L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                Log.i("ScrollMonitorServiceTest", "[$logTag] condition met after ${System.currentTimeMillis() - start}ms")
                return true
            }
            val elapsedSeconds = (System.currentTimeMillis() - start) / 1000
            if (elapsedSeconds != lastLoggedSecond && elapsedSeconds % 2 == 0L) {
                lastLoggedSecond = elapsedSeconds
                Log.i(
                    "ScrollMonitorServiceTest",
                    "[$logTag] still waiting at ${elapsedSeconds}s - state=${diagnostics.state.value}",
                )
            }
            Thread.sleep(intervalMillis)
        }
        return condition()
    }

    /** The system's own parsed view of every enabled service - the event mask AMS dispatches against. */
    private fun describeEnabledServices(): String {
        val manager = targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (services.isEmpty()) return "<none>"
        return services.joinToString(separator = "\n") { info ->
            "  ${info.id}: eventTypes=0x${info.eventTypes.toString(16)} " +
                "feedbackType=0x${info.feedbackType.toString(16)} flags=0x${info.flags.toString(16)} " +
                "packageNames=${info.packageNames?.toList()}"
        }
    }

    /** Blocks until the shell command's output stream is fully drained, so it has actually completed. */
    private fun runShellCommand(command: String) {
        runShellCommandForOutput(command)
    }

    private fun runShellCommandForOutput(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .trim()
}
