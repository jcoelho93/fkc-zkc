package com.mindfulscroll.app.stats

/** Pure, Android-free logic for "has this session crossed its configured limit?" */
data class ThresholdConfig(
    val scrollThreshold: Int,
    val timeThresholdMinutes: Int,
)

data class SessionState(
    val scrollCount: Int,
    val sessionStartMillis: Long,
)

enum class ThresholdReason {
    SCROLL_COUNT,
    SESSION_TIME,
}

data class ThresholdResult(
    val crossed: Boolean,
    val reasons: Set<ThresholdReason>,
)

object ThresholdEvaluator {

    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * A session crosses its threshold the moment EITHER the scroll count or the continuous
     * foreground time meets or exceeds its configured limit - "whichever comes first", per spec.
     */
    fun evaluate(session: SessionState, config: ThresholdConfig, nowMillis: Long): ThresholdResult {
        require(config.scrollThreshold > 0) { "scrollThreshold must be positive" }
        require(config.timeThresholdMinutes > 0) { "timeThresholdMinutes must be positive" }

        val elapsedMillis = (nowMillis - session.sessionStartMillis).coerceAtLeast(0)
        val timeLimitMillis = config.timeThresholdMinutes * MILLIS_PER_MINUTE

        val reasons = buildSet {
            if (session.scrollCount >= config.scrollThreshold) add(ThresholdReason.SCROLL_COUNT)
            if (elapsedMillis >= timeLimitMillis) add(ThresholdReason.SESSION_TIME)
        }

        return ThresholdResult(crossed = reasons.isNotEmpty(), reasons = reasons)
    }
}
