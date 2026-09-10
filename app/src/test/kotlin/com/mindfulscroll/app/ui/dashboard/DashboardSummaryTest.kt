package com.mindfulscroll.app.ui.dashboard

import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.entity.DailyAppStatEntity
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DashboardSummaryTest {

    /** A Monday, so the day labels are predictable. */
    private val monday = LocalDate.of(2026, 9, 7).toEpochDay()

    private fun stat(
        packageName: String,
        day: Long,
        minutes: Long = 0,
        opens: Int? = 0,
        scrolls: Int = 0,
    ) = DailyAppStatEntity(
        packageName = packageName,
        dateEpochDay = day,
        scrollCount = scrolls,
        foregroundTimeMillis = minutes * 60_000L,
        updatedAtMillis = 0,
        openCount = opens,
    )

    @Test
    fun `each day sums time, opens and scrolls across apps, separately`() {
        val bars = summarizeDays(
            listOf(
                stat("a", monday, minutes = 10, opens = 5, scrolls = 3),
                stat("b", monday, minutes = 20, opens = 1, scrolls = 0),
            ),
            firstEpochDay = monday,
            locale = Locale.ENGLISH,
        )

        assertThat(bars).hasSize(7)
        assertThat(bars[0]).isEqualTo(DayBar(label = "Mon", scrollCount = 3, foregroundMinutes = 30, openCount = 6))
    }

    @Test
    fun `a day with nothing recorded is a real zero`() {
        val bars = summarizeDays(emptyList(), firstEpochDay = monday, locale = Locale.ENGLISH)

        assertThat(bars.map { it.openCount }).containsExactly(0, 0, 0, 0, 0, 0, 0).inOrder()
        assertThat(bars.map { it.label }).containsExactly("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").inOrder()
    }

    @Test
    fun `a day with any uncounted row has no open total rather than a partial one`() {
        // The day the update landed: one app's row predates open counting, the other's doesn't.
        // Summing to 4 would present a partial day as a whole one.
        val bars = summarizeDays(
            listOf(
                stat("a", monday, minutes = 15, opens = null),
                stat("b", monday, minutes = 5, opens = 4),
            ),
            firstEpochDay = monday,
        )

        assertThat(bars[0].openCount).isNull()
        assertThat(bars[0].foregroundMinutes).isEqualTo(20) // time was always counted
    }

    @Test
    fun `the opens label says not counted instead of zero`() {
        assertThat(opensLabel(null)).isEqualTo("opens not counted today")
        assertThat(opensLabel(0)).isEqualTo("opened 0 times")
        assertThat(opensLabel(1)).isEqualTo("opened 1 time")
        assertThat(opensLabel(14)).isEqualTo("opened 14 times")
    }
}
