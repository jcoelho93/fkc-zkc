package com.mindfulscroll.app.grayscale

/**
 * What the system's colour-correction ("daltonizer") settings say right now, read back from
 * Settings.Secure. [mode] is null when the setting has never been written on this device.
 */
data class DaltonizerState(val enabled: Boolean, val mode: Int?) {
    /** Exactly what this app writes: colour correction on, in monochromacy (grayscale) mode. */
    val isGrayscale: Boolean get() = enabled && mode == DaltonizerState.MODE_GRAYSCALE

    companion object {
        /** AccessibilityManager's DALTONIZER_SIMULATE_MONOCHROMACY: a true luminance grayscale. */
        const val MODE_GRAYSCALE = 0

        val GRAYSCALE = DaltonizerState(enabled = true, mode = MODE_GRAYSCALE)
    }
}

/**
 * Persisted proof that the grayscale on screen is ours, and what to put back. Its presence is the
 * "we applied it" flag: if the service dies with the screen gray, this survives, and the next
 * connect (or boot) can tell our grayscale from the user's own colour correction.
 */
data class AppliedGrayscale(val previousMode: Int?)

/** Why nothing was written, so Diagnostics can name it instead of just showing colour. */
enum class GrayscaleSkipReason {
    /** WRITE_SECURE_SETTINGS has not been granted over adb, so the feature is inert. */
    PERMISSION_MISSING,

    /** The user already has colour correction on. It is theirs, and is never overwritten. */
    COLOR_CORRECTION_ALREADY_ON,

    /**
     * We applied grayscale, it should come off now, and the permission to do so has been revoked
     * since. The screen stays gray until colour correction is turned off by hand - which is why
     * this is surfaced as an error, not a quiet skip.
     */
    PERMISSION_REVOKED_WHILE_APPLIED,
}

sealed interface GrayscaleDecision {
    /** Write grayscale on, recording [previousMode] first so it can be put back. */
    data class Apply(val previousMode: Int?) : GrayscaleDecision

    /** Turn colour correction off and put the mode back to what it was before we changed it. */
    data class Restore(val previousMode: Int?) : GrayscaleDecision

    /**
     * Our flag is set but the screen no longer shows what we wrote: someone changed colour
     * correction while it was on (or the write never landed). Clear the flag and write nothing -
     * whatever is there now is not ours to undo.
     */
    data object Forget : GrayscaleDecision

    data class Skip(val reason: GrayscaleSkipReason) : GrayscaleDecision

    data object NoChange : GrayscaleDecision
}

/**
 * The whole apply/restore decision, with no Android in it, so every branch is unit-tested.
 *
 * The rules, in the order they matter:
 *
 *  1. **Never overwrite the user's own colour correction.** If it is on and we did not turn it on,
 *     it is left exactly as it is.
 *  2. **Only undo what is still ours.** Restoring means turning colour correction off; if the
 *     screen no longer shows the exact grayscale we wrote, someone else has changed it since, and
 *     turning it off would destroy their setting.
 *  3. **A flag that survived a crash is acted on** like any other: it is the reason the flag is
 *     persisted rather than held in memory.
 */
object GrayscalePolicy {

    /**
     * @param wantGrayscale whether the foreground app is monitored with grayscale switched on.
     *   Pass false to mean "put everything back" (service stopping, connecting, boot).
     * @param applied our persisted record, or null if we believe we have not applied anything.
     */
    fun decide(
        wantGrayscale: Boolean,
        permissionGranted: Boolean,
        system: DaltonizerState,
        applied: AppliedGrayscale?,
    ): GrayscaleDecision {
        if (applied != null) {
            if (!system.isGrayscale) return GrayscaleDecision.Forget
            if (wantGrayscale) return GrayscaleDecision.NoChange
            if (!permissionGranted) return GrayscaleDecision.Skip(GrayscaleSkipReason.PERMISSION_REVOKED_WHILE_APPLIED)
            return GrayscaleDecision.Restore(applied.previousMode)
        }

        if (!wantGrayscale) return GrayscaleDecision.NoChange
        if (!permissionGranted) return GrayscaleDecision.Skip(GrayscaleSkipReason.PERMISSION_MISSING)
        if (system.enabled) return GrayscaleDecision.Skip(GrayscaleSkipReason.COLOR_CORRECTION_ALREADY_ON)
        return GrayscaleDecision.Apply(previousMode = system.mode)
    }
}
