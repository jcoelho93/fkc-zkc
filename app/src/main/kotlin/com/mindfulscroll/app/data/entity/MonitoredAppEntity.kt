package com.mindfulscroll.app.data.entity

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
)
