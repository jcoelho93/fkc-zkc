package com.mindfulscroll.app.data.entity

/** What the user picked when the interruption overlay appeared. */
enum class OverlayChoice {
    /** Overlay is showing and the user has not responded yet. */
    PENDING,

    /** User tapped "Close app" (we sent GLOBAL_ACTION_HOME). */
    CLOSE_APP,

    /** User chose "5 more minutes" and stayed in the app. */
    CONTINUE,
}
