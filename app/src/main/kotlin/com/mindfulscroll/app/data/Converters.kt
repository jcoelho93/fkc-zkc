package com.mindfulscroll.app.data

import androidx.room.TypeConverter
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.OverlayChoice
import com.mindfulscroll.app.data.entity.PauseOutcome

/** Room enum <-> String converters. Kept explicit and centralized rather than relying on defaults. */
class Converters {
    @TypeConverter
    fun overlayChoiceToString(value: OverlayChoice): String = value.name

    // Nullable: the user is allowed to leave the pause screen without answering, and that row
    // must still be countable - same reasoning as an unanswered intention prompt.
    @TypeConverter
    fun pauseOutcomeToString(value: PauseOutcome?): String? = value?.name

    @TypeConverter
    fun stringToPauseOutcome(value: String?): PauseOutcome? = value?.let { PauseOutcome.valueOf(it) }

    @TypeConverter
    fun stringToOverlayChoice(value: String): OverlayChoice = OverlayChoice.valueOf(value)

    // Nullable on purpose: an unanswered prompt stores a null kind, and "shown but ignored" is a
    // row the weekly report must keep - see IntentionEntity.
    @TypeConverter
    fun intentionKindToString(value: IntentionKind?): String? = value?.name

    @TypeConverter
    fun stringToIntentionKind(value: String?): IntentionKind? = value?.let { IntentionKind.valueOf(it) }
}
