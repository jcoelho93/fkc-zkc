package com.mindfulscroll.app.ui.settings

import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import org.junit.Test

class SettingsLabelsTest {

    private fun app(pkg: String, scrolls: Int = 40, minutes: Int = 10, monitored: Boolean = true) = MonitoredAppEntity(
        packageName = pkg,
        appLabel = pkg,
        isMonitored = monitored,
        scrollThreshold = scrolls,
        timeThresholdMinutes = minutes,
        addedAtMillis = 0,
    )

    @Test
    fun `simple values`() {
        assertThat(SettingsLabels.onOff(true)).isEqualTo("On")
        assertThat(SettingsLabels.onOff(false)).isEqualTo("Off")
        assertThat(SettingsLabels.pauseLength(20)).isEqualTo("20 sec")
    }

    @Test
    fun `monitored app count`() {
        assertThat(SettingsLabels.monitoredApps(emptyList())).isEqualTo("None yet")
        assertThat(SettingsLabels.monitoredApps(listOf(app("a")))).isEqualTo("1 app")
        assertThat(SettingsLabels.monitoredApps(listOf(app("a"), app("b"), app("c")))).isEqualTo("3 apps")
    }

    @Test
    fun `one shared threshold is spelled out, different ones say per app`() {
        assertThat(SettingsLabels.thresholdSummary(emptyList())).isEqualTo("No apps yet")
        assertThat(SettingsLabels.thresholdSummary(listOf(app("a"), app("b")))).isEqualTo("40 scrolls or 10 min")
        assertThat(SettingsLabels.thresholdSummary(listOf(app("a"), app("b", minutes = 15)))).isEqualTo("Per app")
    }

    @Test
    fun `a switched-off app's threshold doesn't count towards the summary`() {
        val apps = listOf(app("a"), app("b"), app("x", scrolls = 60, minutes = 15, monitored = false))
        assertThat(SettingsLabels.thresholdSummary(apps)).isEqualTo("40 scrolls or 10 min")
        assertThat(SettingsLabels.thresholdSummary(listOf(app("x", monitored = false)))).isEqualTo("None monitored")
    }
}

class ThresholdRangesTest {

    @Test
    fun `ordinary values get the standard range`() {
        assertThat(ThresholdRanges.scrolls(40)).isEqualTo(5f..200f)
        assertThat(ThresholdRanges.minutes(10)).isEqualTo(1f..60f)
    }

    @Test
    fun `a value the old dialog allowed widens the range instead of being clamped`() {
        // The dialog accepted any positive number. Opening the editor must not silently rewrite
        // 500 to 200 the first time a slider is touched.
        assertThat(ThresholdRanges.scrolls(500)).isEqualTo(5f..500f)
        assertThat(ThresholdRanges.scrolls(2)).isEqualTo(2f..200f)
        assertThat(ThresholdRanges.minutes(90)).isEqualTo(1f..90f)
    }

    @Test
    fun `scrolls snap to round numbers and never below the minimum`() {
        assertThat(ThresholdRanges.snapScrolls(42.4f)).isEqualTo(40f)
        assertThat(ThresholdRanges.snapScrolls(43f)).isEqualTo(45f)
        assertThat(ThresholdRanges.snapScrolls(1f)).isEqualTo(5f)
    }
}
