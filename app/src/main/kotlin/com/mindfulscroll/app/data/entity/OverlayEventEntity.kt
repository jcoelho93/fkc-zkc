package com.mindfulscroll.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per time the interruption overlay was shown, and how the user responded. */
@Entity(tableName = "overlay_events")
data class OverlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val dateEpochDay: Long,
    val shownAtMillis: Long,
    val scrollCountAtTrigger: Int,
    val sessionTimeMillisAtTrigger: Long,
    val choice: OverlayChoice,
    val respondedAtMillis: Long?,
)
