package com.mindfulscroll.app.ui.settings

import com.mindfulscroll.app.data.entity.MonitoredAppEntity

/**
 * The current-value labels on the right of each Settings row. They're pure so they can be tested,
 * and they matter: the menu is meant to answer most questions without anyone opening anything
 * (#26).
 */
internal object SettingsLabels {

    fun onOff(enabled: Boolean): String = if (enabled) "On" else "Off"

    fun pauseLength(seconds: Int): String = "$seconds sec"

    fun monitoredApps(apps: List<MonitoredAppEntity>): String = when (apps.size) {
        0 -> "None yet"
        1 -> "1 app"
        else -> "${apps.size} apps"
    }

    fun threshold(app: MonitoredAppEntity): String =
        "${app.scrollThreshold} scrolls or ${app.timeThresholdMinutes} min"

    /**
     * One shared threshold is spelled out, because that answers the question. Several different
     * ones can't fit in a row, so the row says so and the list behind it has the details. Only
     * apps being monitored count: a switched-off app's threshold applies to nothing, and letting
     * it tip the row to "Per app" would describe settings that aren't in effect.
     */
    fun thresholdSummary(apps: List<MonitoredAppEntity>): String {
        if (apps.isEmpty()) return "No apps yet"
        val active = apps.filter { it.isMonitored }
        if (active.isEmpty()) return "None monitored"
        val distinct = active.map { it.scrollThreshold to it.timeThresholdMinutes }.distinct()
        return if (distinct.size == 1) threshold(active.first()) else "Per app"
    }
}
