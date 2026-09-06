package com.mindfulscroll.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per time the interruption overlay was shown: what triggered it, what the user had said
 * they came for, whether they felt they got it, and what they then did.
 *
 * Intention, outcome and choice live in ONE row on purpose. The weekly report (#6) computes a
 * mismatch rate per app - "you said connection on 60% of opens but only felt you got it 20% of
 * the time" - and joining that back together across tables would mean guessing which intention
 * belonged to which pause. Here it is recorded rather than inferred.
 */
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
    /**
     * The intentions row for this visit, or null if no prompt was ever shown - capture switched
     * off in Settings, or debounced away by a recent prompt for the same app.
     *
     * Kept alongside [intentionKind] rather than replaced by it, because the two nulls mean
     * different things and #6 has to tell them apart: a null id is "never asked", while a non-null
     * id with a null kind is "asked and ignored", which is itself an answer worth counting.
     */
    val intentionId: Long? = null,
    /** Denormalised from the intentions row so the report needs no join. Null: see [intentionId]. */
    val intentionKind: IntentionKind? = null,
    /** Null when the user left before answering - a real and expected case, not a failure. */
    val outcome: PauseOutcome? = null,
)
