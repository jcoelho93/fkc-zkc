package com.mindfulscroll.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mindfulscroll.app.ui.navigation.MindfulScrollNavHost
import com.mindfulscroll.app.ui.navigation.Routes
import com.mindfulscroll.app.ui.theme.MindfulScrollTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** A page to open once the nav host is up: set by the weekly reflection notification. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: after a rotation the back stack already holds the page.
        if (savedInstanceState == null) pendingRoute = routeFor(intent)
        setContent {
            MindfulScrollAppRoot(
                pendingRoute = pendingRoute,
                onPendingRouteOpened = { pendingRoute = null },
            )
        }
    }

    // launchMode="singleTop": tapping the notification while the app is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { pendingRoute = it }
    }

    private fun routeFor(intent: Intent?): String? =
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_REFLECTION -> Routes.REFLECTION
            else -> null
        }

    companion object {
        const val EXTRA_OPEN = "com.mindfulscroll.app.OPEN"
        const val OPEN_REFLECTION = "reflection"
    }
}

@Composable
private fun MindfulScrollAppRoot(pendingRoute: String?, onPendingRouteOpened: () -> Unit) {
    MindfulScrollTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MindfulScrollNavHost(pendingRoute = pendingRoute, onPendingRouteOpened = onPendingRouteOpened)
        }
    }
}
