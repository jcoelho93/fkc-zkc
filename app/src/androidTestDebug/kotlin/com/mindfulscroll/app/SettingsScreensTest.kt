package com.mindfulscroll.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mindfulscroll.app.data.entity.MonitoredAppEntity
import com.mindfulscroll.app.ui.navigation.Routes
import com.mindfulscroll.app.ui.settings.SettingsMenu
import com.mindfulscroll.app.ui.settings.ThresholdEditor
import com.mindfulscroll.app.ui.settings.ThresholdList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The restructured Settings (#26): a menu whose rows show their current values and open the
 * right page, a per-app list, and the slider editor that replaced the two-number dialog.
 * Driven through the stateless composables, so no Hilt graph or database is involved.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun app(pkg: String, label: String, scrolls: Int = 40, minutes: Int = 10, monitored: Boolean = true) =
        MonitoredAppEntity(
            packageName = pkg,
            appLabel = label,
            isMonitored = monitored,
            scrollThreshold = scrolls,
            timeThresholdMinutes = minutes,
            addedAtMillis = 0,
        )

    @Test
    fun menuRowsShowTheirCurrentValuesAndOpenTheirPages() {
        val opened = mutableListOf<String>()
        var diagnostics = 0
        compose.setContent {
            SettingsMenu(
                apps = listOf(app("a", "Instagram"), app("b", "Reddit", minutes = 15)),
                intentionCaptureEnabled = false,
                pauseDurationSeconds = 25,
                onOpenPage = { opened += it },
                onOpenDiagnostics = { diagnostics++ },
            )
        }

        // The values answer the question without opening anything.
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("25 sec").assertIsDisplayed()
        compose.onNodeWithText("Per app").assertIsDisplayed()
        compose.onNodeWithText("2 apps").assertIsDisplayed()

        compose.onNodeWithText("Ask what I'm looking for").performClick()
        compose.onNodeWithText("Pause length").performClick()
        compose.onNodeWithText("When the pause appears").performClick()
        compose.onNodeWithText("Monitored apps").performClick()
        compose.onNodeWithText("Diagnostics").performClick()

        assertEquals(
            listOf(
                Routes.SETTINGS_INTENTION,
                Routes.SETTINGS_PAUSE_LENGTH,
                Routes.SETTINGS_THRESHOLDS,
                Routes.EDIT_MONITORED_APPS,
            ),
            opened,
        )
        assertEquals(1, diagnostics)
    }

    @Test
    fun theMenuNoLongerListsAppsInline() {
        compose.setContent {
            SettingsMenu(
                apps = listOf(app("a", "Instagram"), app("b", "Reddit")),
                intentionCaptureEnabled = true,
                pauseDurationSeconds = 20,
                onOpenPage = {},
                onOpenDiagnostics = {},
            )
        }
        // The unbounded part of the old screen: one card per app. It lives behind a row now.
        assertEquals(0, compose.onAllNodesWithText("Instagram").fetchSemanticsNodes().size)
        compose.onNodeWithText("40 scrolls or 10 min").assertIsDisplayed() // shared threshold, spelled out
    }

    @Test
    fun thePerAppListOpensAnAppsEditorAndTogglesMonitoring() {
        var edited: String? = null
        val toggled = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            Column {
                ThresholdList(
                    apps = listOf(app("com.instagram.android", "Instagram"), app("com.reddit.frontpage", "Reddit", monitored = false)),
                    onEditApp = { edited = it },
                    onMonitoredChange = { a, on -> toggled += a.packageName to on },
                    onChooseApps = {},
                )
            }
        }

        compose.onNodeWithText("40 scrolls or 10 min").assertIsDisplayed()
        compose.onNodeWithText("Not monitored right now").assertIsDisplayed()

        compose.onNodeWithText("Instagram").performClick()
        assertEquals("com.instagram.android", edited)

        val switches = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        switches[1].performClick()
        assertEquals(listOf("com.reddit.frontpage" to true), toggled)
    }

    @Test
    fun theEditorShowsTheCurrentThresholdAndSavesWhatTheSlidersAreSetTo() {
        val saved = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            Column {
                ThresholdEditor(app = app("a", "Instagram", scrolls = 40, minutes = 10)) { s, m -> saved += s to m }
            }
        }

        compose.onNodeWithText("40 scrolls").assertIsDisplayed()
        compose.onNodeWithText("10 minutes in a row").assertIsDisplayed()

        val sliders = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        sliders[0].performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        sliders[1].performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }

        compose.onNodeWithText("100 scrolls").assertIsDisplayed()
        compose.onNodeWithText("25 minutes in a row").assertIsDisplayed()
        // SetProgress runs onValueChange and then onValueChangeFinished, so each drag saves once
        // with both current values.
        assertEquals(listOf(100 to 10, 100 to 25), saved)
    }
}
