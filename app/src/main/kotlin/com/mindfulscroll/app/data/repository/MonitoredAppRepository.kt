package com.mindfulscroll.app.data.repository

import com.mindfulscroll.app.data.dao.MonitoredAppDao
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonitoredAppRepository @Inject constructor(
    private val dao: MonitoredAppDao,
) {
    fun observeAll(): Flow<List<MonitoredAppEntity>> = dao.observeAll()

    fun observeMonitored(): Flow<List<MonitoredAppEntity>> = dao.observeMonitoredApps()

    suspend fun getMonitoredPackageNames(): Set<String> =
        dao.getMonitoredApps().map { it.packageName }.toSet()

    suspend fun get(packageName: String): MonitoredAppEntity? = dao.get(packageName)

    suspend fun setMonitored(app: MonitoredAppEntity, monitored: Boolean) {
        dao.update(app.copy(isMonitored = monitored))
    }

    suspend fun getAllPackageNames(): Set<String> = dao.getAll().map { it.packageName }.toSet()

    /**
     * Makes the monitored list contain exactly [selected] - adding what is new, and **deleting**
     * rows the user has taken off the list.
     *
     * Two things here are load-bearing, and neither is obvious from the call site.
     *
     * A plain `upsertAll` cannot express removal: it only ever adds, so an app deselected in the
     * picker would silently stay monitored. That was survivable while this ran once during
     * onboarding; it is not, now that Settings can edit the list.
     *
     * And an app that is already on the list keeps its EXISTING row rather than being overwritten
     * by the freshly-built one. `upsertAll` uses REPLACE, and the entities the picker constructs
     * carry default thresholds - so overwriting would quietly reset every threshold the user had
     * tuned, and clear the isMonitored switch, every time they opened the picker to add one
     * unrelated app. Only the label is refreshed, since an app can be renamed by an update.
     */
    suspend fun applySelection(selected: List<MonitoredAppEntity>) {
        val existingByPackage = dao.getAll().associateBy { it.packageName }
        val merged = selected.map { candidate ->
            existingByPackage[candidate.packageName]?.copy(appLabel = candidate.appLabel) ?: candidate
        }
        dao.upsertAll(merged)

        val removed = existingByPackage.keys - selected.map { it.packageName }.toSet()
        if (removed.isNotEmpty()) dao.deleteByPackageNames(removed.toList())
    }

    suspend fun updateThresholds(
        packageName: String,
        scrollThreshold: Int,
        timeThresholdMinutes: Int,
    ) {
        val existing = dao.get(packageName) ?: return
        dao.update(
            existing.copy(
                scrollThreshold = scrollThreshold,
                timeThresholdMinutes = timeThresholdMinutes,
            ),
        )
    }
}
