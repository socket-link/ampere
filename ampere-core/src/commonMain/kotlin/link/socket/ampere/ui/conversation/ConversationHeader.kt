package link.socket.ampere.ui.conversation

import androidx.compose.foundation.Image
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.ui.widget.header.Header

@Composable
fun ConversationHeader(
    agentDefinition: AgentDefinition,
    onBackClicked: () -> Unit,
    onImportExportClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        elevation = 16.dp,
    ) {
        Header(
            title = agentDefinition.name,
            displayBackIcon = true,
            onBackClicked = onBackClicked,
            onActionIconClicked = onImportExportClicked,
        ) {
            Image(
                imageVector = Icons.TwoTone.Share,
                contentDescription = "Share",
            )
        }
    }
}
