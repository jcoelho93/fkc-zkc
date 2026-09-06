package com.mindfulscroll.app.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mindfulscroll.app.data.entity.IntentionKind
import com.mindfulscroll.app.data.entity.PauseOutcome
import com.mindfulscroll.app.ui.theme.MindfulScrollTheme
import kotlinx.coroutines.delay

/** Test tags, so instrumented tests can name what they look for instead of matching copy. */
object PauseScreenTags {
    const val BREATHING = "pause_breathing"
    const val RECALL = "pause_recall"
    const val CLOSE_APP = "pause_close_app"
    const val CONTINUE = "pause_continue"
}

/**
 * The mindful pause (#5). Replaces the countdown / typed-phrase gate, which was pure friction: it
 * raised the price of continuing without ever surfacing *why* the user had opened the app.
 *
 * Two things about the structure are deliberate and easy to undo by accident.
 *
 * **Neither exit is ever gated.** Both buttons are live from the first frame, throughout. The
 * urge-surfing phase is an invitation to wait, not a lock - the moment it becomes something to sit
 * out, it is the countdown again under a nicer name, and this screen exists because that did not
 * work.
 *
 * **The recall appears, it does not replace.** After the pause interval the outcome question fades
 * in below what is already on screen, so nothing moves out from under a finger already on its way
 * to a button.
 *
 * Explicitly not here, and not to be added: any streak, score, total or badge. A reward for
 * pausing would reintroduce the exact variable-ratio mechanic this app exists to interrupt.
 */
@Composable
fun InterruptionOverlayScreen(
    state: OverlayUiState,
    onCloseApp: () -> Unit,
    onContinue: () -> Unit,
    onOutcome: (PauseOutcome) -> Unit = {},
) {
    var recallVisible by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf<PauseOutcome?>(null) }

    LaunchedEffect(state.pauseDurationSeconds) {
        delay(state.pauseDurationSeconds * 1_000L)
        recallVisible = true
    }

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
                BreathingCircle()

                Spacer(Modifier.height(32.dp))

                Text(
                    text = "Notice the urge to keep scrolling.",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "It usually peaks and fades within a minute or two, if you watch it " +
                        "instead of acting on it.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "${state.sessionMinutes} min in ${state.appLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )

                AnimatedVisibility(
                    visible = recallVisible && state.intentionKind != null,
                    enter = fadeIn(tween(600)) + expandVertically(tween(600)),
                ) {
                    IntentionRecall(
                        // Safe: this only composes when intentionKind is non-null, and the whole
                        // recall is skipped otherwise - see OverlayUiState.intentionKind for the
                        // three different reasons it can be missing.
                        kind = state.intentionKind ?: IntentionKind.HABIT,
                        note = state.intentionNote,
                        answered = answered,
                        onOutcome = {
                            answered = it
                            onOutcome(it)
                        },
                    )
                }

                Spacer(Modifier.height(40.dp))

                Button(
                    onClick = onCloseApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PauseScreenTags.CLOSE_APP),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Close ${state.appLabel}")
                }

                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PauseScreenTags.CONTINUE),
                ) {
                    Text("Keep scrolling")
                }
            }
        }
    }
}

/**
 * A slow expanding and contracting ring to breathe with. Four seconds each way, which is an
 * unhurried resting pace rather than a technique the user has to be taught.
 *
 * Drawn with Canvas rather than pulled from a library: it is one circle, and this composes inside
 * an accessibility-service window where every extra dependency is another thing R8 has to keep and
 * another thing that can fail to render at the one moment it is needed.
 */
@Composable
private fun BreathingCircle() {
    val transition = rememberInfiniteTransition(label = "breathing")
    val scale by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathing-scale",
    )

    val ringColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .size(160.dp)
            .testTag(PauseScreenTags.BREATHING),
    ) {
        val maxRadius = size.minDimension / 2f
        drawCircle(color = ringColor.copy(alpha = 0.12f), radius = maxRadius)
        drawCircle(
            color = ringColor,
            radius = maxRadius * scale,
            style = Stroke(width = 4.dp.toPx()),
        )
    }
}

/**
 * "You opened it for X - did you get it?", asked only when there is a real answer to quote back.
 *
 * The three options are not a score. "Not really" is not a failure and "Yes" is not a win; the
 * weekly report (#6) compares what the user hoped for against what they felt they got, and says
 * nothing about which answer is the good one. Wording that implied otherwise would make the answer
 * worth managing, and an answer worth managing is worthless.
 */
@Composable
private fun IntentionRecall(
    kind: IntentionKind,
    note: String?,
    answered: PauseOutcome?,
    onOutcome: (PauseOutcome) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(top = 32.dp)
            .testTag(PauseScreenTags.RECALL),
    ) {
        Text(
            text = "You opened it for ${kind.label()}.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "“$note”",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (answered == null) "Did you get it?" else "Thanks - noted.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PauseOutcome.entries.forEach { outcome ->
                val selected = answered == outcome
                SuggestionChip(
                    // Answering twice would write a second, contradictory row for one question.
                    onClick = { if (answered == null) onOutcome(outcome) },
                    label = { Text(outcome.label()) },
                    colors = if (selected) {
                        SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    } else {
                        SuggestionChipDefaults.suggestionChipColors()
                    },
                )
            }
        }
    }
}

/** Lower case on purpose: it is read inside a sentence, not as a category label. */
private fun IntentionKind.label(): String = when (this) {
    IntentionKind.CONNECTION -> "connection"
    IntentionKind.ENTERTAINMENT -> "entertainment"
    IntentionKind.DISTRACTION -> "distraction"
    IntentionKind.HABIT -> "habit"
    IntentionKind.SOMETHING_SPECIFIC -> "something specific"
}

private fun PauseOutcome.label(): String = when (this) {
    PauseOutcome.YES -> "Yes"
    PauseOutcome.KIND_OF -> "Kind of"
    PauseOutcome.NOT_REALLY -> "Not really"
}
