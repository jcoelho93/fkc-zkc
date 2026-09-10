package com.mindfulscroll.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
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
    /** Null: not counted today - see DailyAppStatEntity.openCount. */
    val openCount: Int?,
)

/**
 * One day across all monitored apps. Time and opens are separate figures on purpose (#28):
 * duration can fall while the checking habit stays exactly as frequent.
 */
data class DayBar(
    val label: String,
    val scrollCount: Int,
    val foregroundMinutes: Int,
    /** Null when any app's row that day predates open counting, so the total would be partial. */
    val openCount: Int?,
)

/**
 * Pure: folds per-app daily rows into one [DayBar] per day for the seven days starting
 * [firstEpochDay]. A day with no rows at all is a real zero, since nothing was recorded. A day
 * where any row has a null open count gets a null total rather than a sum that quietly
 * leaves those opens out.
 */
internal fun summarizeDays(
    stats: List<DailyAppStatEntity>,
    firstEpochDay: Long,
    days: Int = 7,
    locale: Locale = Locale.getDefault(),
): List<DayBar> {
    val byDay = stats.groupBy { it.dateEpochDay }
    return (0 until days).map { offset ->
        val epochDay = firstEpochDay + offset
        val rows = byDay[epochDay].orEmpty()
        DayBar(
            label = LocalDate.ofEpochDay(epochDay).dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
            scrollCount = rows.sumOf { it.scrollCount },
            foregroundMinutes = (rows.sumOf { it.foregroundTimeMillis } / 60_000L).toInt(),
            openCount = if (rows.any { it.openCount == null }) null else rows.sumOf { it.openCount ?: 0 },
        )
    }
}

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
                .filter { it.scrollCount > 0 || it.foregroundTimeMillis > 0 || (it.openCount ?: 0) > 0 }
                .map { stat ->
                    AppTodayStat(
                        appLabel = appsByPackage[stat.packageName]?.appLabel ?: stat.packageName,
                        scrollCount = stat.scrollCount,
                        foregroundMinutes = stat.foregroundTimeMillis / 60_000L,
                        openCount = stat.openCount,
                    )
                }
                .sortedByDescending { it.scrollCount }

            val dayBars = summarizeDays(weekStats, firstEpochDay = weekStart)

            val outcomes = OverlayOutcomeSummary(
                shown = overlayEvents.size,
                closedApp = overlayEvents.count { it.choice == OverlayChoice.CLOSE_APP },
                continued = overlayEvents.count { it.choice == OverlayChoice.CONTINUE },
            )

            DashboardUiState(today = todaySummary, last7Days = dayBars, overlayOutcomes = outcomes)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
