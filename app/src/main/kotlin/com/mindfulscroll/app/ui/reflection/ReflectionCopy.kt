package com.mindfulscroll.app.ui.reflection

import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.reflection.AppReflection
import com.mindfulscroll.app.reflection.OutcomeTally
import com.mindfulscroll.app.reflection.ReflectionWindow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every sentence the reflection says, as pure functions so the wording can be tested.
 *
 * The rules (CONTRIBUTING.md, "Writing user-facing copy") matter more here than anywhere else in
 * the app, because this is the one screen that puts the user's own answers side by side. It
 * gives counts with their denominators and nothing more: no rates judged, nothing ranked, no
 * "only", no "more than last week". Unanswered questions are always stated, never dropped.
 */
internal object ReflectionCopy {

    /** Same words as the prompt's chips, so the report reads back what the user actually tapped. */
    fun kindLabel(kind: IntentionKind): String = when (kind) {
        IntentionKind.CONNECTION -> "Connection"
        IntentionKind.ENTERTAINMENT -> "Entertainment"
        IntentionKind.DISTRACTION -> "Distraction"
        IntentionKind.HABIT -> "Habit"
        IntentionKind.SOMETHING_SPECIFIC -> "Checking something specific"
    }

    /**
     * Under a chart bar. Six columns leave room for about nine characters on a phone, and
     * "Connection" or "Distraction" then broke mid-word, so the bars use the chips' stems.
     */
    fun kindChartLabel(kind: IntentionKind): String = when (kind) {
        IntentionKind.CONNECTION -> "Connect"
        IntentionKind.ENTERTAINMENT -> "Entertain"
        IntentionKind.DISTRACTION -> "Distract"
        IntentionKind.HABIT -> "Habit"
        IntentionKind.SOMETHING_SPECIFIC -> "Specific"
    }

    const val NO_ANSWER = "No answer"

    fun times(n: Int): String = if (n == 1) "1 time" else "$n times"

    /** "Opened 15 times · asked 12 times · no answer 3 times", leaving out what was not counted. */
    fun appSummary(app: AppReflection): String = buildList {
        app.timesOpened?.let { add("Opened ${times(it)}") }
        add("asked ${times(app.promptsShown)}")
        add("no answer ${times(app.promptsUnanswered)}")
    }.joinToString(" · ").replaceFirstChar { it.uppercase() }

    /** The denominator is every time the question appeared, answered or not. */
    fun saidLine(said: Int, promptsShown: Int): String = "Your answer $said of ${times(promptsShown)} asked"

    fun pauseLine(tally: OutcomeTally): String =
        if (tally.total == 0) {
            "No pause on these visits."
        } else {
            "At the pause, ${times(tally.total)}: yes ${tally.yes} · kind of ${tally.kindOf} · " +
                "not really ${tally.notReally} · no answer ${tally.noAnswer}"
        }

    fun pausesAfterUnansweredLine(n: Int): String =
        "${pauses(n)} after a question left unanswered at opening, so nothing to ask about."

    fun pausesWithoutPromptLine(n: Int): String = "${pauses(n)} with no question at opening."

    private fun pauses(n: Int): String = if (n == 1) "1 pause" else "$n pauses"

    /** "4 – 10 Sep", or "29 Aug – 4 Sep" across a month boundary. */
    fun windowLabel(window: ReflectionWindow, locale: Locale = Locale.getDefault()): String {
        val first = LocalDate.ofEpochDay(window.startEpochDay)
        val last = LocalDate.ofEpochDay(window.lastEpochDay)
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val startText = if (first.month == last.month && first.year == last.year) {
            first.dayOfMonth.toString()
        } else {
            first.format(dayMonth)
        }
        return "$startText – ${last.format(dayMonth)}"
    }

    fun weekHeading(weekOffset: Int): String = when (weekOffset) {
        0 -> "Past 7 days"
        1 -> "The 7 days before"
        else -> "$weekOffset weeks back"
    }
}
