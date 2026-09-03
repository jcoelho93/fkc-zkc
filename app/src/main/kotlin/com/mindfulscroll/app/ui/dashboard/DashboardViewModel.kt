package com.mindfulscroll.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.repository.MonitoredAppRepository
import com.mindfulscroll.app.data.repository.ScrollStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class AppTodayStat(
    val appLabel: String,
    val scrollCount: Int,
    val foregroundMinutes: Long,
)

data class DayBar(
    val label: String,
    val scrollCount: Int,
)

data class OverlayOutcomeSummary(
    val shown: Int,
    val closedApp: Int,
    val continued: Int,
)

data class DashboardUiState(
    val today: List<AppTodayStat> = emptyList(),
    val last7Days: List<DayBar> = emptyList(),
    val overlayOutcomes: OverlayOutcomeSummary = OverlayOutcomeSummary(0, 0, 0),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    monitoredAppRepository: MonitoredAppRepository,
    scrollStatsRepository: ScrollStatsRepository,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = run {
        val today = scrollStatsRepository.todayEpochDay()
        val weekStart = today - 6

        combine(
            monitoredAppRepository.observeAll(),
            scrollStatsRepository.observeStatsForRange(today, today),
            scrollStatsRepository.observeStatsForRange(weekStart, today),
            scrollStatsRepository.observeOverlayEventsForRange(weekStart, today),
        ) { apps, todayStats, weekStats, overlayEvents ->
            val appsByPackage: Map<String, MonitoredAppEntity> = apps.associateBy { it.packageName }

            val todaySummary = todayStats
                .filter { it.scrollCount > 0 || it.foregroundTimeMillis > 0 }
                .map { stat ->
                    AppTodayStat(
                        appLabel = appsByPackage[stat.packageName]?.appLabel ?: stat.packageName,
                        scrollCount = stat.scrollCount,
                        foregroundMinutes = stat.foregroundTimeMillis / 60_000L,
                    )
                }
                .sortedByDescending { it.scrollCount }

            val scrollsByDay = weekStats.groupBy { it.dateEpochDay }
                .mapValues { (_, stats) -> stats.sumOf { it.scrollCount } }
            val dayBars = (0..6).map { offset ->
                val epochDay = weekStart + offset
                val date = LocalDate.ofEpochDay(epochDay)
                DayBar(
                    label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    scrollCount = scrollsByDay[epochDay] ?: 0,
                )
            }

            val outcomes = OverlayOutcomeSummary(
                shown = overlayEvents.size,
                closedApp = overlayEvents.count { it.choice == OverlayChoice.CLOSE_APP },
                continued = overlayEvents.count { it.choice == OverlayChoice.CONTINUE },
            )

            DashboardUiState(today = todaySummary, last7Days = dayBars, overlayOutcomes = outcomes)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
