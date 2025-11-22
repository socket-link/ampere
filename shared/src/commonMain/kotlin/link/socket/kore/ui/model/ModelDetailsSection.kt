package link.socket.kore.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.socket.kore.domain.ai.model.AIModel
import link.socket.kore.domain.ai.model.AIModelFeatures

@Composable
fun ModelDetailsSection(
    selectedModel: AIModel,
    modifier: Modifier = Modifier,
) {
    val features: AIModelFeatures = remember(selectedModel) {
        selectedModel.features
    }

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp),
            verticalArrangement = Arrangement
                .spacedBy(16.dp),
        ) {
            // Header
            Text(
                style = MaterialTheme
                    .typography.h6,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4A5568),
                text = "Model Details",
            )

            // Model Overview
            ModelOverviewSection(
                modifier = Modifier
                    .fillMaxWidth(),
                features = features,
            )

            // Supported Inputs
            Text(
                style = MaterialTheme
                    .typography.subtitle2,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4A5568),
                text = "Supported Inputs",
            )

            ModelSupportedInputsSection(
                modifier = Modifier
                    .fillMaxWidth(),
                supportedInputs = features.supportedInputs,
            )

            // Technical Specifications
            Text(
                style = MaterialTheme
                    .typography.subtitle2,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4A5568),
                text = "Technical Specifications",
            )

            ModelLimitsSection(
                modifier = Modifier
                    .fillMaxWidth(),
                limits = selectedModel.limits,
                trainingCutoffDate = selectedModel.features.trainingCutoffDate,
            )
        }
    }
}
