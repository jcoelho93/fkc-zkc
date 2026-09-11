package com.mindfulscroll.app.grayscale

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every branch of the apply/restore decision (#27). The daltonizer is display-wide and persistent,
 * so each wrong answer here is visible on the whole phone: a gray screen that never clears, or a
 * colour-blind user's own correction switched off by an app that never turned it on.
 */
class GrayscalePolicyTest {

    private val off = DaltonizerState(enabled = false, mode = null)
    private val offWithUserMode = DaltonizerState(enabled = false, mode = DEUTERANOMALY)
    private val userCorrectionOn = DaltonizerState(enabled = true, mode = DEUTERANOMALY)
    private val userGrayscaleOn = DaltonizerState.GRAYSCALE

    private fun decide(
        want: Boolean,
        system: DaltonizerState,
        applied: AppliedGrayscale? = null,
        permission: Boolean = true,
    ) = GrayscalePolicy.decide(want, permission, system, applied)

    // -- entering and leaving a grayscale app ----------------------------------------------------

    @Test
    fun `entering a grayscale app applies it and remembers the mode that was there`() {
        assertThat(decide(want = true, system = off)).isEqualTo(GrayscaleDecision.Apply(previousMode = null))
        assertThat(decide(want = true, system = offWithUserMode))
            .isEqualTo(GrayscaleDecision.Apply(previousMode = DEUTERANOMALY))
    }

    @Test
    fun `staying in the app, or a second event for it, changes nothing`() {
        assertThat(decide(want = true, system = DaltonizerState.GRAYSCALE, applied = AppliedGrayscale(null)))
            .isEqualTo(GrayscaleDecision.NoChange)
    }

    @Test
    fun `leaving restores the previous mode`() {
        assertThat(decide(want = false, system = DaltonizerState.GRAYSCALE, applied = AppliedGrayscale(DEUTERANOMALY)))
            .isEqualTo(GrayscaleDecision.Restore(previousMode = DEUTERANOMALY))
        assertThat(decide(want = false, system = DaltonizerState.GRAYSCALE, applied = AppliedGrayscale(null)))
            .isEqualTo(GrayscaleDecision.Restore(previousMode = null))
    }

    @Test
    fun `apps without grayscale leave everything alone`() {
        assertThat(decide(want = false, system = off)).isEqualTo(GrayscaleDecision.NoChange)
        assertThat(decide(want = false, system = userCorrectionOn)).isEqualTo(GrayscaleDecision.NoChange)
    }

    // -- the user's own colour correction -------------------------------------------------------

    @Test
    fun `colour correction the user already had on is never overwritten`() {
        assertThat(decide(want = true, system = userCorrectionOn))
            .isEqualTo(GrayscaleDecision.Skip(GrayscaleSkipReason.COLOR_CORRECTION_ALREADY_ON))
    }

    @Test
    fun `grayscale the user set themselves is theirs, not ours, and is never turned off`() {
        // Identical on screen to what we write - only our record tells the two apart.
        assertThat(decide(want = true, system = userGrayscaleOn))
            .isEqualTo(GrayscaleDecision.Skip(GrayscaleSkipReason.COLOR_CORRECTION_ALREADY_ON))
        assertThat(decide(want = false, system = userGrayscaleOn)).isEqualTo(GrayscaleDecision.NoChange)
    }

    @Test
    fun `a setting changed while ours was on is forgotten, not undone`() {
        // The user switched to their own correction mode while grayscale was on...
        assertThat(decide(want = false, system = userCorrectionOn, applied = AppliedGrayscale(null)))
            .isEqualTo(GrayscaleDecision.Forget)
        // ...or turned colour correction off by hand.
        assertThat(decide(want = false, system = off, applied = AppliedGrayscale(null)))
            .isEqualTo(GrayscaleDecision.Forget)
        // Even while still in the app: re-applying over their change would overwrite it.
        assertThat(decide(want = true, system = userCorrectionOn, applied = AppliedGrayscale(null)))
            .isEqualTo(GrayscaleDecision.Forget)
    }

    // -- crash recovery -------------------------------------------------------------------------

    @Test
    fun `a record that survived the service dying with the screen gray is restored`() {
        // What restoreIfApplied() sees on the next connect or boot: our record, the screen still gray.
        assertThat(decide(want = false, system = DaltonizerState.GRAYSCALE, applied = AppliedGrayscale(DEUTERANOMALY)))
            .isEqualTo(GrayscaleDecision.Restore(previousMode = DEUTERANOMALY))
    }

    @Test
    fun `a record left by dying between recording and writing is cleared without writing`() {
        // The record is written before the setting, so a death in between leaves a record and no
        // grayscale. Nothing on screen is ours, so nothing is written.
        assertThat(decide(want = false, system = offWithUserMode, applied = AppliedGrayscale(DEUTERANOMALY)))
            .isEqualTo(GrayscaleDecision.Forget)
    }

    // -- permission ------------------------------------------------------------------------------

    @Test
    fun `without the permission nothing is attempted`() {
        assertThat(decide(want = true, system = off, permission = false))
            .isEqualTo(GrayscaleDecision.Skip(GrayscaleSkipReason.PERMISSION_MISSING))
        assertThat(decide(want = false, system = off, permission = false)).isEqualTo(GrayscaleDecision.NoChange)
    }

    @Test
    fun `permission revoked while grayscale is on is reported, since it can no longer be undone`() {
        assertThat(
            decide(want = false, system = DaltonizerState.GRAYSCALE, applied = AppliedGrayscale(null), permission = false),
        ).isEqualTo(GrayscaleDecision.Skip(GrayscaleSkipReason.PERMISSION_REVOKED_WHILE_APPLIED))
    }

    @Test
    fun `isGrayscale means exactly on and monochromacy`() {
        assertThat(DaltonizerState(enabled = true, mode = 0).isGrayscale).isTrue()
        assertThat(DaltonizerState(enabled = false, mode = 0).isGrayscale).isFalse()
        assertThat(DaltonizerState(enabled = true, mode = null).isGrayscale).isFalse()
        assertThat(DaltonizerState(enabled = true, mode = DEUTERANOMALY).isGrayscale).isFalse()
    }

    private companion object {
        /** AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY, the platform's default correction. */
        const val DEUTERANOMALY = 12
    }
}
