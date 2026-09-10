package com.mindfulscroll.app.accessibility

/**
 * Pure, Android-free decisions about which TYPE_WINDOW_STATE_CHANGED events mean "the user moved
 * to a different app", as opposed to "a window appeared on top of the one they are still in".
 */
object ForegroundTransitions {

    /**
     * The window class every soft keyboard is drawn in. InputMethodService creates it for the
     * IME, whatever the keyboard app is, so this is the framework's class and not any one
     * keyboard's.
     */
    const val SOFT_INPUT_WINDOW_CLASS = "android.inputmethodservice.SoftInputWindow"

    /**
     * True for the keyboard's own window, which must never count as a foreground change.
     *
     * Treating it as one was a silent failure of the usual shape here. When the keyboard opens
     * it raises TYPE_WINDOW_STATE_CHANGED under the *keyboard's* package (seen on the API 30
     * emulator as `com.google.android.inputmethod.latin`). Closing it raises nothing for the app
     * underneath. So typing a single comment in a monitored feed ended its session, cancelled the
     * time-threshold timer and parked "foreground" on the keyboard. Every scroll after that was
     * dropped for not matching, and nothing re-armed until the app happened to open another
     * window. The same event also hid the intention prompt the moment its own free-text field
     * brought the keyboard up.
     *
     * Matched on the window class rather than a list of keyboard packages: there are countless
     * keyboards, and they all use this one class.
     */
    fun isKeyboardWindow(className: String?): Boolean = className == SOFT_INPUT_WINDOW_CLASS
}
