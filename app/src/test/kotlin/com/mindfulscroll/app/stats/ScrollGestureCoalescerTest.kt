package com.mindfulscroll.app.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollGestureCoalescerTest {

    /**
     * One fling as a feed reports it: events arriving every 100 ms (the service's
     * notificationTimeout) for [durationMillis], the length of the deceleration tail.
     */
    private fun fling(startMillis: Long, durationMillis: Long = 1_500L, everyMillis: Long = 100L): List<Long> =
        (startMillis..startMillis + durationMillis step everyMillis).toList()

    @Test
    fun `the first event after coming forward always starts a swipe`() {
        assertThat(ScrollGestureCoalescer.startsNewGesture(previousEventAtMillis = null, eventAtMillis = 5_000L)).isTrue()
    }

    @Test
    fun `a whole fling is one scroll however many events it emits`() {
        val events = fling(startMillis = 10_000L)
        assertThat(events.size).isGreaterThan(10) // the old 300 ms window counted this as 5-6

        assertThat(ScrollGestureCoalescer.countGestures(events)).isEqualTo(1)
    }

    @Test
    fun `the reported 14 swipes count as 14, not 40`() {
        // #25's observation: ~14 real swipes produced ~40 ticks. Swipes a couple of seconds apart,
        // each a 1.5 s fling. With the old rule (300 ms from the last *counted* event) each fling
        // was 5-6 ticks.
        val events = (0 until 14).flatMap { i -> fling(startMillis = 100_000L + i * 3_000L) }

        assertThat(ScrollGestureCoalescer.countGestures(events)).isEqualTo(14)
    }

    @Test
    fun `two quick swipes separated by the idle gap count as two`() {
        val first = fling(startMillis = 0L, durationMillis = 600L)
        val second = fling(startMillis = first.last() + ScrollGestureCoalescer.IDLE_GAP_MILLIS, durationMillis = 600L)

        assertThat(ScrollGestureCoalescer.countGestures(first + second)).isEqualTo(2)
    }

    @Test
    fun `the gap is measured from the last event, not from the start of the swipe`() {
        // Events 799 ms apart, forever: each one extends the swipe, so it never closes. A window
        // measured from the swipe's *first* event would re-open every 800 ms - that was the bug.
        val events = (0L..20_000L step 799L).toList()

        assertThat(ScrollGestureCoalescer.countGestures(events)).isEqualTo(1)
    }

    @Test
    fun `exactly the idle gap opens a new swipe, one millisecond less does not`() {
        val gap = ScrollGestureCoalescer.IDLE_GAP_MILLIS
        assertThat(ScrollGestureCoalescer.startsNewGesture(previousEventAtMillis = 1_000L, eventAtMillis = 1_000L + gap)).isTrue()
        assertThat(ScrollGestureCoalescer.startsNewGesture(previousEventAtMillis = 1_000L, eventAtMillis = 1_000L + gap - 1)).isFalse()
    }

    @Test
    fun `continuous content churn with nobody touching the screen stays one swipe`() {
        // A video's progress label, an animating placeholder: a content-changed event every
        // 250 ms for a minute, no finger anywhere. The count must not keep climbing.
        val churn = (0L..60_000L step 250L).toList()

        assertThat(ScrollGestureCoalescer.countGestures(churn)).isEqualTo(1)
    }

    @Test
    fun `churn with pauses longer than the gap still counts once per burst - the accepted limitation`() {
        // Pinned rather than hidden: a counter that ticks every 2 s is indistinguishable from a
        // short swipe every 2 s without reading screen content, which this app never does. See
        // the class doc of ScrollGestureCoalescer.
        val ticks = (0L..10_000L step 2_000L).toList()

        assertThat(ScrollGestureCoalescer.countGestures(ticks)).isEqualTo(ticks.size)
    }

    @Test
    fun `a clock going backwards is folded into the current swipe, not counted`() {
        assertThat(ScrollGestureCoalescer.startsNewGesture(previousEventAtMillis = 10_000L, eventAtMillis = 5_000L)).isFalse()
    }
}
