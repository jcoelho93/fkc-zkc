package com.mindfulscroll.app.reflection

import com.mindfulscroll.app.data.dao.DailyAppStatDao
import com.mindfulscroll.app.data.dao.IntentionDao
import com.mindfulscroll.app.data.dao.OverlayEventDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the three tables the reflection is built from and hands them to [ReflectionAggregator].
 * No table of its own: everything the report shows is recomputed from the rows the app already
 * keeps, so it can never disagree with them, and it ages out with them at 90 days.
 */
@Singleton
class ReflectionRepository @Inject constructor(
    private val intentionDao: IntentionDao,
    private val overlayEventDao: OverlayEventDao,
    private val dailyAppStatDao: DailyAppStatDao,
) {
    suspend fun load(window: ReflectionWindow): WeeklyReflection =
        ReflectionAggregator.aggregate(
            window = window,
            intentions = intentionDao.getForDayRange(window.startEpochDay, window.lastEpochDay),
            overlayEvents = overlayEventDao.getForDayRange(window.startEpochDay, window.lastEpochDay),
            dailyStats = dailyAppStatDao.getForDayRange(window.startEpochDay, window.lastEpochDay),
        )

    fun observe(window: ReflectionWindow): Flow<WeeklyReflection> =
        combine(
            intentionDao.observeForDayRange(window.startEpochDay, window.lastEpochDay),
            overlayEventDao.observeForDayRange(window.startEpochDay, window.lastEpochDay),
            dailyAppStatDao.observeForDayRange(window.startEpochDay, window.lastEpochDay),
        ) { intentions, events, stats ->
            ReflectionAggregator.aggregate(window, intentions, events, stats)
        }
}
