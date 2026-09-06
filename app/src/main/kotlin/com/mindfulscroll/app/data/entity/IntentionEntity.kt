package com.mindfulscroll.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What the user said they were hoping to find, captured when a monitored app comes to the
 * foreground - not at the threshold. The distinction is the whole point: by the time the pause
 * screen appears the answer is contaminated by having already scrolled for ten minutes, and
 * "why did you open this?" is a question only the moment of opening can answer honestly.
 *
 * The chips are deliberately not framed as good or bad. "Distraction" and "Habit" are legitimate
 * answers to give, and the weekly report compares what the user hoped for against what they felt
 * they got rather than scoring the hope itself.
 */
enum class IntentionKind {
    CONNECTION,
    ENTERTAINMENT,
    DISTRACTION,
    HABIT,
    SOMETHING_SPECIFIC,
}

/**
 * One row per prompt shown. A row exists even when the user ignores the prompt entirely
 * ([kind] null, [respondedAtMillis] null), because "opened it and had nothing in mind" is a real
 * and interesting answer - dropping those rows would silently bias every rate the weekly report
 * computes towards the opens the user felt like explaining.
 *
 * [sessionStartMillis] is what ties this to the session that follows: it is the same value as the
 * matching ActiveSessionEntity.sessionStartMillis, so the pause screen can ask "you said
 * Distraction - did you get that?" about THIS visit rather than some earlier one.
 */
@Entity(
    tableName = "intentions",
    indices = [Index(value = ["packageName", "sessionStartMillis"])],
)
data class IntentionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val dateEpochDay: Long,
    val sessionStartMillis: Long,
    val promptedAtMillis: Long,
    val respondedAtMillis: Long?,
    val kind: IntentionKind?,
    /** Optional free text; only ever set when the user deliberately typed something. */
    val note: String?,
)
