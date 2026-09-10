package com.mindfulscroll.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A deliberately minimal bar chart drawn with Canvas - no charting library, per the MVP's
 * "lightweight, not a heavy dependency" brief. Good enough for 7 bars of daily/weekly counts.
 *
 * A null value means "not measured", not zero. It draws no bar and is labelled "–", so a day
 * that was never counted cannot be mistaken for a day with none.
 */
@Composable
fun SimpleBarChart(
    bars: List<Pair<String, Int?>>,
    barColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    chartHeight: androidx.compose.ui.unit.Dp = 120.dp,
) {
    val maxValue = (bars.maxOfOrNull { it.second ?: 0 } ?: 0).coerceAtLeast(1)

    // Height wraps its content. A fixed chartHeight + 24.dp was shorter than value label + bar +
    // day label, so the day names under the bars were clipped in half.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        bars.forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = value?.toString() ?: "–",
                    style = MaterialTheme.typography.labelLarge,
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight),
                ) {
                    val fraction = (value ?: 0).toFloat() / maxValue.toFloat()
                    val barHeight = size.height * fraction
                    drawRoundRect(
                        color = barColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
