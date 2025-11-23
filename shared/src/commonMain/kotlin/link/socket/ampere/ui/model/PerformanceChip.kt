package link.socket.ampere.ui.model

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PerformanceChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = remember(color) {
        color.copy(alpha = 0.15f)
    }

    val borderColor = remember(color) {
        color.copy(alpha = 0.4f)
    }

    val labelColor = remember {
        Color(0xFF6B7280)
    }

    Column(
        modifier = modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                BorderStroke(
                    width = 2.dp,
                    color = borderColor,
                ),
                RoundedCornerShape(12.dp),
            )
            .padding(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
        horizontalAlignment = Alignment
            .CenterHorizontally,
    ) {
        Text(
            style = MaterialTheme
                .typography.caption,
            fontWeight = FontWeight
                .Medium,
            color = labelColor,
            text = label,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            style = MaterialTheme
                .typography.subtitle2,
            fontWeight = FontWeight
                .Bold,
            color = color,
            text = value,
        )
    }
}
