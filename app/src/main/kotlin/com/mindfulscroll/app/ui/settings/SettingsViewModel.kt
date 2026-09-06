package com.mindfulscroll.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
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
) : ViewModel() {

    val apps: StateFlow<List<MonitoredAppEntity>> = monitoredAppRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isIntentionCaptureEnabled: StateFlow<Boolean> = appSettings.isIntentionCaptureEnabled

    fun setIntentionCaptureEnabled(enabled: Boolean) {
        appSettings.setIntentionCaptureEnabled(enabled)
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
