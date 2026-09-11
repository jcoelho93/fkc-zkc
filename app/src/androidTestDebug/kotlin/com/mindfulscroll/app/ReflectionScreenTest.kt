package com.mindfulscroll.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.reflection.AppReflection
import com.mindfulscroll.app.reflection.IntentionReflection
import com.mindfulscroll.app.reflection.OutcomeTally
import com.mindfulscroll.app.reflection.ReflectionWindow
import com.mindfulscroll.app.reflection.WeeklyReflection
import com.mindfulscroll.app.ui.reflection.ReflectionReport
import com.mindfulscroll.app.ui.reflection.ReflectionTags
import com.mindfulscroll.app.ui.reflection.ReflectionUiState
import com.mindfulscroll.app.ui.settings.WeeklyReflectionSettings
import com.mindfulscroll.app.ui.settings.WeeklyReflectionSettingsTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The weekly reflection screen (#6) and its Settings page (#6, #33), through the stateless
 * composables: no Hilt graph, no database, no notification permission involved.
 */
@RunWith(AndroidJUnit4::class)
class ReflectionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val window = ReflectionWindow.weekEndingOn(LocalDate.of(2026, 9, 10).toEpochDay())

    private val instagram = AppReflection(
        packageName = "com.instagram.android",
        promptsShown = 12,
        promptsUnanswered = 3,
        byIntention = listOf(
            IntentionReflection(
                kind = IntentionKind.CONNECTION,
                saidAtOpening = 5,
                atPause = OutcomeTally(yes = 1, kindOf = 2, notReally = 0, noAnswer = 1),
            ),
            IntentionReflection(IntentionKind.HABIT, saidAtOpening = 4, atPause = OutcomeTally()),
        ),
        pausesAfterUnansweredPrompt = 2,
        pausesWithoutPrompt = 1,
        timesOpened = 15,
    )

    private fun state(
        reflection: WeeklyReflection? = WeeklyReflection(window, listOf(instagram)),
        weekOffset: Int = 0,
        captureEnabled: Boolean = true,
    ) = ReflectionUiState(
        weekOffset = weekOffset,
        reflection = reflection,
        appLabels = mapOf("com.instagram.android" to "Instagram"),
        intentionCaptureEnabled = captureEnabled,
    )

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun rendersEachAppsIntentionsNextToThePauseAnswers() {
        compose.setContent { ReflectionReport(state(), onPreviousWeek = {}, onNextWeek = {}) }

        compose.onNodeWithText("Past 7 days").assertIsDisplayed()
        compose.onNodeWithText("4 – 10 Sep", substring = true).assertExists()
        compose.onNodeWithTag(ReflectionTags.CHART).assertExists()

        scrollTo("Instagram")
        compose.onNodeWithText("Instagram").assertIsDisplayed()
        compose.onNodeWithText("Opened 15 times · asked 12 times · no answer 3 times").assertExists()

        scrollTo("Connection")
        compose.onNodeWithText("Your answer 5 of 12 times asked").assertExists()
        compose.onNodeWithText("At the pause, 4 times: yes 1 · kind of 2 · not really 0 · no answer 1").assertExists()

        scrollTo("Habit")
        compose.onNodeWithText("Your answer 4 of 12 times asked").assertExists()
        compose.onNodeWithText("No pause on these visits.").assertExists()
    }

    @Test
    fun unansweredQuestionsAndUnaskedPausesAreShownNotDropped() {
        compose.setContent { ReflectionReport(state(), onPreviousWeek = {}, onNextWeek = {}) }

        // The chart's own "No answer" bar, alongside the answers that were given.
        compose.onNodeWithText("No answer").assertExists()
        compose.onNodeWithText("Specific").assertDoesNotExist() // never answered, so no bar

        scrollTo("with no question at opening")
        compose.onNodeWithText("1 pause with no question at opening.").assertExists()
        compose.onNodeWithText(
            "2 pauses after a question left unanswered at opening, so nothing to ask about.",
        ).assertExists()
    }

    @Test
    fun anEmptyWeekSaysSoAndSaysWhyWhenCaptureIsOff() {
        compose.setContent {
            ReflectionReport(
                state(reflection = WeeklyReflection(window, emptyList()), captureEnabled = false),
                onPreviousWeek = {},
                onNextWeek = {},
            )
        }
        compose.onNodeWithTag(ReflectionTags.EMPTY).assertExists()
        compose.onNodeWithText("Nothing recorded for these days.").assertIsDisplayed()
        compose.onNodeWithText("The question at opening is off in Settings", substring = true).assertExists()
        compose.onNodeWithTag(ReflectionTags.CHART).assertDoesNotExist()
    }

    @Test
    fun weekArrowsMoveBackAndForwardWithinTheKeptHistory() {
        var back = 0
        var forward = 0
        compose.setContent {
            ReflectionReport(state(weekOffset = 0), onPreviousWeek = { back++ }, onNextWeek = { forward++ })
        }
        // Nothing lies after the present week.
        compose.onNodeWithTag(ReflectionTags.NEXT_WEEK).assertIsNotEnabled()
        compose.onNodeWithTag(ReflectionTags.PREVIOUS_WEEK).assertIsEnabled().performClick()
        assertEquals(1, back)
        assertEquals(0, forward)
    }

    @Test
    fun theOldestKeptWeekCannotGoFurtherBack() {
        compose.setContent {
            ReflectionReport(state(weekOffset = 12), onPreviousWeek = {}, onNextWeek = {})
        }
        compose.onNodeWithText("12 weeks back").assertIsDisplayed()
        compose.onNodeWithTag(ReflectionTags.PREVIOUS_WEEK).assertIsNotEnabled()
        compose.onNodeWithTag(ReflectionTags.NEXT_WEEK).assertIsEnabled()
    }

    @Test
    fun weeklyPromptSettingsReportTheSwitchAndOpenTheReflection() {
        val switched = mutableListOf<Boolean>()
        var opened = 0
        compose.setContent {
            Column {
                WeeklyReflectionSettings(
                    enabled = false,
                    permissionDeclined = false,
                    notificationsBlocked = false,
                    onSwitchChanged = { switched += it },
                    onOpenReflection = { opened++ },
                    onOpenNotificationSettings = {},
                )
            }
        }
        compose.onNodeWithTag(WeeklyReflectionSettingsTags.SWITCH).assertIsOff().performClick()
        compose.onNodeWithText("Open the reflection").performClick()
        // No note and no settings button when nothing is wrong.
        compose.onNodeWithText("Notification settings").assertDoesNotExist()

        assertEquals(listOf(true), switched)
        assertEquals(1, opened)
    }

    @Test
    fun aDeclinedPermissionLeavesTheSwitchOffAndSaysWhy() {
        var openedSettings = 0
        compose.setContent {
            Column {
                WeeklyReflectionSettings(
                    enabled = false,
                    permissionDeclined = true,
                    notificationsBlocked = true,
                    onSwitchChanged = {},
                    onOpenReflection = {},
                    onOpenNotificationSettings = { openedSettings++ },
                )
            }
        }
        compose.onNodeWithTag(WeeklyReflectionSettingsTags.SWITCH).assertIsOff()
        compose.onNodeWithText("notification access", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Notification settings").performClick()
        assertEquals(1, openedSettings)
    }

    @Test
    fun anEnabledPromptWithNotificationsBlockedSaysItCannotArrive() {
        compose.setContent {
            Column {
                WeeklyReflectionSettings(
                    enabled = true,
                    permissionDeclined = false,
                    notificationsBlocked = true,
                    onSwitchChanged = {},
                    onOpenReflection = {},
                    onOpenNotificationSettings = {},
                )
            }
        }
        compose.onNodeWithTag(WeeklyReflectionSettingsTags.SWITCH).assertIsOn()
        compose.onNodeWithText("off in Android settings", substring = true).assertIsDisplayed()
    }
}
