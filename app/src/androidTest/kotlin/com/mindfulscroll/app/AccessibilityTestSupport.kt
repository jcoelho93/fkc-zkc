package com.mindfulscroll.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        .also { automation ->
            // getWindows() returns an empty list unless the querying service asks for interactive
            // windows, and every window-level assertion here depends on it. Set centrally so a
            // test that forgets does not silently "find no windows" and conclude the UI is absent.
            automation.serviceInfo = automation.serviceInfo?.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }

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
        // Cleared first so what follows is always a real off -> on transition. Writing a value
        // that is already set changes nothing, and AccessibilityManagerService can then leave the
        // service sitting in "Binding services" forever - which surfaces as the maximally
        // unhelpful "service never connected" 30 seconds later. Whatever ran before this test,
        // whether another test or someone poking at the device by hand, must not be able to
        // decide whether this one works.
        shell("settings put secure accessibility_enabled 0")
        shell("settings delete secure enabled_accessibility_services")
        // Service list first, master switch last - the write to accessibility_enabled is what
        // triggers AccessibilityManagerService to (re)read the service list, so the list needs to
        // already be correct when that happens.
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
        // `settings put ... ''` fails outright with "Bad arguments" and silently leaves the
        // service enabled, so the next run inherits a device that looks configured but isn't.
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
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

    /**
     * Pins the screen on for tests that have to sit and wait out real elapsed time.
     *
     * Only ThresholdOverlayEndToEndInstrumentedTest needs this today, and it needs it badly: it
     * waits a full minute for a time threshold, which is comfortably longer than the emulator's
     * default screen-off timeout. A blanked screen ends the foreground session under the test, so
     * the failure would read as "the threshold never fired" - naming a bug in the code under test
     * for something the harness did.
     *
     * Paired with [releaseScreen] in teardown rather than left on, so one test cannot change how
     * the device behaves for every test after it.
     */
    fun keepScreenAwake() {
        shell("svc power stayon true")
    }

    fun releaseScreen() {
        shell("svc power stayon false")
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

    /**
     * The `mAttrs` line WindowManager holds for our own windows, which is where the layout flags
     * actually live. Reading them back from the system rather than from our own object is the
     * point: the flags are what decide whether the app underneath can still receive input.
     */
    fun ourWindowAttrs(): String =
        shell("dumpsys window windows")
            .lineSequence()
            .dropWhile { !it.contains(targetContext.packageName) }
            .take(12)
            .filter { it.contains("mAttrs=") || it.trimStart().startsWith("fl=") }
            .joinToString(" ")

    /**
     * Depth-first walk of every node in a window, so callers can search or describe with the same
     * traversal. Depth-capped as a cheap guard against a pathological tree.
     */
    private fun walkNodes(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 30) return
        visit(node)
        for (i in 0 until node.childCount) walkNodes(node.getChild(i), depth + 1, visit)
    }

    /**
     * Screen bounds of the first node whose text or content description contains [text], searched
     * across EVERY window.
     *
     * Two things this deliberately does not use:
     *
     *  - `uiautomator dump`, which only walks the *active* window. The intention prompt is
     *    FLAG_NOT_FOCUSABLE by design, so it is never active and a dump simply does not contain
     *    it - the search comes back empty and looks exactly like "the UI is not there".
     *  - `findAccessibilityNodeInfosByText`, which returned nothing for Compose nodes here even
     *    when a manual walk of the same tree found the text immediately.
     *
     * Note the asymmetry with screenshots: the accessibility tree CAN see
     * TYPE_ACCESSIBILITY_OVERLAY windows, while `screencap` composites surfaces and cannot. That
     * is what makes overlay UI testable by tapping even though it cannot be photographed.
     */
    fun findNodeBounds(text: String): Rect? {
        for (window in uiAutomation.windows) {
            var found: Rect? = null
            walkNodes(window.root) { node ->
                if (found == null) {
                    val label = "${node.text ?: ""} ${node.contentDescription ?: ""}"
                    if (label.contains(text, ignoreCase = true)) {
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        if (!bounds.isEmpty) found = bounds
                    }
                }
            }
            found?.let { return it }
        }
        return null
    }

    /**
     * Every window and the text of every node in it. For failure messages: "could not find the
     * chip" is useless on its own, because it cannot distinguish a wrong search string from a
     * window that is not being walked from a UI that never rendered.
     */
    fun describeWindowsAndTexts(): String = uiAutomation.windows.joinToString("\n") { window ->
        val texts = mutableListOf<String>()
        walkNodes(window.root) { node ->
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts += "cd:$it" }
        }
        "  window type=${window.type} pkg=${window.root?.packageName} texts=$texts"
    }

    /** @return false if no node with that text is on screen in any window. */
    fun tapNodeWithText(text: String): Boolean {
        val bounds = findNodeBounds(text) ?: return false
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        return true
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
