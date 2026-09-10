package com.mindfulscroll.app.accessibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundTransitionsTest {

    @Test
    fun `the soft keyboard's window is not an app switch`() {
        assertThat(ForegroundTransitions.isKeyboardWindow("android.inputmethodservice.SoftInputWindow")).isTrue()
    }

    @Test
    fun `ordinary activity and dialog windows still are`() {
        // What the API 30 emulator actually reported for the windows either side of the keyboard.
        assertThat(ForegroundTransitions.isKeyboardWindow("com.android.settings.homepage.SettingsHomepageActivity")).isFalse()
        assertThat(ForegroundTransitions.isKeyboardWindow("com.android.settings.intelligence.search.SearchActivity")).isFalse()
        assertThat(ForegroundTransitions.isKeyboardWindow("android.app.Dialog")).isFalse()
    }

    @Test
    fun `an event with no class name is not assumed to be the keyboard`() {
        // Missing information must fall through to the normal path, which at worst behaves as
        // the app always has, rather than swallow a real app switch.
        assertThat(ForegroundTransitions.isKeyboardWindow(null)).isFalse()
    }
}
