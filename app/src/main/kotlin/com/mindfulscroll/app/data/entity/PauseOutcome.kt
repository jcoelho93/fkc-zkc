package com.mindfulscroll.app.data.entity

/**
 * The user's own answer to "did you get what you came for?", asked on the pause screen about the
 * intention they gave when they opened the app.
 *
 * Deliberately three options rather than yes/no. The honest answer to "did scrolling give you the
 * connection you were after?" is usually neither - and forcing it into a binary would push people
 * towards whichever end felt less like an admission, which is exactly the distortion the weekly
 * report (#6) cannot afford.
 *
 * Not scored, ranked or totalled anywhere. NOT_REALLY is not a failure and YES is not a win; the
 * report compares hope against outcome and says nothing about which answer is better.
 */
enum class PauseOutcome {
    YES,
    KIND_OF,
    NOT_REALLY,
}
