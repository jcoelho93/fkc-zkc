package com.mindfulscroll.app.ui.appselection

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

/**
 * The app picker, used both as an onboarding step and - via Settings - to edit the list later.
 *
 * [onBack] is what distinguishes the two. During onboarding there is nowhere to go back to (the
 * step is part of a linear flow), so it is null and no back arrow is shown. Opened from Settings
 * it is non-null, which also changes the confirm button's wording: "continue" is the language of
 * a flow you are partway through, not of editing a list you already have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: AppSelectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose apps to monitor") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            // navigationBarsPadding() before the 16dp, not after: the app draws edge to edge
            // (MainActivity calls enableEdgeToEdge), and Scaffold insets only its CONTENT slot -
            // a custom bottomBar is on its own. Without this the button sits under the system
            // navigation bar.
            Box(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                Button(
                    onClick = { viewModel.confirmSelection(onDone) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                ) {
                    Text(
                        when {
                            state.selectedPackageNames.isNotEmpty() ->
                                "Monitor ${state.selectedPackageNames.size} app(s)"
                            onBack != null -> "Save - monitor no apps"
                            else -> "Continue without monitoring any app"
                        },
                    )
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding)) {
            Text(
                text = "We've pre-checked apps known for infinite feeds, if installed. " +
                    "Pick whichever you want - you can change this later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(state.apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in state.selectedPackageNames,
                        onToggle = { viewModel.toggle(app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledAppInfo, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        )
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
