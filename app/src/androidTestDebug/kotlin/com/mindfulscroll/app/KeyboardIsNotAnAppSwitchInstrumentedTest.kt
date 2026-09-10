package com.mindfulscroll.app

import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Opening the keyboard inside a monitored app must not read as leaving it.
 *
 * The keyboard is its own window and raises TYPE_WINDOW_STATE_CHANGED under its own package, while
 * closing it raises nothing for the app underneath. Treated as a foreground change, one typed
 * comment ended the session, cancelled the time-threshold timer and hid the intention prompt, and
 * every scroll after it was dropped until the app happened to open another window. Nothing
 * failed, and every counter looked healthy.
 *
 * Settings search stands in for a feed with a text box, and tapping its search field opens the
 * real system keyboard. It's the settings.intelligence package, not Settings itself, so that's
 * the package being monitored.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardIsNotAnAppSwitchInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness
    private val monitoredPackage = "com.android.settings.intelligence"

    @Before
    fun setUp() {
        harness = AccessibilityServiceHarness()
        val entryPoint = EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        )
        entryPoint.appSettings().setIntentionCaptureEnabled(true)
        runBlocking {
            entryPoint.monitoredAppRepository().applySelection(
                listOf(
                    MonitoredAppEntity(
                        packageName = monitoredPackage,
                        appLabel = "Settings search",
                        isMonitored = true,
                        addedAtMillis = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        if (!::harness.isInitialized) return
        harness.shell("input keyevent KEYCODE_BACK")
        harness.pressHome()
        harness.disableService()
    }

    @Test
    fun keyboardOpeningInAMonitoredAppKeepsTheSessionAndThePrompt() {
        assertTrue("service never connected", harness.enableServiceAndAwaitConnection())
        assertTrue(
            "service never picked up $monitoredPackage as monitored",
            harness.pollUntil(10_000, "monitored-list") {
                monitoredPackage in harness.diagnostics.state.value.monitoredPackages
            },
        )

        val renderedBefore = harness.diagnostics.state.value.intentionPromptsRenderedCount
        Log.i(TAG, "launching search: " + harness.shell("am start -W --activity-clear-task -a com.android.settings.action.SETTINGS_SEARCH"))

        assertTrue(
            "the monitored app never became the active session, so there is nothing for the " +
                "keyboard to break. state=${harness.diagnostics.state.value}",
            harness.pollUntil(15_000, "session-started") {
                harness.diagnostics.state.value.activeSessionPackage == monitoredPackage
            },
        )
        assertTrue(
            "the intention prompt never drew over the monitored app. state=${harness.diagnostics.state.value}",
            harness.pollUntil(15_000, "prompt-render") {
                harness.diagnostics.state.value.intentionPromptsRenderedCount > renderedBefore
            },
        )

        // The keyboard under test is summoned by hand, once everything else has settled, rather
        // than taken from the one the search field opens on launch. With notificationTimeout=100
        // the system delivers only the LAST event of a type within each 100 ms, so a keyboard
        // appearing in the same instant as the prompt's own window is simply never delivered.
        // Harmless in production, since an undelivered event cannot cause a false app switch,
        // but it made this test pass alone and fail in the full suite depending on timing.
        //
        // The launch keyboard's timing varies (it came up after the prompt on CI, before it
        // locally), so wait for it to appear, then close it, rather than checking once. If it
        // never appears, that's fine too: the point is only that none is up when the field is
        // tapped.
        harness.pollUntil(LAUNCH_KEYBOARD_WAIT_MILLIS, "launch-keyboard") { keyboardShown() }
        val closed = harness.pollUntil(10_000, "keyboard-hidden-first", intervalMillis = 1_000) {
            if (keyboardShown()) harness.shell("input keyevent KEYCODE_BACK")
            !keyboardShown()
        }
        assertTrue(
            "The keyboard would not close, so its opening could not be observed. " +
                "dumpsys input_method:\n${harness.shell("dumpsys input_method")}",
            closed,
        )
        Thread.sleep(SETTLE_MILLIS)

        val keyboardEventsBefore = harness.diagnostics.state.value.keyboardWindowEventsIgnored
        val field = harness.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(SEARCH_FIELD_ID)
            ?.firstOrNull()
        assertTrue(
            "Could not find the search field to tap. Windows and texts:\n${harness.describeWindowsAndTexts()}",
            field != null,
        )
        val bounds = Rect().also { field!!.getBoundsInScreen(it) }
        harness.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

        // The precondition, asserted rather than assumed: if no keyboard ever appears, a passing
        // test below would prove nothing at all.
        assertTrue(
            "The system keyboard never opened over Settings search, so this test cannot exercise " +
                "what it exists for. dumpsys input_method:\n${harness.shell("dumpsys input_method")}",
            harness.pollUntil(10_000, "keyboard-shown") { keyboardShown() },
        )
        // Sync point: wait for the keyboard's window event to have been handled one way or the
        // other - set aside (fixed) or taken as an app switch (the bug) - before judging it.
        harness.pollUntil(5_000, "keyboard-event-handled") {
            val s = harness.diagnostics.state.value
            s.keyboardWindowEventsIgnored > keyboardEventsBefore || s.currentForegroundPackage != monitoredPackage
        }

        val state = harness.diagnostics.state.value
        assertEquals(
            "The keyboard was read as leaving the app: foreground moved off the monitored package. " +
                "log=${state.recentLog}",
            monitoredPackage,
            state.currentForegroundPackage,
        )
        assertEquals(
            "The keyboard ended the monitored app's session, which also cancels its time threshold. " +
                "log=${state.recentLog}",
            monitoredPackage,
            state.activeSessionPackage,
        )
        val prompt = EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        ).intentionPromptController()
        assertTrue(
            "The keyboard opening took the intention prompt down with it. log=${state.recentLog}",
            prompt.isShowing(),
        )
        // Last, because on its own it only proves the event was recognised; the three above are
        // what the user would actually have lost.
        assertTrue(
            "The keyboard opened, but its window event was never seen and set aside - either it " +
                "raised no TYPE_WINDOW_STATE_CHANGED here, or it no longer matches the keyboard " +
                "window class. log=${state.recentLog}",
            state.keyboardWindowEventsIgnored > keyboardEventsBefore,
        )
    }

    private fun keyboardShown(): Boolean = harness.shell("dumpsys input_method").contains("mInputShown=true")

    private companion object {
        const val TAG = "KeyboardSwitchTest"

        const val SEARCH_FIELD_ID = "android:id/search_src_text"

        /** Well past notificationTimeout, so no other window event is pending when the keyboard opens. */
        const val SETTLE_MILLIS = 1_000L

        /** How long to give the search field's own keyboard to come up before closing it. */
        const val LAUNCH_KEYBOARD_WAIT_MILLIS = 5_000L
    }
}
