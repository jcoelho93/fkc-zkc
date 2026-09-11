package com.mindfulscroll.app.grayscale

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns grayscale on while a monitored app that has it switched on is in front, and off again
 * when it is not (#27). The decisions are GrayscalePolicy's; this class does the I/O around them
 * and records every outcome on ServiceDiagnostics.
 *
 * The display-wide daltonizer is the only mechanism available, which makes one failure worth
 * designing against above all others: the service dying with the screen gray, leaving the whole
 * phone colourless with nothing left running to undo it. Hence the persisted [AppliedGrayscale]
 * record, written BEFORE the system setting and cleared only AFTER it is restored, and
 * [restoreIfApplied] on every way in and out of the service, plus boot.
 */
@Singleton
class GrayscaleController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: ServiceDiagnostics,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mindful_scroll_grayscale", Context.MODE_PRIVATE)

    fun isPermissionGranted(): Boolean = DaltonizerSettings.isPermissionGranted(context)

    fun readSystemState(): DaltonizerState = DaltonizerSettings.read(context.contentResolver)

    fun appliedRecord(): AppliedGrayscale? {
        if (!prefs.getBoolean(KEY_APPLIED, false)) return null
        val previous = if (prefs.contains(KEY_PREVIOUS_MODE)) prefs.getInt(KEY_PREVIOUS_MODE, 0) else null
        return AppliedGrayscale(previousMode = previous)
    }

    /** Called on every foreground change the service accepts, and when the monitored list changes. */
    fun onForeground(packageName: String?, wantGrayscale: Boolean) {
        update(wantGrayscale = wantGrayscale, context = packageName ?: "(no app)")
    }

    /** Service connecting, stopping or interrupted, or the device booting: put back anything of ours. */
    fun restoreIfApplied(reason: String) {
        update(wantGrayscale = false, context = reason)
    }

    private fun update(wantGrayscale: Boolean, context: String) {
        val decision = try {
            GrayscalePolicy.decide(
                wantGrayscale = wantGrayscale,
                permissionGranted = isPermissionGranted(),
                system = readSystemState(),
                applied = appliedRecord(),
            )
        } catch (error: RuntimeException) {
            recordError("Could not read colour-correction settings ($context)", error)
            return
        }

        when (decision) {
            is GrayscaleDecision.Apply -> apply(decision.previousMode, context)
            is GrayscaleDecision.Restore -> restore(decision.previousMode, context)
            GrayscaleDecision.Forget -> {
                clearRecord()
                val now = readSystemStateOrNull()
                diagnostics.update { it.copy(lastGrayscaleRestore = "${time()} $context: not restored - colour correction was changed outside the app since (now ${now.describe()})") }
                diagnostics.log("Grayscale record cleared ($context): colour correction no longer shows ours, left as it is")
            }
            is GrayscaleDecision.Skip -> skip(decision.reason, context)
            GrayscaleDecision.NoChange -> Unit
        }
    }

    private fun apply(previousMode: Int?, context: String) {
        // Recorded first and synchronously (commit, not apply): if the process dies between here
        // and the write below, the worst case is a record with no grayscale, which the policy
        // clears on sight. The other order could leave grayscale with no record of being ours.
        prefs.edit(commit = true) {
            putBoolean(KEY_APPLIED, true)
            if (previousMode != null) putInt(KEY_PREVIOUS_MODE, previousMode) else remove(KEY_PREVIOUS_MODE)
        }
        try {
            DaltonizerSettings.writeGrayscale(this.context.contentResolver)
        } catch (error: RuntimeException) {
            clearRecord()
            recordError("Grayscale NOT applied for $context", error)
            return
        }

        // Attempted is not worked: read the setting back from the system rather than trusting
        // the write returned.
        val readBack = readSystemStateOrNull()
        if (readBack?.isGrayscale != true) {
            diagnostics.update { it.copy(lastGrayscaleError = "${time()} wrote grayscale for $context but the system reads back ${readBack.describe()}") }
            diagnostics.log("Grayscale write for $context did not take effect (read back ${readBack.describe()})")
            Log.e(TAG, "Grayscale write for $context did not take effect: ${readBack.describe()}")
            return
        }
        diagnostics.update {
            it.copy(
                grayscaleAppliedCount = it.grayscaleAppliedCount + 1,
                lastGrayscaleApply = "${time()} $context (read back ${readBack.describe()})",
            )
        }
        diagnostics.log("Grayscale on for $context")
    }

    private fun restore(previousMode: Int?, context: String) {
        try {
            DaltonizerSettings.restore(this.context.contentResolver, previousMode)
        } catch (error: RuntimeException) {
            // The record stays, so the next transition or connect tries again.
            recordError("Grayscale NOT restored ($context) - the screen may still be gray", error)
            return
        }
        val readBack = readSystemStateOrNull()
        if (readBack == null || readBack.enabled) {
            recordErrorText("restore ($context) wrote colour correction off but the system reads back ${readBack.describe()}")
            return
        }
        clearRecord()
        diagnostics.update {
            it.copy(
                grayscaleRestoredCount = it.grayscaleRestoredCount + 1,
                lastGrayscaleRestore = "${time()} $context (read back ${readBack.describe()})",
            )
        }
        diagnostics.log("Grayscale off ($context)")
    }

    private fun skip(reason: GrayscaleSkipReason, context: String) {
        val text = when (reason) {
            GrayscaleSkipReason.PERMISSION_MISSING ->
                "WRITE_SECURE_SETTINGS not granted, so grayscale cannot be applied"
            GrayscaleSkipReason.COLOR_CORRECTION_ALREADY_ON ->
                "colour correction is already on and was not set by this app (${readSystemStateOrNull().describe()}) - left as it is"
            GrayscaleSkipReason.PERMISSION_REVOKED_WHILE_APPLIED ->
                "grayscale is ours but WRITE_SECURE_SETTINGS was revoked, so it cannot be turned off - " +
                    "turn off colour correction in Android's accessibility settings"
        }
        if (reason == GrayscaleSkipReason.PERMISSION_REVOKED_WHILE_APPLIED) {
            recordErrorText("$context: $text")
        } else {
            diagnostics.update { it.copy(lastGrayscaleSkip = "${time()} $context: $text") }
            diagnostics.log("Grayscale skipped for $context: $text")
        }
    }

    private fun clearRecord() {
        prefs.edit(commit = true) {
            remove(KEY_APPLIED)
            remove(KEY_PREVIOUS_MODE)
        }
    }

    private fun readSystemStateOrNull(): DaltonizerState? = try {
        readSystemState()
    } catch (error: RuntimeException) {
        Log.e(TAG, "Could not read colour-correction settings", error)
        null
    }

    private fun recordError(message: String, error: RuntimeException) {
        recordErrorText("$message: ${error.javaClass.simpleName}: ${error.message}")
        Log.e(TAG, message, error)
    }

    private fun recordErrorText(message: String) {
        diagnostics.update { it.copy(lastGrayscaleError = "${time()} $message") }
        diagnostics.log("Grayscale error: $message")
        Log.e(TAG, message)
    }

    private fun DaltonizerState?.describe(): String =
        if (this == null) "(unreadable)" else "enabled=${if (enabled) 1 else 0} mode=${mode ?: "unset"}"

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val TAG = "MindfulScroll"
        const val KEY_APPLIED = "grayscale_applied_by_us"
        const val KEY_PREVIOUS_MODE = "grayscale_previous_mode"
    }
}

/**
 * For BootRescheduleReceiver, which is not a Hilt entry point itself. Lets it reach the same
 * singleton (and the same Diagnostics) as the service.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GrayscaleEntryPoint {
    fun grayscaleController(): GrayscaleController
}
