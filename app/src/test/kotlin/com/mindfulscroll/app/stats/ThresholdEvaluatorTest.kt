package com.mindfulscroll.app.stats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThresholdEvaluatorTest {

    private val config = ThresholdConfig(scrollThreshold = 40, timeThresholdMinutes = 10)
    private val sessionStart = 1_000_000L

    @Test
    fun `not crossed when below both limits`() {
        val session = SessionState(scrollCount = 5, sessionStartMillis = sessionStart)
        val now = sessionStart + 60_000L // 1 minute in

        val result = ThresholdEvaluator.evaluate(session, config, now)

        assertThat(result.crossed).isFalse()
        assertThat(result.reasons).isEmpty()
    }

    @Test
    fun `crossed by scroll count alone`() {
        val session = SessionState(scrollCount = 40, sessionStartMillis = sessionStart)
        val now = sessionStart + 60_000L

        val result = ThresholdEvaluator.evaluate(session, config, now)

        assertThat(result.crossed).isTrue()
        assertThat(result.reasons).containsExactly(ThresholdReason.SCROLL_COUNT)
    }

    @Test
    fun `crossed by session time alone`() {
        val session = SessionState(scrollCount = 3, sessionStartMillis = sessionStart)
        val now = sessionStart + 10 * 60_000L // exactly 10 minutes

        val result = ThresholdEvaluator.evaluate(session, config, now)

        assertThat(result.crossed).isTrue()
        assertThat(result.reasons).containsExactly(ThresholdReason.SESSION_TIME)
    }

    @Test
    fun `crossed by both at once reports both reasons`() {
        val session = SessionState(scrollCount = 41, sessionStartMillis = sessionStart)
        val now = sessionStart + 11 * 60_000L

        val result = ThresholdEvaluator.evaluate(session, config, now)

        assertThat(result.crossed).isTrue()
        assertThat(result.reasons).containsExactly(ThresholdReason.SCROLL_COUNT, ThresholdReason.SESSION_TIME)
    }

    @Test
    fun `just below scroll threshold does not cross`() {
        val session = SessionState(scrollCount = 39, sessionStartMillis = sessionStart)
        val now = sessionStart + 60_000L

        val result = ThresholdEvaluator.evaluate(session, config, now)

        assertThat(result.crossed).isFalse()
    }

    @Test
    fun `just below time threshold does not cross`() {
        val session = SessionState(scrollCount = 1, sessionStartMillis = sessionStart)
        val now = sessionStart + 10 * 60_000L - 1

        val result = ThresholdEvaluator.evaluate(session, config, now)

        assertThat(result.crossed).isFalse()
    }

    @Test
    fun `whichever comes first wins with a low scroll threshold`() {
        val tightConfig = ThresholdConfig(scrollThreshold = 5, timeThresholdMinutes = 30)
        val session = SessionState(scrollCount = 5, sessionStartMillis = sessionStart)
        val now = sessionStart + 5_000L // barely any time has passed

        val result = ThresholdEvaluator.evaluate(session, tightConfig, now)

        assertThat(result.crossed).isTrue()
        assertThat(result.reasons).containsExactly(ThresholdReason.SCROLL_COUNT)
    }

    @Test
    fun `clock skew before session start is treated as zero elapsed time`() {
        val session = SessionState(scrollCount = 1, sessionStartMillis = sessionStart)
        val now = sessionStart - 5_000L // now before start, e.g. device clock adjustment

        val result = ThresholdEvaluator.evaluate(session, config, now)

        assertThat(result.crossed).isFalse()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive scroll threshold is rejected`() {
        ThresholdEvaluator.evaluate(
            SessionState(0, sessionStart),
            ThresholdConfig(scrollThreshold = 0, timeThresholdMinutes = 10),
            sessionStart,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive time threshold is rejected`() {
        ThresholdEvaluator.evaluate(
            SessionState(0, sessionStart),
            ThresholdConfig(scrollThreshold = 40, timeThresholdMinutes = 0),
            sessionStart,
        )
    }
}
