package com.mindfulscroll.app.ui.diagnostics

import android.util.Log
import androidx.lifecycle.ViewModel
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import com.mindfulscroll.app.accessibility.ServiceDiagnosticsState
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.grayscale.AppliedGrayscale
import com.mindfulscroll.app.grayscale.DaltonizerState
import com.mindfulscroll.app.grayscale.GrayscaleController
import com.mindfulscroll.app.reflection.ReflectionNotifier
import com.mindfulscroll.app.reflection.WeeklyReflectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Grayscale as the system sees it right now, read live rather than taken from the service's own
 * bookkeeping. [system] is null if the settings could not be read, which is shown as such.
 */
data class GrayscaleSnapshot(
    val permissionGranted: Boolean,
    val system: DaltonizerState?,
    val appliedByApp: AppliedGrayscale?,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    diagnostics: ServiceDiagnostics,
    private val grayscaleController: GrayscaleController,
    appSettings: AppSettings,
    private val weeklyStatus: WeeklyReflectionStatus,
    private val notifier: ReflectionNotifier,
) : ViewModel() {
    val state: StateFlow<ServiceDiagnosticsState> = diagnostics.state

    fun grayscaleSnapshot(): GrayscaleSnapshot = GrayscaleSnapshot(
        permissionGranted = grayscaleController.isPermissionGranted(),
        system = try {
            grayscaleController.readSystemState()
        } catch (error: RuntimeException) {
            Log.e("MindfulScroll", "Diagnostics could not read colour-correction settings", error)
            null
        },
        appliedByApp = grayscaleController.appliedRecord(),
    )

    val weeklyPromptEnabled: StateFlow<Boolean> = appSettings.isWeeklyReflectionPromptEnabled

    /** Persisted by the job itself, so it is read fresh rather than observed. */
    fun weeklyLastRun(): WeeklyReflectionStatus.LastRun? = weeklyStatus.lastRun()

    /** Null when a notification could reach the screen right now. */
    fun weeklyBlockedReason(): String? = notifier.blockedReason()
}
