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
 * End-to-end for intention capture: a monitored app comes to the foreground and the prompt has to
 * actually appear over it.
 *
 * Asserts on the prompt having DRAWN, not on show() returning true, for the reason the whole
 * OverlayRenderInstrumentedTest exists: a window can be accepted by WindowManager and then never
 * render, and the two are indistinguishable from the caller. This one has a second failure mode
 * that matters just as much - the prompt is deliberately non-focusable and non-touch-modal so the
 * user can scroll straight past it, and a window with those flags that is also mis-sized or
 * mis-positioned is invisible in a way no counter would show.
 *
 * The Settings app stands in for a feed app: what the service reacts to is a
 * TYPE_WINDOW_STATE_CHANGED from a package it has been told to monitor, and nothing about that
 * cares which app it is.
 */
@RunWith(AndroidJUnit4::class)
class IntentionPromptInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness
    private val monitoredPackage = "com.android.settings"

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
                        appLabel = "Settings",
                        isMonitored = true,
                        addedAtMillis = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        // Guarded: a throwing @After replaces the @Before failure that caused it, and the setUp
        // failure is always the one worth reading.
        if (!::harness.isInitialized) return
        // Restored because the toggle test switches this OFF and SharedPreferences outlive the
        // process. Leaving it off silently disables the feature for every later test and for
        // anyone poking at the app on this device afterwards - which is exactly how a screenshot
        // of "the prompt doesn't appear" gets mistaken for a bug in the prompt.
        EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        ).appSettings().setIntentionCaptureEnabled(true)
        harness.pressHome()
        harness.disableService()
    }

    @Test
    fun openingAMonitoredAppShowsAndRendersTheIntentionPrompt() {
        val connected = harness.enableServiceAndAwaitConnection()
        assertTrue(
            "ScrollMonitorService never connected, so nothing about the prompt can be tested. " +
                "dumpsys:\n${if (connected) "" else harness.dumpsysAccessibility()}",
            connected,
        )

        // The service only prompts for packages it has been told to monitor, and it learns that
        // list reactively from Room - so wait for it to have arrived rather than racing it.
        val sawMonitoredList = harness.pollUntil(10_000, "monitored-list") {
            monitoredPackage in harness.diagnostics.state.value.monitoredPackages
        }
        assertTrue(
            "The service never picked up $monitoredPackage as monitored; it saw " +
                "${harness.diagnostics.state.value.monitoredPackages}",
            sawMonitoredList,
        )

        val before = harness.diagnostics.state.value.intentionPromptsRenderedCount
        Log.i(TAG, "launching $monitoredPackage: " + harness.shell("am start -W -a android.settings.SETTINGS"))

        val rendered = harness.pollUntil(15_000, "intention-prompt-render") {
            harness.diagnostics.state.value.intentionPromptsRenderedCount > before
        }
        val state = harness.diagnostics.state.value
        Log.i(TAG, "prompt state: added=${state.intentionPromptsShownCount} " +
            "rendered=${state.intentionPromptsRenderedCount} render=${state.lastIntentionPromptRender} " +
            "error=${state.lastIntentionPromptError}")

        assertTrue(
            "The intention prompt never drew a frame after $monitoredPackage came to the " +
                "foreground. added=${state.intentionPromptsShownCount}, " +
                "rendered=${state.intentionPromptsRenderedCount}, " +
                "lastRender=${state.lastIntentionPromptRender}, " +
                "lastError=${state.lastIntentionPromptError}, log=${state.recentLog}",
            rendered,
        )

        // The prompt must be a strip, not a full-screen window: it is explicitly allowed to be
        // ignored, and one that covered the feed would be the friction this feature is designed
        // not to add. Its own render record carries the measured size.
        val render = state.lastIntentionPromptRender.orEmpty()
        val height = Regex("""\d+x(\d+),""").find(render)?.groupValues?.get(1)?.toIntOrNull()
        assertTrue("could not read a height out of the render record \"$render\"", height != null)
        val screenHeight = harness.shell("wm size").substringAfterLast('x').trim().toIntOrNull() ?: 0
        assertTrue(
            "The prompt drew ${height}px tall against a ${screenHeight}px screen - that is not a " +
                "small strip, and a prompt that covers the feed is exactly the friction this " +
                "feature must not add. Render record: $render",
            screenHeight > 0 && height!! < screenHeight / 2,
        )
    }

    @Test
    fun intentionCaptureToggleSuppressesThePrompt() {
        EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        ).appSettings().setIntentionCaptureEnabled(false)

        assertTrue("service never connected", harness.enableServiceAndAwaitConnection())
        assertTrue(
            "service never picked up the monitored list",
            harness.pollUntil(10_000, "monitored-list") {
                monitoredPackage in harness.diagnostics.state.value.monitoredPackages
            },
        )

        val before = harness.diagnostics.state.value.intentionPromptsShownCount
        harness.shell("am start -W -a android.settings.SETTINGS")

        // Deliberately asserting a NON-event, so it waits long enough to be meaningful: the
        // positive case above renders within a couple of seconds.
        val appeared = harness.pollUntil(6_000, "prompt-should-not-appear") {
            harness.diagnostics.state.value.intentionPromptsShownCount > before
        }
        assertEquals(
            "The prompt appeared even though intention capture is switched off. A settings toggle " +
                "that does not actually stop the thing it names is worse than not having it. " +
                "Log: ${harness.diagnostics.state.value.recentLog}",
            false,
            appeared,
        )
    }

    /**
     * The regression guard for the free-text path, which had no coverage at all - which is
     * precisely how the bug it covers got in.
     *
     * Tapping "Checking something specific" makes the window focusable so the keyboard can open.
     * That same tap used to CANCEL the dismiss timer rather than restart it, so a prompt the user
     * then walked away from stayed on screen forever, holding keyboard focus away from the app
     * underneath. On the one path where the user was being more deliberate, not less.
     *
     * Asserts both halves: that the window really does become focusable (otherwise the free-text
     * path is silently broken), and that it still takes itself down afterwards.
     */
    @Test
    fun abandonedFreeTextPromptStillDismissesItself() {
        assertTrue("service never connected", harness.enableServiceAndAwaitConnection())
        assertTrue(
            "service never picked up the monitored list",
            harness.pollUntil(10_000, "monitored-list") {
                monitoredPackage in harness.diagnostics.state.value.monitoredPackages
            },
        )

        // From the debug-only entry point, not DiagnosticsEntryPoint: that one lives in `main` so
        // the release variant can be instrumented, and everything it exposes has to earn an R8
        // keep rule. This test is debug-only, so it should not widen that surface.
        val prompt = EntryPointAccessors.fromApplication(
            harness.targetContext.applicationContext,
            TestRepositoryEntryPoint::class.java,
        ).intentionPromptController()

        val renderedBefore = harness.diagnostics.state.value.intentionPromptsRenderedCount
        var added = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            added = prompt.show(
                appLabel = "Test app",
                onAnswer = { _, _ -> },
                onDismiss = { prompt.hide() },
                // Long enough that the untouched timer cannot be what dismisses this, short enough
                // that the engaged one can be waited out in a test.
                idleDismissMillis = 60_000L,
                engagedIdleDismissMillis = 4_000L,
            )
        }
        assertTrue("prompt window was not added: ${prompt.lastFailureReason}", added)
        assertTrue(
            "prompt never drew a frame",
            harness.pollUntil(10_000, "prompt-render") {
                harness.diagnostics.state.value.intentionPromptsRenderedCount > renderedBefore
            },
        )

        val flagsBefore = harness.ourWindowAttrs()
        Log.i(TAG, "window attrs before tapping the free-text chip: $flagsBefore")
        assertTrue(
            "The prompt must start NOT_FOCUSABLE - it appears on every app open and must never " +
                "take input away from the app underneath. WindowManager reported: $flagsBefore",
            flagsBefore.contains("NOT_FOCUSABLE"),
        )

        assertTrue(
            "Could not find the free-text chip to tap. Windows and node texts actually visible:\n" +
                harness.describeWindowsAndTexts(),
            harness.tapNodeWithText("Checking something specific"),
        )

        val becameFocusable = harness.pollUntil(5_000, "window-focusable") {
            !harness.ourWindowAttrs().contains("NOT_FOCUSABLE")
        }
        assertTrue(
            "Tapping the free-text chip did not make the window focusable, so the keyboard could " +
                "never open and the free-text path is broken. Attrs: ${harness.ourWindowAttrs()}",
            becameFocusable,
        )

        // The actual regression: engaging must RESTART the dismiss timer, never remove it.
        val dismissed = harness.pollUntil(20_000, "engaged-auto-dismiss") {
            !prompt.isShowing()
        }
        assertTrue(
            "The prompt was engaged and then abandoned, and never took itself down. It is holding " +
                "keyboard focus away from the app underneath with no timeout left to remove it. " +
                "Attrs: ${harness.ourWindowAttrs()}",
            dismissed,
        )
    }

    private companion object {
        const val TAG = "IntentionPromptTest"
    }
}
