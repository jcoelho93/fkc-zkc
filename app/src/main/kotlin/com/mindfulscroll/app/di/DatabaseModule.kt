package com.mindfulscroll.app.di

import android.content.Context
import androidx.room.Room
import com.mindfulscroll.app.data.AppDatabase
import com.mindfulscroll.app.data.dao.ActiveSessionDao
import com.mindfulscroll.app.data.dao.DailyAppStatDao
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
        // Schema is at version 1 with no prior version to migrate from yet; a migration
        // strategy will be added alongside the first real schema change post-MVP.
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .build()

    @Provides
    fun provideMonitoredAppDao(db: AppDatabase): MonitoredAppDao = db.monitoredAppDao()

    @Provides
    fun provideDailyAppStatDao(db: AppDatabase): DailyAppStatDao = db.dailyAppStatDao()

    @Provides
    fun provideActiveSessionDao(db: AppDatabase): ActiveSessionDao = db.activeSessionDao()

    @Provides
    fun provideOverlayEventDao(db: AppDatabase): OverlayEventDao = db.overlayEventDao()
}
