package com.mindfulscroll.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mindfulscroll.app.ui.navigation.MindfulScrollNavHost
import com.mindfulscroll.app.ui.theme.MindfulScrollTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MindfulScrollApp()
        }
    }
}

@Composable
private fun MindfulScrollApp() {
    MindfulScrollTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MindfulScrollNavHost()
        }
    }
}
