package com.mindfulscroll.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import com.mindfulscroll.app.reflection.WeeklyPromptToggle
import com.mindfulscroll.app.reflection.WeeklyReflectionPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val monitoredAppRepository: MonitoredAppRepository,
    private val appSettings: AppSettings,
    private val weeklyReflectionPrompt: WeeklyReflectionPrompt,
) : ViewModel() {

    val apps: StateFlow<List<MonitoredAppEntity>> = monitoredAppRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isIntentionCaptureEnabled: StateFlow<Boolean> = appSettings.isIntentionCaptureEnabled

    fun setIntentionCaptureEnabled(enabled: Boolean) {
        appSettings.setIntentionCaptureEnabled(enabled)
    }

    val isWeeklyReflectionPromptEnabled: StateFlow<Boolean> = appSettings.isWeeklyReflectionPromptEnabled

    /** Takes the already-decided action - see WeeklyPromptToggle for when the permission is asked. */
    internal fun applyWeeklyPromptAction(action: WeeklyPromptToggle.Action) {
        when (action) {
            WeeklyPromptToggle.Action.TURN_ON -> weeklyReflectionPrompt.setEnabled(true)
            WeeklyPromptToggle.Action.TURN_OFF -> weeklyReflectionPrompt.setEnabled(false)
            // The screen launches the system dialog; there is nothing to store until it answers.
            WeeklyPromptToggle.Action.REQUEST_PERMISSION -> Unit
        }
    }

    val pauseDurationSeconds: StateFlow<Int> = appSettings.pauseDurationSeconds

    fun setPauseDurationSeconds(seconds: Int) {
        appSettings.setPauseDurationSeconds(seconds)
    }

    fun setMonitored(app: MonitoredAppEntity, monitored: Boolean) {
        viewModelScope.launch { monitoredAppRepository.setMonitored(app, monitored) }
    }

    fun updateThresholds(packageName: String, scrollThreshold: Int, timeThresholdMinutes: Int) {
        viewModelScope.launch {
            monitoredAppRepository.updateThresholds(packageName, scrollThreshold, timeThresholdMinutes)
        }
    }
}
