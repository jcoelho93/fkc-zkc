package com.mindfulscroll.app.ui.appselection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulscroll.app.data.OnboardingPreferences
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppSelectionUiState(
    val isLoading: Boolean = true,
    val apps: List<InstalledAppInfo> = emptyList(),
    val selectedPackageNames: Set<String> = emptySet(),
)

@HiltViewModel
class AppSelectionViewModel @Inject constructor(
    private val installedAppsProvider: InstalledAppsProvider,
    private val monitoredAppRepository: MonitoredAppRepository,
    private val onboardingPreferences: OnboardingPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSelectionUiState())
    val uiState: StateFlow<AppSelectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val (apps, alreadySelected) = withContext(Dispatchers.IO) {
                installedAppsProvider.queryLaunchableApps() to monitoredAppRepository.getMonitoredPackageNames()
            }
            val preselected = if (alreadySelected.isNotEmpty()) {
                alreadySelected
            } else {
                apps.map { it.packageName }.filter { it in SUGGESTED_PACKAGE_NAMES }.toSet()
            }
            _uiState.value = AppSelectionUiState(isLoading = false, apps = apps, selectedPackageNames = preselected)
        }
    }

    fun toggle(packageName: String) {
        val current = _uiState.value.selectedPackageNames
        val updated = if (packageName in current) current - packageName else current + packageName
        _uiState.value = _uiState.value.copy(selectedPackageNames = updated)
    }

    fun confirmSelection(onDone: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entities = state.apps
                .filter { it.packageName in state.selectedPackageNames }
                .map { app ->
                    MonitoredAppEntity(
                        packageName = app.packageName,
                        appLabel = app.label,
                        isMonitored = true,
                        addedAtMillis = now,
                    )
                }
            monitoredAppRepository.saveSelection(entities)
            onboardingPreferences.hasCompletedAppSelection = true
            onDone()
        }
    }
}
