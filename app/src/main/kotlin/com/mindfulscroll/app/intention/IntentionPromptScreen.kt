package com.mindfulscroll.app.intention

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.ui.theme.MindfulScrollTheme
import kotlinx.coroutines.delay

/**
 * Neutral by design. None of these is the "right" answer, and none is phrased as a confession -
 * a prompt that made "Distraction" feel like an admission would simply teach people to tap
 * "Checking something specific" instead, and the weekly report would then be comparing the user's
 * hopes against a set of answers they had been nudged into.
 */
private val CHIP_LABELS = listOf(
    IntentionKind.CONNECTION to "Connection",
    IntentionKind.ENTERTAINMENT to "Entertainment",
    IntentionKind.DISTRACTION to "Distraction",
    IntentionKind.HABIT to "Habit",
    IntentionKind.SOMETHING_SPECIFIC to "Checking something specific",
)

/** How long before the dismiss button appears - see the note in IntentionPromptController. */
private const val DISMISS_ENABLED_AFTER_MILLIS = 2_000L

/** How long an untouched prompt stays before removing itself. */
internal const val IDLE_DISMISS_MILLIS = 15_000L

/**
 * How long an ENGAGED prompt tolerates inactivity. Longer than [IDLE_DISMISS_MILLIS] because the
 * user is mid-thought and may be typing, but deliberately finite: the free-text path makes the
 * window focusable, so a prompt with no timeout does not merely linger, it keeps the keyboard
 * away from the app underneath until it is answered. Reset by every further interaction, so it
 * only ever expires after real inactivity.
 */
internal const val ENGAGED_IDLE_DISMISS_MILLIS = 45_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntentionPromptScreen(
    appLabel: String,
    onAnswer: (IntentionKind, String?) -> Unit,
    onDismiss: () -> Unit,
    onRequestTextEntry: () -> Unit,
    idleDismissMillis: Long = IDLE_DISMISS_MILLIS,
    engagedIdleDismissMillis: Long = ENGAGED_IDLE_DISMISS_MILLIS,
) {
    var canDismiss by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var pendingKind by remember { mutableStateOf<IntentionKind?>(null) }

    /** Bumped by every interaction; restarts the dismiss timer below rather than cancelling it. */
    var interactions by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(DISMISS_ENABLED_AFTER_MILLIS)
        canDismiss = true
    }

    // There is ALWAYS a timeout running. Engaging with the prompt lengthens it and restarts it;
    // engaging never removes it.
    //
    // Removing it is what this used to do, and it was a real bug: tapping "Checking something
    // specific" makes the window focusable so the keyboard can open, and with no timeout left, a
    // prompt the user then abandoned sat there indefinitely holding keyboard focus away from the
    // app underneath. For a feature whose whole premise is costing the user nothing, on the one
    // path where they were trying to be more thoughtful, that was the worst available outcome.
    LaunchedEffect(interactions) {
        delay(if (interactions == 0) idleDismissMillis else engagedIdleDismissMillis)
        onDismiss()
    }

    MindfulScrollTheme {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "What are you hoping to find?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Opening $appLabel · answer or just keep scrolling",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    if (canDismiss) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CHIP_LABELS.forEach { (kind, label) ->
                        SuggestionChip(
                            onClick = {
                                interactions++
                                if (kind == IntentionKind.SOMETHING_SPECIFIC) {
                                    // The only path that needs the keyboard, so it is also the only
                                    // path that takes focus away from the app underneath.
                                    pendingKind = kind
                                    showNote = true
                                    onRequestTextEntry()
                                } else {
                                    onAnswer(kind, null)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }

                if (showNote) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = {
                            interactions++
                            note = it
                        },
                        label = { Text("What are you checking? (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                onAnswer(pendingKind ?: IntentionKind.SOMETHING_SPECIFIC, note)
                            },
                        ) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}
