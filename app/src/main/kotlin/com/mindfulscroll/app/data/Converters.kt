package com.mindfulscroll.app.data

import androidx.room.TypeConverter
import com.mindfulscroll.app.data.entity.FrictionMode
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.OverlayChoice

/** Room enum <-> String converters. Kept explicit and centralized rather than relying on defaults. */
class Converters {
    @TypeConverter
    fun frictionModeToString(value: FrictionMode): String = value.name

    @TypeConverter
    fun stringToFrictionMode(value: String): FrictionMode = FrictionMode.valueOf(value)

    @TypeConverter
    fun overlayChoiceToString(value: OverlayChoice): String = value.name

    @TypeConverter
    fun stringToOverlayChoice(value: String): OverlayChoice = OverlayChoice.valueOf(value)

    // Nullable on purpose: an unanswered prompt stores a null kind, and "shown but ignored" is a
    // row the weekly report must keep - see IntentionEntity.
    @TypeConverter
    fun intentionKindToString(value: IntentionKind?): String? = value?.name

    @TypeConverter
    fun stringToIntentionKind(value: String?): IntentionKind? = value?.let { IntentionKind.valueOf(it) }
}
