package com.mindfulscroll.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulscroll.app.accessibility.DiagnosticsEntryPoint
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import com.mindfulscroll.app.overlay.OverlayController
import dagger.hilt.android.EntryPointAccessors

private const val TAG = "MindfulScrollTest"

/**
 * The event-type mask accessibility_service_config.xml asks for, expressed in platform
 * constants rather than as the literal 0x1820 it adds up to, so a failure can say *which*
 * event type went missing instead of only that two hex numbers differ.
 */
object AccessibilityMasks {

    val EXPECTED_EVENT_TYPES: Int =
        AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    private val NAMED_BITS = listOf(
        AccessibilityEvent.TYPE_VIEW_SCROLLED to "typeViewScrolled",
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to "typeWindowContentChanged",
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED to "typeWindowStateChanged",
    )

    fun describe(mask: Int): String {
        if (mask == 0) return "(none)"
        val known = NAMED_BITS.filter { (bit, _) -> mask and bit != 0 }.map { it.second }
        val leftover = NAMED_BITS.fold(mask) { acc, (bit, _) -> acc and bit.inv() }
        val unknown = if (leftover != 0) listOf("0x${leftover.toString(16)}") else emptyList()
        return (known + unknown).joinToString()
    }
}

/**
 * Turns the real ScrollMonitorService on the way a real user does - by writing to
 * Settings.Secure - and reads back what the app itself thinks is happening.
 *
 * Shared by every instrumented test that needs a live accessibility service, because getting
 * this sequence wrong fails silently in ways that took this project several CI rounds to
 * diagnose (see ScrollMonitorServiceInstrumentedTest's class doc for the UiAutomation
 * suppression story).
 */
class AccessibilityServiceHarness {

    val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES is load-bearing, not a tweak: by default a
     * connected UiAutomation suppresses every other accessibility service on the device, and
     * AccessibilityManagerService enforces that by silently declining to bind them. Every
     * instrumented test holds a UiAutomation, so without this flag the test's own harness is
     * what prevents the service under test from ever starting.
     */
    val uiAutomation: UiAutomation = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /**
     * Note this resolves against the *target* package, so it is correct for whichever variant
     * is under test - `com.mindfulscroll.app.debug` or `com.mindfulscroll.app` for release.
     */
    val componentName: String =
        "${targetContext.packageName}/com.mindfulscroll.app.accessibility.ScrollMonitorService"

    /**
     * Wrapped so the failure names itself. On the release variant this is the first thing that
     * touches the minified app, so it is where a stripped class or a broken Hilt graph shows up -
     * and a bare NoClassDefFoundError from inside Dagger's generated code says nothing about
     * which of those it was, or that R8 is even involved.
     */
    private val entryPoint: DiagnosticsEntryPoint = try {
        EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            DiagnosticsEntryPoint::class.java,
        )
    } catch (error: Throwable) {
        throw AssertionError(
            "Could not read DiagnosticsEntryPoint out of the app's Hilt graph. On the release " +
                "variant this usually means R8: either the entry point itself was stripped " +
                "(check the keep rules in proguard-rules.pro) or a class only the test apk " +
                "calls was removed from the app apk, which is the recurring app/test seam - see " +
                "proguard-rules-instrumentation.pro. Underlying failure: " +
                "${error.javaClass.name}: ${error.message}",
            error,
        )
    }

    val diagnostics: ServiceDiagnostics = entryPoint.serviceDiagnostics()

    val overlayController: OverlayController = entryPoint.overlayController()

    /** @return true if the service connected within [timeoutMillis]. */
    fun enableServiceAndAwaitConnection(timeoutMillis: Long = 30_000): Boolean {
        // Service list first, master switch last - the write to accessibility_enabled is what
        // should trigger AccessibilityManagerService to (re)read the service list, so the list
        // needs to already be correct when that happens.
        shell("settings put secure enabled_accessibility_services $componentName")
        shell("settings put secure accessibility_enabled 1")
        Log.i(
            TAG,
            "After writing settings - enabled_accessibility_services=" +
                "\"${shell("settings get secure enabled_accessibility_services")}\" " +
                "accessibility_enabled=\"${shell("settings get secure accessibility_enabled")}\" " +
                "(expected component: $componentName)",
        )
        return pollUntil(timeoutMillis, "connect") { diagnostics.state.value.isServiceConnected }
    }

    fun disableService() {
        shell("settings put secure enabled_accessibility_services ''")
    }

    /**
     * Brings MainActivity to the foreground so the service sees a TYPE_WINDOW_STATE_CHANGED event.
     *
     * Deliberately `am start` rather than ActivityScenario. ActivityScenario pulls androidx.test's
     * whole runtime across the same app/test-apk seam that has already cost this suite two rounds
     * of NoClassDefFoundError on the release variant (androidx.tracing.Trace, then kotlin.LazyKt):
     * anything it needs has to survive the APP's R8 pass, and the app has no reason to keep it.
     * A shell launch depends on nothing but the activity being exported, and it is also closer to
     * how the activity really starts on a device.
     *
     * The class name is fully qualified and the package comes from targetContext, because the
     * debug variant carries an applicationIdSuffix while the class name does not move.
     */
    fun launchMainActivity(): String =
        shell("am start -W -n ${targetContext.packageName}/com.mindfulscroll.app.MainActivity")

    /** Sends the activity to the background again, so one test does not leave state for the next. */
    fun pressHome() {
        shell("input keyevent KEYCODE_HOME")
    }

    /** Runs a shell command and blocks until its output stream is drained, i.e. it completed. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .trim()

    /**
     * AccessibilityManagerService's whole picture: bound services, and any UiAutomation service
     * sitting alongside ours. Distinguishes "never registered", "registered but not bound" and
     * "suppressed" directly instead of leaving them to be inferred.
     */
    fun dumpsysAccessibility(): String = shell("dumpsys accessibility")

    /** The system's own parsed view of every enabled service - the mask AMS dispatches against. */
    fun describeEnabledServices(): String {
        val manager = targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (services.isEmpty()) return "<none>"
        return services.joinToString(separator = "\n") { info ->
            "  ${info.id}: eventTypes=0x${info.eventTypes.toString(16)} " +
                "feedbackType=0x${info.feedbackType.toString(16)} flags=0x${info.flags.toString(16)} " +
                "packageNames=${info.packageNames?.toList()}"
        }
    }

    fun pollUntil(
        timeoutMillis: Long,
        logTag: String,
        intervalMillis: Long = 200,
        condition: () -> Boolean,
    ): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMillis
        var lastLoggedSecond = -1L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                Log.i(TAG, "[$logTag] condition met after ${System.currentTimeMillis() - start}ms")
                return true
            }
            val elapsedSeconds = (System.currentTimeMillis() - start) / 1000
            if (elapsedSeconds != lastLoggedSecond && elapsedSeconds % 2 == 0L) {
                lastLoggedSecond = elapsedSeconds
                Log.i(TAG, "[$logTag] still waiting at ${elapsedSeconds}s - state=${diagnostics.state.value}")
            }
            Thread.sleep(intervalMillis)
        }
        return condition()
    }
}
