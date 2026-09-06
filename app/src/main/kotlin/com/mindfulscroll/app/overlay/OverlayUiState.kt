package com.mindfulscroll.app.overlay

/** How much extra time "5 more minutes" actually grants before the overlay can reappear. */
const val OVERLAY_GRACE_MINUTES = 5

data class OverlayUiState(
    val appLabel: String,
    val scrollCount: Int,
    val sessionMinutes: Int,
)
