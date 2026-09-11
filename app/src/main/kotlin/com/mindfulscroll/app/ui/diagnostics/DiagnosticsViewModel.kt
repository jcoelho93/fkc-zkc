package com.mindfulscroll.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import com.mindfulscroll.app.accessibility.ServiceDiagnostics
import com.mindfulscroll.app.accessibility.ServiceDiagnosticsState
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.reflection.ReflectionNotifier
import com.mindfulscroll.app.reflection.WeeklyReflectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    diagnostics: ServiceDiagnostics,
    appSettings: AppSettings,
    private val weeklyStatus: WeeklyReflectionStatus,
    private val notifier: ReflectionNotifier,
) : ViewModel() {
    val state: StateFlow<ServiceDiagnosticsState> = diagnostics.state

    val weeklyPromptEnabled: StateFlow<Boolean> = appSettings.isWeeklyReflectionPromptEnabled

    /** Persisted by the job itself, so it is read fresh rather than observed. */
    fun weeklyLastRun(): WeeklyReflectionStatus.LastRun? = weeklyStatus.lastRun()

    /** Null when a notification could reach the screen right now. */
    fun weeklyBlockedReason(): String? = notifier.blockedReason()
}
