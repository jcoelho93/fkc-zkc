package com.mindfulscroll.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mindfulscroll.app.data.dao.ActiveSessionDao
import com.mindfulscroll.app.data.dao.DailyAppStatDao
import com.mindfulscroll.app.data.dao.IntentionDao
import com.mindfulscroll.app.data.dao.MonitoredAppDao
import com.mindfulscroll.app.data.dao.OverlayEventDao
import com.mindfulscroll.app.data.entity.ActiveSessionEntity
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.data.entity.OverlayEventEntity

@Database(
    entities = [
        MonitoredAppEntity::class,
        DailyAppStatEntity::class,
        ActiveSessionEntity::class,
        OverlayEventEntity::class,
        IntentionEntity::class,
    ],
    version = 3,
    // Turned on with the first real migration (1 -> 2, intention capture). The exported JSON in
    // app/schemas is what lets a future migration be tested against the schema it actually
    // starts from, rather than against one written from memory.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun monitoredAppDao(): MonitoredAppDao
    abstract fun dailyAppStatDao(): DailyAppStatDao
    abstract fun activeSessionDao(): ActiveSessionDao
    abstract fun overlayEventDao(): OverlayEventDao
    abstract fun intentionDao(): IntentionDao

    companion object {
        const val DATABASE_NAME = "mindful_scroll.db"
    }
}
