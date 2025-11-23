package link.socket.ampere.ui.model

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.ai.model.AIModelFeatures

@Composable
fun ModelSupportedInputsSection(
    supportedInputs: AIModelFeatures.SupportedInputs,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement
            .spacedBy(10.dp),
    ) {
        InputTypeChip("Text", supportedInputs.text, Modifier.weight(1f))
        InputTypeChip("Image", supportedInputs.image, Modifier.weight(1f))
        InputTypeChip("PDF", supportedInputs.pdf, Modifier.weight(1f))
        InputTypeChip("Audio", supportedInputs.audio, Modifier.weight(1f))
        InputTypeChip("Video", supportedInputs.video, Modifier.weight(1f))
    }
}

@Composable
private fun InputTypeChip(
    inputType: String,
    isSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = if (isSupported) {
                    Color(0xFF4CAF50)
                        .copy(alpha = 0.12f)
                } else {
                    MaterialTheme
                        .colors.onSurface.copy(alpha = 0.06f)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                border = BorderStroke(
                    width = if (isSupported) 2.dp else 1.dp,
                    color = if (isSupported) {
                        Color(0xFF4CAF50)
                            .copy(alpha = 0.6f)
                    } else {
                        MaterialTheme
                            .colors.onSurface.copy(alpha = 0.15f)
                    },
                ),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
        contentAlignment = Alignment
            .Center,
    ) {
        Text(
            style = MaterialTheme
                .typography.body2,
            color = if (isSupported) {
                Color(0xFF4CAF50)
            } else {
                MaterialTheme
                    .colors.onSurface.copy(alpha = 0.6f)
            },
            fontWeight = if (isSupported) {
                FontWeight.SemiBold
            } else {
                FontWeight.Normal
            },
            text = inputType,
        )
    }
}
