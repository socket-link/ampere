package link.socket.ampere.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val backgroundColor = Color(0xFFF2F2F7)
val bodyTextColor = Color(0xFF575758)
val nonContentCardColor = Color(0xFFEEEEEE)
val iconAlpha = 0.5f
val separatorIconSize = 4.dp
val iconButtonSize = 48.dp
val headerHeight = 64.dp
val conversationCardHeight = 128.dp
val agentCardHeight = 64.dp

@Composable
fun themeColors() =
    MaterialTheme.colors.copy(
        background = backgroundColor,
    )

@Composable
fun themeTypography() =
    with(MaterialTheme.typography) {
        copy(
            body1 = body1.copy(color = bodyTextColor),
            body2 = body2.copy(color = bodyTextColor),
        )
    }

@Composable
fun themeShapes() =
    with(MaterialTheme.shapes) {
        copy(
            small = small.copy(CornerSize(4.dp)),
            medium = medium.copy(CornerSize(12.dp)),
        )
    }
