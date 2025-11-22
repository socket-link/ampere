package link.socket.kore.ui.model

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.socket.kore.domain.tool.ProvidedTool

@Composable
fun ModelToolsSection(
    tools: List<ProvidedTool<*>>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tools) { tool ->
            val toolName = remember(tool) {
                tool::class.simpleName ?: "Tool"
            }
            ToolChip(toolName)
        }
    }
}

@Composable
private fun ToolChip(
    toolName: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = Color(0xFF2196F3).copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                border = BorderStroke(
                    width = 1.dp,
                    color = Color(0xFF2196F3).copy(alpha = 0.3f),
                ),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(
                horizontal = 12.dp,
                vertical = 4.dp,
            ),
    ) {
        Text(
            text = toolName,
            style = MaterialTheme.typography.caption,
            color = Color(0xFF2196F3),
            fontWeight = FontWeight.Medium,
        )
    }
}
