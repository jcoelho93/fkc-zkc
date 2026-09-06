package com.mindfulscroll.app.overlay

import com.mindfulscroll.app.data.entity.IntentionKind

/** How much extra time "5 more minutes" actually grants before the overlay can reappear. */
const val OVERLAY_GRACE_MINUTES = 5

data class OverlayUiState(
    val appLabel: String,
    val scrollCount: Int,
    val sessionMinutes: Int,
    /**
     * What the user said they were after when they opened the app, for this visit only.
     *
     * Null covers three different situations that the pause screen treats identically - capture
     * switched off, the prompt debounced away, or the prompt shown and ignored. It asks no recall
     * question in any of them, because inventing one ("you said nothing - did you get it?") would
     * be worse than silence. The database keeps the three apart; see OverlayEventEntity.
     */
    val intentionKind: IntentionKind? = null,
    /** Only ever set when the user deliberately typed something at the prompt. */
    val intentionNote: String? = null,
    /** How long the urge-surfing phase runs before the recall appears. Never gates the exits. */
    val pauseDurationSeconds: Int = 20,
)
