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
        runShellCommand("settings put secure accessibility_enabled 1")
        runShellCommand("settings put secure enabled_accessibility_services $componentName")

        val connected = pollUntil(timeoutMillis = 10_000) { diagnostics.state.value.isServiceConnected }
        if (!connected) {
            fail(
                "ScrollMonitorService never connected within 10s after enabling " +
                    "$componentName via settings put secure enabled_accessibility_services. " +
                    "Check the accessibility_service_config.xml / manifest <service> " +
                    "declaration for a mismatch (expected component: $componentName).",
            )
        }

        Log.i("ScrollMonitorServiceTest", "Service connected, launching an activity to trigger a window-state-change event")
        val scenario = ActivityScenario.launch(ScrollProbeActivity::class.java)

        val gotForegroundEvent = pollUntil(timeoutMillis = 10_000) {
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
                "TYPE_WINDOW_STATE_CHANGED event within 10s of launching an activity " +
                "(currentForegroundPackage stayed null; recent log: ${finalState.recentLog}). " +
                "This is a basic, virtually-always-fires event, so if this fails the service " +
                "isn't receiving ANY accessibility events at all - the bug is in event " +
                "delivery/registration, not anything scroll-specific.",
            gotForegroundEvent,
        )
    }

    private fun pollUntil(timeoutMillis: Long, intervalMillis: Long = 200, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMillis)
        }
        return condition()
    }

    /** Blocks until the shell command's output stream is fully drained, so it has actually completed. */
    private fun runShellCommand(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
