package com.mindfulscroll.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.util.Log
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
 * First attempt on CI failed at the "connects within 10s" step. That's a materially different
 * symptom than the original bug report (there, the Diagnostics screen showed "Connected: Yes"
 * as a settled state, just with zero events after) - so either the settings-writing sequence
 * here is wrong, or 10s genuinely isn't enough on a loaded CI emulator. This version fixes a
 * likely ordering issue (enabled_accessibility_services should be written before the
 * accessibility_enabled master switch, not after - the latter is what should trigger
 * AccessibilityManagerService to (re)read the former), reads back both values to confirm the
 * writes actually took, and logs progress every 2s with a much longer timeout so a real
 * failure produces a much clearer picture than "never connected" alone.
 */
@RunWith(AndroidJUnit4::class)
class ScrollMonitorServiceInstrumentedTest {

    private lateinit var diagnostics: ServiceDiagnostics
    private lateinit var componentName: String
    private lateinit var uiAutomation: UiAutomation

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        diagnostics = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DiagnosticsEntryPoint::class.java,
        ).serviceDiagnostics()
        componentName = "${context.packageName}/com.mindfulscroll.app.accessibility.ScrollMonitorService"
        uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
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

        Log.i("ScrollMonitorServiceTest", "Service connected, launching an activity to trigger a window-state-change event")
        val scenario = ActivityScenario.launch(ScrollProbeActivity::class.java)

        val gotForegroundEvent = pollUntilWithProgress(timeoutMillis = 15_000, logTag = "foreground-event") {
            diagnostics.state.value.currentForegroundPackage != null
        }
        scenario.close()

        val finalState = diagnostics.state.value
        Log.i(
            "ScrollMonitorServiceTest",
            "currentForegroundPackage=${finalState.currentForegroundPackage} recentLog=${finalState.recentLog}",
        )

        assertTrue(
            "ScrollMonitorService connected but never received a single " +
                "TYPE_WINDOW_STATE_CHANGED event within 15s of launching an activity " +
                "(currentForegroundPackage stayed null; recent log: ${finalState.recentLog}). " +
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
