package link.socket.ampere.ui.agent

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun PreviewAgentCreationScreen() {
    AgentSelectionSection(
        modifier = Modifier
            .fillMaxSize(),
        onAgentPartiallySelected = {},
    )
}
