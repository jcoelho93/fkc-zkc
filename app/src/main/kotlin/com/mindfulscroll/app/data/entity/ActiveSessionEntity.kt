package com.mindfulscroll.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The current continuous-foreground session for a monitored app, used only for threshold
 * evaluation (the "40 scrolls OR 10 minutes continuous, whichever first" rule). It resets
 * whenever the app leaves the foreground, and again after each overlay is resolved.
 *
 * This is intentionally separate from [DailyAppStatEntity]: the daily stat is a
 * never-reset running total for the dashboard, while this row is the short-lived
 * "session clock" the overlay threshold is measured against.
 */
@Entity(tableName = "active_sessions")
data class ActiveSessionEntity(
    @PrimaryKey val packageName: String,
    val sessionStartMillis: Long,
    val scrollCountInSession: Int,
)
