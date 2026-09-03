package com.mindfulscroll.app.data.entity

import androidx.room.Entity

/**
 * One row per (app, calendar day). This is the running per-app, per-day counter the
 * accessibility service updates live, and what the dashboard reads for "today" and
 * the past-7-days chart.
 *
 * [dateEpochDay] is days since epoch in the device's local time zone (see
 * java.time.LocalDate.toEpochDay), so a day boundary always matches what the user sees
 * on their clock rather than shifting with UTC.
 */
@Entity(
    tableName = "daily_app_stats",
    primaryKeys = ["packageName", "dateEpochDay"],
)
data class DailyAppStatEntity(
    val packageName: String,
    val dateEpochDay: Long,
    val scrollCount: Int,
    val foregroundTimeMillis: Long,
    val updatedAtMillis: Long,
)
