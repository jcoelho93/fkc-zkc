package com.mindfulscroll.app.data.repository

import com.mindfulscroll.app.data.dao.MonitoredAppDao
import com.mindfulscroll.app.data.entity.FrictionMode
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

    suspend fun hasCompletedAppSelection(): Boolean = dao.count() > 0

    suspend fun setMonitored(app: MonitoredAppEntity, monitored: Boolean) {
        dao.update(app.copy(isMonitored = monitored))
    }

    suspend fun saveSelection(apps: List<MonitoredAppEntity>) {
        dao.upsertAll(apps)
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

    suspend fun updateFrictionMode(packageName: String, mode: FrictionMode) {
        val existing = dao.get(packageName) ?: return
        dao.update(existing.copy(frictionMode = mode))
    }
}
