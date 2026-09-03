package com.mindfulscroll.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mindfulscroll.app.data.dao.ActiveSessionDao
import com.mindfulscroll.app.data.dao.DailyAppStatDao
import com.mindfulscroll.app.data.dao.MonitoredAppDao
import com.mindfulscroll.app.data.dao.OverlayEventDao
import com.mindfulscroll.app.data.entity.ActiveSessionEntity
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayEventEntity

@Database(
    entities = [
        MonitoredAppEntity::class,
        DailyAppStatEntity::class,
        ActiveSessionEntity::class,
        OverlayEventEntity::class,
    ],
    version = 1,
    // No migrations exist yet at v1; schema export can be turned on once one is needed.
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun monitoredAppDao(): MonitoredAppDao
    abstract fun dailyAppStatDao(): DailyAppStatDao
    abstract fun activeSessionDao(): ActiveSessionDao
    abstract fun overlayEventDao(): OverlayEventDao

    companion object {
        const val DATABASE_NAME = "mindful_scroll.db"
    }
}
