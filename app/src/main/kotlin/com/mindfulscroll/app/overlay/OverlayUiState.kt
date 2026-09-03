package com.mindfulscroll.app.overlay

import com.mindfulscroll.app.data.entity.FrictionMode

/** The typed phrase required in [FrictionMode.TYPED_PHRASE] mode, shown to the user verbatim. */
const val OVERLAY_TYPED_FRICTION_PHRASE = "I choose to keep scrolling"

/** The countdown length in [FrictionMode.COUNTDOWN] mode, per spec. */
const val OVERLAY_COUNTDOWN_SECONDS = 10

/** How much extra time "5 more minutes" actually grants before the overlay can reappear. */
const val OVERLAY_GRACE_MINUTES = 5

data class OverlayUiState(
    val appLabel: String,
    val scrollCount: Int,
    val sessionMinutes: Int,
    val frictionMode: FrictionMode,
)
