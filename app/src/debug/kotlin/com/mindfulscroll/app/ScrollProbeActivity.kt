package com.mindfulscroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Debug-build-only: a long Compose LazyColumn standing in for a "feed"
 * (Instagram/Reddit/TikTok-style) so ScrollEventDetectionTest can empirically check which
 * AccessibilityEvent types actually fire when it's scrolled - see that test for why this
 * matters. Lives in src/debug rather than src/androidTest so it compiles into the app's own
 * debug APK - see app/src/debug/AndroidManifest.xml for why that's required.
 */
class ScrollProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.testTag(SCROLL_PROBE_LIST_TAG)) {
                        items(count = 500) { index ->
                            Text(
                                text = "Probe item #$index",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val SCROLL_PROBE_LIST_TAG = "scroll_probe_list"
    }
}
