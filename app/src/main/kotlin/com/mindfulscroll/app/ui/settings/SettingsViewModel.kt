package com.mindfulscroll.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulscroll.app.data.entity.FrictionMode
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
) : ViewModel() {

    val apps: StateFlow<List<MonitoredAppEntity>> = monitoredAppRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setMonitored(app: MonitoredAppEntity, monitored: Boolean) {
        viewModelScope.launch { monitoredAppRepository.setMonitored(app, monitored) }
    }

    fun updateThresholds(packageName: String, scrollThreshold: Int, timeThresholdMinutes: Int) {
        viewModelScope.launch {
            monitoredAppRepository.updateThresholds(packageName, scrollThreshold, timeThresholdMinutes)
        }
    }

    fun updateFrictionMode(packageName: String, mode: FrictionMode) {
        viewModelScope.launch { monitoredAppRepository.updateFrictionMode(packageName, mode) }
    }
}
