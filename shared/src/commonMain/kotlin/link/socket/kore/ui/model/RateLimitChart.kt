package link.socket.kore.ui.model

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max
import link.socket.kore.domain.limits.RateLimits
import link.socket.kore.domain.limits.TokenCount

// TODO: Consolidate with ContextUsageDisplay
@Composable
fun RateLimitChart(
    requestsPerMinute: Int,
    inputTokensPerMinute: TokenCount?,
    outputTokensPerMinute: TokenCount?,
    rateLimits: RateLimits,
    modifier: Modifier = Modifier,
) {
    val requestsValue = requestsPerMinute.toLong()
    val inputTokensValue = inputTokensPerMinute?.let { getTokenNumericValue(it) } ?: 0L
    val outputTokensValue = outputTokensPerMinute?.let { getTokenNumericValue(it) } ?: 0L

    // Calculate max requests/min across all available tiers for proper scaling
    val maxRequestsPerMinute = listOfNotNull(
        rateLimits.tierFree?.requestsPerMinute,
        rateLimits.tier1?.requestsPerMinute,
        rateLimits.tier2?.requestsPerMinute,
        rateLimits.tier3?.requestsPerMinute,
        rateLimits.tier4?.requestsPerMinute,
        rateLimits.tier5?.requestsPerMinute,
    ).maxOrNull()?.toLong() ?: requestsValue

    // Calculate max token values for token-based metrics
    val maxTokenValue: Long = max(inputTokensValue, outputTokensValue)

    // Calculate separate progress values for different metric types
    val requestsProgress: Float = if (maxRequestsPerMinute > 0) (requestsValue.toFloat() / maxRequestsPerMinute.toFloat()) else 0f
    val inputProgress: Float = if (maxTokenValue > 0) (inputTokensValue.toFloat() / maxTokenValue.toFloat()) else 0f
    val outputProgress: Float = if (maxTokenValue > 0) (outputTokensValue.toFloat() / maxTokenValue.toFloat()) else 0f

    Column(modifier = modifier) {
        RateLimitBar(
            label = "Requests/Min",
            value = requestsPerMinute.toString(),
            progress = requestsProgress,
            color = Color(0xFF9C27B0),
            isLarger = true, // Always emphasize since it's on its own scale
        )

        if (inputTokensPerMinute != null) {
            Spacer(modifier = Modifier.height(8.dp))

            RateLimitBar(
                label = "Input Tokens/Min",
                value = inputTokensPerMinute.label,
                progress = inputProgress,
                color = Color(0xFF4CAF50),
                isLarger = inputTokensValue >= outputTokensValue,
            )
        }

        if (outputTokensPerMinute != null) {
            Spacer(modifier = Modifier.height(8.dp))

            RateLimitBar(
                label = "Output Tokens/Min",
                value = outputTokensPerMinute.label,
                progress = outputProgress,
                color = Color(0xFF2196F3),
                isLarger = outputTokensValue >= inputTokensValue,
            )
        }
    }
}

@Composable
private fun RateLimitBar(
    label: String,
    value: String,
    progress: Float,
    color: Color,
    isLarger: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.width(100.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f),
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
            text = value,
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.width(80.dp),
        )
    }
}

private fun getTokenNumericValue(tokenCount: TokenCount): Long {
    return when (tokenCount) {
        TokenCount._4k -> 4_000L
        TokenCount._5k -> 5_000L
        TokenCount._4096 -> 4_096L
        TokenCount._8k -> 8_000L
        TokenCount._8192 -> 8_192L
        TokenCount._10k -> 10_000L
        TokenCount._15k -> 15_000L
        TokenCount._16k -> 16_000L
        TokenCount._20k -> 20_000L
        TokenCount._25k -> 25_000L
        TokenCount._30k -> 30_000L
        TokenCount._32k -> 32_000L
        TokenCount._35k -> 35_000L
        TokenCount._40k -> 40_000L
        TokenCount._50k -> 50_000L
        TokenCount._64k -> 64_000L
        TokenCount._80k -> 80_000L
        TokenCount._90k -> 90_000L
        TokenCount._100k -> 100_000L
        TokenCount._128k -> 128_000L
        TokenCount._160k -> 160_000L
        TokenCount._200k -> 200_000L
        TokenCount._250k -> 250_000L
        TokenCount._400k -> 400_000L
        TokenCount._450k -> 450_000L
        TokenCount._800k -> 800_000L
        TokenCount._1m -> 1_000_000L
        TokenCount._2m -> 2_000_000L
        TokenCount._3m -> 3_000_000L
        TokenCount._4m -> 4_000_000L
        TokenCount._5m -> 5_000_000L
        TokenCount._8m -> 8_000_000L
        TokenCount._10m -> 10_000_000L
        TokenCount._30m -> 30_000_000L
        TokenCount._40m -> 40_000_000L
        TokenCount._150m -> 150_000_000L
        TokenCount._180m -> 180_000_000L
        TokenCount._400m -> 400_000_000L
        TokenCount._500m -> 500_000_000L
        TokenCount._1b -> 1_000_000_000L
        TokenCount._5b -> 5_000_000_000L
    }
}
