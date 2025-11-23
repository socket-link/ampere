package link.socket.ampere.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.ai.model.AIModelFeatures
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed

@Composable
fun ModelOverviewSection(
    features: AIModelFeatures,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement
            .spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement
                .spacedBy(16.dp),
        ) {
            PerformanceChip(
                modifier = Modifier
                    .weight(1f),
                label = "Reasoning",
                value = features.reasoningLevel.name,
                color = when (features.reasoningLevel) {
                    RelativeReasoning.HIGH -> Color(0xFF4CAF50)
                    RelativeReasoning.NORMAL -> Color(0xFFFF9800)
                    RelativeReasoning.LOW -> Color(0xFFF44336)
                },
            )

            PerformanceChip(
                modifier = Modifier
                    .weight(1f),
                label = "Speed",
                value = features.speed.name,
                color = when (features.speed) {
                    RelativeSpeed.FAST -> Color(0xFF4CAF50)
                    RelativeSpeed.NORMAL -> Color(0xFFFF9800)
                    RelativeSpeed.SLOW -> Color(0xFFF44336)
                },
            )
        }
    }
}
