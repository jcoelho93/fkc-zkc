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
import com.mindfulscroll.app.intention.IntentionPromptScreen
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
                    Column(
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
                }
            }
        }
    }
}
