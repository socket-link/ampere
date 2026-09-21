package link.socket.ampere.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max
import link.socket.ampere.domain.limits.TokenCount
import link.socket.ampere.domain.limits.numericValue

@Composable
fun TokenUsageChart(
    contextWindow: TokenCount,
    maxOutput: TokenCount,
    modifier: Modifier = Modifier,
) {
    val contextValue: Long = getTokenNumericValue(contextWindow)
    val outputValue: Long = getTokenNumericValue(maxOutput)
    val maxValue: Long = max(contextValue, outputValue)

    val minProgress = 0.15f // Minimum 15% width to ensure visibility

    val contextProgress = remember(contextValue, maxValue) {
        if (maxValue > 0) {
            val calculated = (contextValue.toFloat() / maxValue.toFloat())

            if (calculated > 0f) {
                max(calculated, minProgress)
            } else {
                0f
            }
        } else {
            0f
        }
    }

    val outputProgress = remember(outputValue, maxValue) {
        if (maxValue > 0) {
            val calculated = (outputValue.toFloat() / maxValue.toFloat())

            if (calculated > 0f) {
                max(calculated, minProgress)
            } else {
                0f
            }
        } else {
            0f
        }
    }

    Column(modifier = modifier) {
        TokenBar(
            label = "Context Window",
            value = contextWindow.label,
            progress = contextProgress,
            color = Color(0xFF4CAF50),
            isLarger = contextValue >= outputValue,
        )

        Spacer(modifier = Modifier.height(8.dp))

        TokenBar(
            label = "Max Output",
            value = maxOutput.label,
            progress = outputProgress,
            color = Color(0xFF2196F3),
            isLarger = outputValue > contextValue,
        )
    }
}

@Composable
private fun TokenBar(
    label: String,
    value: String,
    progress: Float,
    color: Color,
    isLarger: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment
            .CenterVertically,
    ) {
        Text(
            modifier = Modifier
                .width(80.dp),
            style = MaterialTheme
                .typography.caption,
            color = MaterialTheme
                .colors.onSurface.copy(alpha = 0.8f),
            text = label,
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(
                    color = MaterialTheme
                        .colors.onSurface.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .background(
                        color = color,
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            modifier = Modifier
                .width(60.dp),
            style = MaterialTheme
                .typography.caption,
            fontWeight = if (isLarger) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            },
            textAlign = TextAlign.End,
            color = color,
            text = value,
        )
    }
}

private fun getTokenNumericValue(tokenCount: TokenCount): Long = tokenCount.numericValue
