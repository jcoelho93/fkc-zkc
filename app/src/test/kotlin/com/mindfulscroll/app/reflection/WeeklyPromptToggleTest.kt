package com.mindfulscroll.app.reflection

import com.google.common.truth.Truth.assertThat
import com.mindfulscroll.app.reflection.WeeklyPromptToggle.Action
import org.junit.Test

/** The opt-in rules for POST_NOTIFICATIONS (#6, #33): asked only when switched on, and a no is final. */
class WeeklyPromptToggleTest {

    private val api33 = 33
    private val api30 = 30

    @Test
    fun `switching off never asks for anything`() {
        assertThat(WeeklyPromptToggle.onSwitchChanged(false, api33, permissionGranted = false)).isEqualTo(Action.TURN_OFF)
        assertThat(WeeklyPromptToggle.onSwitchChanged(false, api33, permissionGranted = true)).isEqualTo(Action.TURN_OFF)
        assertThat(WeeklyPromptToggle.onSwitchChanged(false, api30, permissionGranted = false)).isEqualTo(Action.TURN_OFF)
    }

    @Test
    fun `switching on requests the permission on Android 13 and later when it is missing`() {
        assertThat(WeeklyPromptToggle.onSwitchChanged(true, api33, permissionGranted = false))
            .isEqualTo(Action.REQUEST_PERMISSION)
    }

    @Test
    fun `switching on with the permission already held turns on without a dialog`() {
        assertThat(WeeklyPromptToggle.onSwitchChanged(true, api33, permissionGranted = true)).isEqualTo(Action.TURN_ON)
    }

    @Test
    fun `below Android 13 there is no runtime permission to ask for`() {
        assertThat(WeeklyPromptToggle.onSwitchChanged(true, api30, permissionGranted = false)).isEqualTo(Action.TURN_ON)
    }

    @Test
    fun `a granted request turns the prompt on, and a declined one leaves it off without asking again`() {
        assertThat(WeeklyPromptToggle.onPermissionResult(granted = true)).isEqualTo(Action.TURN_ON)
        // TURN_OFF, not REQUEST_PERMISSION: nothing re-asks on its own.
        assertThat(WeeklyPromptToggle.onPermissionResult(granted = false)).isEqualTo(Action.TURN_OFF)
    }
}
