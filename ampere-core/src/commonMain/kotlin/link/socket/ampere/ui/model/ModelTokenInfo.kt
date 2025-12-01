package link.socket.ampere.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.limits.TokenLimits

@Composable
fun ModelTokenInfo(
    limits: TokenLimits,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            modifier = Modifier.padding(bottom = 12.dp),
            text = "Token Limits",
            style = MaterialTheme.typography.subtitle2,
            fontWeight = FontWeight.Bold,
        )

        TokenUsageChart(
            modifier = Modifier.fillMaxWidth(),
            contextWindow = limits.contextWindow,
            maxOutput = limits.maxOutput,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LimitItem(
                label = "Context Window",
                value = "${limits.contextWindow.label} tokens",
            )

            LimitItem(
                label = "Max Output",
                value = "${limits.maxOutput.label} tokens",
            )
        }
    }
}

@Composable
private fun LimitItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = value,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
        )
    }
}
