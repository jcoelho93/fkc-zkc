package com.mindfulscroll.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.overlay.OverlayUiState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mindful pause's *content* (#5), on a real device, in the real accessibility-service window.
 *
 * OverlayRenderInstrumentedTest already proves that window appears at all, and is deliberately
 * kept content-agnostic so it can run on the release variant. This one is about what is written
 * inside it, and lives in androidTestDebug because it asserts on copy that R8 has no bearing on.
 *
 * It reads the screen through the accessibility tree rather than a screenshot, for the reason
 * OverlayPreviewActivity exists: `adb screencap` cannot capture TYPE_ACCESSIBILITY_OVERLAY windows
 * at all, so a screenshot of a perfectly rendered pause screen is blank. The tree can see it.
 */
@RunWith(AndroidJUnit4::class)
class PauseScreenInstrumentedTest {

    private lateinit var harness: AccessibilityServiceHarness

    @Before
    fun setUp() {
        harness = AccessibilityServiceHarness()
    }

    @After
    fun tearDown() {
        // Guarded: a throwing @After replaces the @Before failure that caused it.
        if (!::harness.isInitialized) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { harness.overlayController.hide() }
        harness.disableService()
    }

    private fun showPause(state: OverlayUiState) {
        assertTrue(
            "ScrollMonitorService never connected, so OverlayController was never handed the " +
                "service context that carries the window token. dumpsys:\n" +
                harness.dumpsysAccessibility(),
            harness.enableServiceAndAwaitConnection(),
        )
        // Baseline, not `> 0`: the counter is cumulative across the process, so an earlier test
        // in this class having rendered would make this poll pass for a window that never drew.
        val renderedBefore = harness.diagnostics.state.value.overlaysRenderedCount
        var added = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            added = harness.overlayController.show(
                state = state,
                onCloseApp = {},
                onContinue = {},
                onOutcome = {},
            )
        }
        assertTrue(
            "overlay window was not added: ${harness.overlayController.lastFailureReason}",
            added,
        )
        val drew = harness.pollUntil(10_000, "pause-render") {
            harness.diagnostics.state.value.overlaysRenderedCount > renderedBefore
        }
        assertTrue(
            "the pause screen never drew a frame. lastOverlayRender=" +
                "${harness.diagnostics.state.value.lastOverlayRender}, lastOverlayError=" +
                "${harness.diagnostics.state.value.lastOverlayError}. Windows:\n" +
                harness.describeWindowsAndTexts(),
            drew,
        )
    }

    /**
     * The invariant this feature lives or dies by: both exits are on screen from the first frame,
     * while the urge-surfing phase is still running.
     *
     * If this ever fails, the pause has silently become the countdown gate again under nicer
     * wording - which is the exact design #5 exists to remove, and nothing else in the suite would
     * notice.
     */
    @Test
    fun bothExitsAreOnScreenBeforeThePauseIntervalElapses() {
        // Long enough that the interval cannot possibly have elapsed by the time we look.
        showPause(pauseState(pauseDurationSeconds = 600))

        val windows = harness.describeWindowsAndTexts()
        Log.i(TAG, "windows during the urge-surfing phase:\n$windows")

        assertTrue(
            "\"Close Instagram\" is not on screen during the urge-surfing phase, so the exits are " +
                "being gated behind the pause interval. Windows and texts:\n$windows",
            harness.findNodeBounds("Close Instagram") != null,
        )
        assertTrue(
            "\"Keep scrolling\" is not on screen during the urge-surfing phase, so continuing is " +
                "being gated behind the pause interval - which is the countdown this screen " +
                "replaced. Windows and texts:\n$windows",
            harness.findNodeBounds("Keep scrolling") != null,
        )

        // And the recall genuinely has not appeared yet, so the assertion above is about the
        // urge-surfing phase rather than a screen that had already moved on.
        assertFalse(
            "the outcome question appeared immediately, so this test was not measuring what it " +
                "claims to measure. Windows and texts:\n$windows",
            harness.findNodeBounds("Did you get it?") != null,
        )
    }

    /** The urge-surfing copy itself, which is the whole content of the first phase. */
    @Test
    fun theUrgeSurfingPromptIsRendered() {
        showPause(pauseState(pauseDurationSeconds = 600))

        assertTrue(
            "the urge-surfing prompt is missing. Windows and texts:\n" +
                harness.describeWindowsAndTexts(),
            harness.findNodeBounds("Notice the urge") != null,
        )
        assertTrue(
            "the session context line is missing. Windows and texts:\n" +
                harness.describeWindowsAndTexts(),
            harness.findNodeBounds("11 min in Instagram") != null,
        )
    }

    /** Step 2: the intention captured at open is quoted back, with the outcome question. */
    @Test
    fun theIntentionIsRecalledAfterThePauseInterval() {
        showPause(pauseState(pauseDurationSeconds = 0, intentionKind = IntentionKind.CONNECTION))

        val appeared = harness.pollUntil(10_000, "recall") {
            harness.findNodeBounds("Did you get it?") != null
        }
        assertTrue(
            "the outcome question never appeared. Windows and texts:\n" +
                harness.describeWindowsAndTexts(),
            appeared,
        )
        assertTrue(
            "the intention was not quoted back to the user. Windows and texts:\n" +
                harness.describeWindowsAndTexts(),
            harness.findNodeBounds("You opened it for connection") != null,
        )
        // All three, because a two-way split would push people towards whichever end felt less
        // like an admission - see PauseOutcome.
        for (label in listOf("Yes", "Kind of", "Not really")) {
            assertTrue(
                "outcome chip \"$label\" is missing. Windows and texts:\n" +
                    harness.describeWindowsAndTexts(),
                harness.findNodeBounds(label) != null,
            )
        }
    }

    /**
     * The degradation path, which is the easiest thing here to get wrong and never notice: with no
     * intention for the session the pause must still work, just without step 2. A crash or a blank
     * "You opened it for null" would only show up for users who dismissed the prompt or switched
     * capture off - never for whoever tested it.
     */
    @Test
    fun withNoIntentionThePauseStillWorksAndAsksNothing() {
        showPause(pauseState(pauseDurationSeconds = 0, intentionKind = null))

        // Waited out rather than checked instantly, so this is "it never appears" and not merely
        // "it had not appeared yet".
        val recallAppeared = harness.pollUntil(6_000, "recall-should-not-appear") {
            harness.findNodeBounds("Did you get it?") != null
        }
        val windows = harness.describeWindowsAndTexts()
        assertFalse(
            "The pause asked whether the user got what they came for even though no intention was " +
                "ever recorded for this session - so it is asking about something nobody said. " +
                "Windows and texts:\n$windows",
            recallAppeared,
        )
        assertTrue(
            "the urge-surfing prompt is missing, so the pause did not degrade cleanly - it broke. " +
                "Windows and texts:\n$windows",
            harness.findNodeBounds("Notice the urge") != null,
        )
        assertTrue(
            "the exits are missing with no intention. Windows and texts:\n$windows",
            harness.findNodeBounds("Keep scrolling") != null,
        )
    }

    private fun pauseState(
        pauseDurationSeconds: Int,
        intentionKind: IntentionKind? = IntentionKind.CONNECTION,
    ) = OverlayUiState(
        appLabel = "Instagram",
        scrollCount = 63,
        sessionMinutes = 11,
        intentionKind = intentionKind,
        pauseDurationSeconds = pauseDurationSeconds,
    )

    private companion object {
        const val TAG = "PauseScreenTest"
    }
}
