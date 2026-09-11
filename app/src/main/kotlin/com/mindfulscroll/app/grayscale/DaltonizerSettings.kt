package com.mindfulscroll.app.grayscale

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The only code that touches the system's colour-correction settings. Grayscale is the platform's
 * own daltonizer in monochromacy mode: the spike on #27 established that a third-party app cannot
 * desaturate another app's window any other way (an overlay can only dim or tint, and per-app
 * saturation is signature-only).
 *
 * Writing these needs WRITE_SECURE_SETTINGS, which Android only grants over adb. Every write can
 * therefore throw SecurityException; callers route that to Diagnostics rather than catching it here.
 */
object DaltonizerSettings {

    /** Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED - hidden in the SDK, stable since API 19. */
    const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"

    /** Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER - the correction mode. */
    const val KEY_MODE = "accessibility_display_daltonizer"

    fun isPermissionGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** The adb command that turns the feature on, for this exact package (debug builds differ). */
    fun grantCommand(packageName: String): String =
        "adb shell pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    fun read(resolver: ContentResolver): DaltonizerState = DaltonizerState(
        enabled = Settings.Secure.getInt(resolver, KEY_ENABLED, 0) == 1,
        mode = Settings.Secure.getString(resolver, KEY_MODE)?.toIntOrNull(),
    )

    /**
     * Mode first, then the switch, so colour correction never comes on in the user's old mode.
     *
     * The mode is always written and read as a string. That is how Settings.Secure stores every
     * value anyway, and it keeps one code path for "set to a number" and "cleared" in [restore].
     */
    fun writeGrayscale(resolver: ContentResolver) {
        Settings.Secure.putString(resolver, KEY_MODE, DaltonizerState.MODE_GRAYSCALE.toString())
        Settings.Secure.putInt(resolver, KEY_ENABLED, 1)
    }

    /**
     * Switch off first, then the mode, for the mirror-image reason. A [previousMode] of null means
     * the setting did not exist before we wrote it, and it is cleared rather than left at
     * grayscale - otherwise the next time the user turns colour correction on themselves, it would
     * come up in a mode they never picked.
     */
    fun restore(resolver: ContentResolver, previousMode: Int?) {
        Settings.Secure.putInt(resolver, KEY_ENABLED, 0)
        Settings.Secure.putString(resolver, KEY_MODE, previousMode?.toString())
    }
}
