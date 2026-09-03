package com.mindfulscroll.app.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mindfulscroll.app.data.entity.FrictionMode
import com.mindfulscroll.app.ui.theme.MindfulScrollTheme
import kotlinx.coroutines.delay

@Composable
fun InterruptionOverlayScreen(
    state: OverlayUiState,
    onCloseApp: () -> Unit,
    onContinue: () -> Unit,
) {
    MindfulScrollTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Pause for a moment",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "In ${state.appLabel} you've scrolled ${state.scrollCount} times " +
                        "(${state.sessionMinutes} min this session).",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "What are you looking for right now?",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(40.dp))

                Button(
                    onClick = onCloseApp,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Close app")
                }

                Spacer(Modifier.height(16.dp))

                when (state.frictionMode) {
                    FrictionMode.COUNTDOWN -> CountdownContinueButton(onContinue)
                    FrictionMode.TYPED_PHRASE -> TypedPhraseContinueButton(onContinue)
                }
            }
        }
    }
}

@Composable
private fun CountdownContinueButton(onContinue: () -> Unit) {
    var secondsLeft by remember { mutableIntStateOf(OVERLAY_COUNTDOWN_SECONDS) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(
            onClick = onContinue,
            enabled = secondsLeft == 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (secondsLeft > 0) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text("  5 more minutes ($secondsLeft)")
            } else {
                Text("5 more minutes")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Wait $OVERLAY_COUNTDOWN_SECONDS seconds to make this a deliberate choice.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TypedPhraseContinueButton(onContinue: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val matches = typed.trim().equals(OVERLAY_TYPED_FRICTION_PHRASE, ignoreCase = true)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Type “$OVERLAY_TYPED_FRICTION_PHRASE” to continue:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onContinue,
            enabled = matches,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("5 more minutes")
        }
    }
}
