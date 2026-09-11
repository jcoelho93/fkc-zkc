package com.mindfulscroll.app.ui.reflection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulscroll.app.data.AppSettings
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import com.mindfulscroll.app.reflection.ReflectionRepository
import com.mindfulscroll.app.reflection.ReflectionWindow
import com.mindfulscroll.app.reflection.WeeklyReflection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject

data class ReflectionUiState(
    /** 0 is the seven days ending today; 1 the seven before that, and so on. */
    val weekOffset: Int = 0,
    /** Null while the first load is in flight, so an empty week is never flashed by mistake. */
    val reflection: WeeklyReflection? = null,
    val appLabels: Map<String, String> = emptyMap(),
    val intentionCaptureEnabled: Boolean = true,
) {
    val canGoBack: Boolean get() = weekOffset < ReflectionViewModel.MAX_WEEKS_BACK
    val canGoForward: Boolean get() = weekOffset > 0

    fun labelFor(packageName: String): String = appLabels[packageName] ?: packageName
}

@HiltViewModel
class ReflectionViewModel @Inject constructor(
    repository: ReflectionRepository,
    monitoredAppRepository: MonitoredAppRepository,
    appSettings: AppSettings,
) : ViewModel() {

    private val weekOffset = MutableStateFlow(0)
    private val today = LocalDate.now().toEpochDay()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ReflectionUiState> = weekOffset.flatMapLatest { offset ->
        val window = ReflectionWindow.weekEndingOn(today - offset * ReflectionWindow.WEEK_DAYS)
        combine(
            repository.observe(window),
            monitoredAppRepository.observeAll(),
            appSettings.isIntentionCaptureEnabled,
        ) { reflection, apps, captureEnabled ->
            ReflectionUiState(
                weekOffset = offset,
                reflection = reflection,
                appLabels = apps.associate { it.packageName to it.appLabel },
                intentionCaptureEnabled = captureEnabled,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReflectionUiState())

    fun previousWeek() = weekOffset.update { if (it < MAX_WEEKS_BACK) it + 1 else it }

    fun nextWeek() = weekOffset.update { if (it > 0) it - 1 else it }

    companion object {
        /** History is pruned at 90 days (DailyMaintenanceWorker): twelve whole weeks fit behind this one. */
        const val MAX_WEEKS_BACK = 12
    }
}
