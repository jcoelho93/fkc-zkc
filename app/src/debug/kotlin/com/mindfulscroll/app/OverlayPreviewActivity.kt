package com.mindfulscroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.intention.IntentionPromptScreen
import com.mindfulscroll.app.overlay.InterruptionOverlayScreen
import com.mindfulscroll.app.overlay.OverlayUiState
import com.mindfulscroll.app.ui.theme.MindfulScrollTheme

/**
 * Debug-only host that renders the overlay UIs inside an ordinary activity window.
 *
 * It exists because `adb screencap` does not capture TYPE_ACCESSIBILITY_OVERLAY windows: the
 * interruption overlay and the intention prompt are both provably on screen - present in
 * `dumpsys window windows` as ty=2032, with a measured first frame - and both come out of a
 * screenshot completely invisible. That makes the real windows impossible to review visually, and
 * "I could not see it" indistinguishable from "it did not render", which is the single most
 * expensive confusion available on this project.
 *
 * This does NOT verify the window path - OverlayRenderInstrumentedTest and
 * IntentionPromptInstrumentedTest do that. It verifies only what the composable looks like.
 *
 *     adb shell am start -n com.mindfulscroll.app.debug/com.mindfulscroll.app.OverlayPreviewActivity
 */
class OverlayPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MindfulScrollTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    // Which UI to preview, chosen with:
                    //   adb shell am start -n com.mindfulscroll.app.debug/\
                    //     com.mindfulscroll.app.OverlayPreviewActivity --es screen pause
                    // Defaults to the pause screen, which is the one with real layout to review.
                    when (intent?.getStringExtra("screen")) {
                        "prompt" -> Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            IntentionPromptScreen(
                                appLabel = "Instagram",
                                onAnswer = { _, _ -> },
                                onDismiss = {},
                                onRequestTextEntry = {},
                            )
                        }
                        else -> InterruptionOverlayScreen(
                            state = OverlayUiState(
                                appLabel = "Instagram",
                                scrollCount = 63,
                                sessionMinutes = 11,
                                // "none" previews the degraded path - no intention for this
                                // session, so no recall. Distinguished from an absent extra on
                                // purpose: falling back to a default here would make the one
                                // state worth eyeballing impossible to reach.
                                intentionKind = when (val kind = intent?.getStringExtra("intention")) {
                                    null -> IntentionKind.CONNECTION
                                    "none" -> null
                                    else -> IntentionKind.valueOf(kind)
                                },
                                // Zero so the recall is on screen immediately; the real screen
                                // waits out AppSettings.pauseDurationSeconds first.
                                pauseDurationSeconds = intent?.getIntExtra("pauseSeconds", 0) ?: 0,
                            ),
                            onCloseApp = {},
                            onContinue = {},
                            onOutcome = {},
                        )
                    }
                }
            }
        }
    }
}
