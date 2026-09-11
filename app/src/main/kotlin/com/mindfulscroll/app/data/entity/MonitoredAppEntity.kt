package com.mindfulscroll.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Defaults from the MVP spec: 40 scrolls OR 10 minutes of continuous foreground time, whichever first. */
const val DEFAULT_SCROLL_THRESHOLD = 40
const val DEFAULT_TIME_THRESHOLD_MINUTES = 10

@Entity(tableName = "monitored_apps")
data class MonitoredAppEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val isMonitored: Boolean,
    val scrollThreshold: Int = DEFAULT_SCROLL_THRESHOLD,
    val timeThresholdMinutes: Int = DEFAULT_TIME_THRESHOLD_MINUTES,
    val addedAtMillis: Long,
    /**
     * Show this app in grayscale while it is open (#27). Off by default: it only does anything once
     * WRITE_SECURE_SETTINGS has been granted over adb. The SQL default is what MIGRATION_5_6 gives
     * every existing row, and it has to be declared here too or Room's schema check on open fails.
     */
    @ColumnInfo(defaultValue = "0")
    val grayscaleEnabled: Boolean = false,
)
