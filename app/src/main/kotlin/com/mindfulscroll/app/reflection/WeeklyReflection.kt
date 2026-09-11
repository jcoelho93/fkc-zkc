package com.mindfulscroll.app.reflection

import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import com.mindfulscroll.app.data.entity.IntentionEntity
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.OverlayEventEntity
import com.mindfulscroll.app.data.entity.PauseOutcome

/**
 * A run of whole local days, `[startEpochDay, endEpochDayExclusive)`.
 *
 * Explicit rather than "this week" so the one aggregation can serve a full week (#6), a partial
 * one (a mid-week check-in, #31) or a series of weeks (pause-decay tracking, #29) without a second
 * copy of the counting rules.
 */
data class ReflectionWindow(val startEpochDay: Long, val endEpochDayExclusive: Long) {
    init {
        require(endEpochDayExclusive > startEpochDay) {
            "empty window: [$startEpochDay, $endEpochDayExclusive)"
        }
    }

    val lastEpochDay: Long get() = endEpochDayExclusive - 1

    operator fun contains(epochDay: Long): Boolean =
        epochDay >= startEpochDay && epochDay < endEpochDayExclusive

    companion object {
        const val WEEK_DAYS = 7L

        /** The seven days ending on, and including, [lastEpochDay]. */
        fun weekEndingOn(lastEpochDay: Long): ReflectionWindow =
            ReflectionWindow(lastEpochDay - (WEEK_DAYS - 1), lastEpochDay + 1)
    }
}

/**
 * How the pause screen's "did you get it?" was answered, over some set of pauses. [noAnswer] is a
 * pause where the question was on screen and the user left without picking one, which is an
 * expected case and is counted, not dropped.
 */
data class OutcomeTally(
    val yes: Int = 0,
    val kindOf: Int = 0,
    val notReally: Int = 0,
    val noAnswer: Int = 0,
) {
    val total: Int get() = yes + kindOf + notReally + noAnswer

    operator fun plus(outcome: PauseOutcome?): OutcomeTally = when (outcome) {
        PauseOutcome.YES -> copy(yes = yes + 1)
        PauseOutcome.KIND_OF -> copy(kindOf = kindOf + 1)
        PauseOutcome.NOT_REALLY -> copy(notReally = notReally + 1)
        null -> copy(noAnswer = noAnswer + 1)
    }
}

/**
 * One intention, for one app: how often it was given at opening, and how the pause's question
 * went on the visits where it was given. The pairing is the report (#6): what the user hoped for,
 * next to what they felt they got. Neither number is a score.
 */
data class IntentionReflection(
    val kind: IntentionKind,
    /** Prompts answered with [kind]. The denominator is AppReflection.promptsShown. */
    val saidAtOpening: Int,
    /** Pauses on visits where [kind] was the answer at opening. */
    val atPause: OutcomeTally,
)

data class AppReflection(
    val packageName: String,
    /**
     * Times the prompt at opening appeared. This, not the answered subset, is the denominator of
     * every "said X on N of M opens": rates over answered prompts only would lean towards the
     * opens the user felt like explaining (see IntentionEntity).
     */
    val promptsShown: Int,
    /** Prompts shown and left without an answer. Counted, never dropped. */
    val promptsUnanswered: Int,
    /** One entry per intention with any data, in the order the prompt's chips appear. */
    val byIntention: List<IntentionReflection>,
    /**
     * Pauses on a visit whose prompt was left unanswered. The pause asks no question then (there
     * is nothing to recall), so these have no outcome at all, which is different from
     * OutcomeTally.noAnswer.
     */
    val pausesAfterUnansweredPrompt: Int,
    /** Pauses on a visit with no prompt: capture switched off, or a recent prompt for the app. */
    val pausesWithoutPrompt: Int,
    /**
     * Times opened across the window (#28), or null when any day in it predates open counting,
     * so the sum would quietly leave those opens out.
     */
    val timesOpened: Int?,
) {
    val pausesShown: Int
        get() = byIntention.sumOf { it.atPause.total } + pausesAfterUnansweredPrompt + pausesWithoutPrompt
}

