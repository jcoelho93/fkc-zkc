package com.mindfulscroll.app.di

import android.content.Context
import androidx.room.Room
import com.mindfulscroll.app.data.ALL_MIGRATIONS
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.dao.ActiveSessionDao
import com.mindfulscroll.app.data.dao.DailyAppStatDao
import com.mindfulscroll.app.data.dao.IntentionDao
import com.mindfulscroll.app.data.dao.MonitoredAppDao
import com.mindfulscroll.app.data.dao.OverlayEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        // No fallbackToDestructiveMigration, deliberately: this database holds the user's own
        // history, and silently wiping it on a schema change we forgot to migrate would destroy
        // the only copy - there is no backup (allowBackup=false) and no server.
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideMonitoredAppDao(db: AppDatabase): MonitoredAppDao = db.monitoredAppDao()

    @Provides
    fun provideDailyAppStatDao(db: AppDatabase): DailyAppStatDao = db.dailyAppStatDao()

    @Provides
    fun provideActiveSessionDao(db: AppDatabase): ActiveSessionDao = db.activeSessionDao()

    @Provides
    fun provideOverlayEventDao(db: AppDatabase): OverlayEventDao = db.overlayEventDao()

    @Provides
    fun provideIntentionDao(db: AppDatabase): IntentionDao = db.intentionDao()
}
