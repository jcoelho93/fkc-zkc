package com.mindfulscroll.app.stats

/**
 * Pure, Android-free logic for "is this scroll-related event the start of a new swipe?", so that
 * one physical gesture counts as one scroll however many events it emits (#25).
 *
 * A fling doesn't stop when the finger lifts. It decelerates for a second or two, and a feed
 * emits TYPE_VIEW_SCROLLED and TYPE_WINDOW_CONTENT_CHANGED for the whole of that tail. The old
 * rule was a fixed 300 ms window measured from the last *counted* event, so it re-opened three to
 * six times inside a single fling. 14 real swipes counted as ~40, which is exactly the default
 * threshold, so the pause fired after a third of the scrolling it was set for.
 *
 * The rule now: an event starts a new swipe only after [IDLE_GAP_MILLIS] of silence. Every event
 * inside a swipe, of either type, pushes the end of the swipe forward. So a whole fling is one
 * scroll, and so is a burst of content churn.
 *
 * What this deliberately trades away: the count now errs low, never high. Content that changes
 * continuously (an autoplaying video's progress label, say) keeps a single swipe open, so real
 * swipes over it merge into it and are not counted. Churn that pauses for longer than the gap
 * between bursts still counts one tick per burst. Scroll count was already best-effort (Compose
 * feeds fire neither event) and foreground time is the trustworthy half of the threshold. A
 * best-effort count should under-report rather than invent scrolls the user never made.
 */
object ScrollGestureCoalescer {

    /**
     * Long enough to cover a fling's deceleration tail, short enough that two deliberate swipes
     * in quick succession still count as two. Chosen in #25 at the low end of 800 ms to 1 s,
     * because 1 s started merging genuinely separate quick swipes.
     */
    const val IDLE_GAP_MILLIS = 800L

    /**
     * @param previousEventAtMillis when the previous scroll-related event in the same foreground
     *   app arrived (monotonic clock), or null if there has been none since the app came forward.
     * @param eventAtMillis when this event arrived, on the same clock.
     * @return true if this event opens a new swipe and should be counted as one scroll.
     *
     * A negative gap can't come from a monotonic clock. If it ever appears, the event is treated
     * as part of the current swipe, because the error then goes towards under-counting.
     */
    fun startsNewGesture(
        previousEventAtMillis: Long?,
        eventAtMillis: Long,
        idleGapMillis: Long = IDLE_GAP_MILLIS,
    ): Boolean = previousEventAtMillis == null || eventAtMillis - previousEventAtMillis >= idleGapMillis

    /** Replays a sequence of event times through [startsNewGesture] and returns how many swipes it holds. */
    fun countGestures(eventTimesMillis: List<Long>, idleGapMillis: Long = IDLE_GAP_MILLIS): Int {
        var previous: Long? = null
        return eventTimesMillis.count { eventAt ->
            startsNewGesture(previous, eventAt, idleGapMillis).also { previous = eventAt }
        }
    }
}