data class WeeklyReflection(
    val window: ReflectionWindow,
    val apps: List<AppReflection>,
) {
    val isEmpty: Boolean get() = apps.isEmpty()

    /** All apps together: how often each intention was given at opening. */
    fun saidAtOpening(kind: IntentionKind): Int =
        apps.sumOf { app -> app.byIntention.firstOrNull { it.kind == kind }?.saidAtOpening ?: 0 }

    val promptsUnanswered: Int get() = apps.sumOf { it.promptsUnanswered }
}

/**
 * Pure: turns raw rows into a [WeeklyReflection] for [ReflectionAggregator.aggregate]'s window.
 * Rows outside the window are ignored, so a caller may pass a superset.
 *
 * It counts and does nothing else. No rate is judged, no trend is drawn, and nothing is ranked:
 * the app has no business interpreting one week of someone's answers (see CLAUDE.md on the
 * 60-90 day evaluation horizon).
 */
object ReflectionAggregator {

    fun aggregate(
        window: ReflectionWindow,
        intentions: List<IntentionEntity>,
        overlayEvents: List<OverlayEventEntity>,
        dailyStats: List<DailyAppStatEntity>,
    ): WeeklyReflection {
        val intentionsByApp = intentions.filter { it.dateEpochDay in window }.groupBy { it.packageName }
        val eventsByApp = overlayEvents.filter { it.dateEpochDay in window }.groupBy { it.packageName }
        val statsByApp = dailyStats.filter { it.dateEpochDay in window }.groupBy { it.packageName }

        val apps = (intentionsByApp.keys + eventsByApp.keys).map { pkg ->
            appReflection(
                packageName = pkg,
                intentions = intentionsByApp[pkg].orEmpty(),
                events = eventsByApp[pkg].orEmpty(),
                stats = statsByApp[pkg].orEmpty(),
            )
        }.sortedWith(
            compareByDescending<AppReflection> { it.promptsShown }
                .thenByDescending { it.pausesShown }
                .thenBy { it.packageName },
        )
        return WeeklyReflection(window, apps)
    }

    private fun appReflection(
        packageName: String,
        intentions: List<IntentionEntity>,
        events: List<OverlayEventEntity>,
        stats: List<DailyAppStatEntity>,
    ): AppReflection {
        val saidCounts = intentions.mapNotNull { it.kind }.groupingBy { it }.eachCount()

        // intentionId null: no prompt that visit. Non-null with a null kind: prompt shown and
        // ignored. The two nulls mean different things (see OverlayEventEntity.intentionId).
        val withoutPrompt = events.count { it.intentionId == null }
        val afterUnanswered = events.count { it.intentionId != null && it.intentionKind == null }
        val outcomesByKind = events
            .filter { it.intentionKind != null }
            .groupBy { it.intentionKind!! }
            .mapValues { (_, rows) -> rows.fold(OutcomeTally()) { tally, row -> tally + row.outcome } }

        val byIntention = IntentionKind.entries.mapNotNull { kind ->
            val said = saidCounts[kind] ?: 0
            val atPause = outcomesByKind[kind] ?: OutcomeTally()
            if (said == 0 && atPause.total == 0) null else IntentionReflection(kind, said, atPause)
        }

        return AppReflection(
            packageName = packageName,
            promptsShown = intentions.size,
            promptsUnanswered = intentions.count { it.kind == null },
            byIntention = byIntention,
            pausesAfterUnansweredPrompt = afterUnanswered,
            pausesWithoutPrompt = withoutPrompt,
            timesOpened = if (stats.any { it.openCount == null }) null else stats.sumOf { it.openCount ?: 0 },
        )
    }
}
