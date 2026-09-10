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
 *
 * The three measures are deliberately independent (#28). [foregroundTimeMillis] is how long,
 * [openCount] is how often, and [scrollCount] is a best-effort swipe count. Time spent can fall
 * while the checking habit stays exactly as frequent, and reporting only duration would hide that.
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
    /**
     * How many times the app came to the foreground this day: one per foreground-entry, the same
     * transition that starts a session and shows the intention prompt. Never per scroll.
     *
     * Null means *not counted*, not zero. Rows written before this column existed (schema 4)
     * genuinely have no count, and 0 would claim the user never opened an app they spent an
     * hour in. Every row created since starts at 0.
     */
    val openCount: Int? = 0,
)
