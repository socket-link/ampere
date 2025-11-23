package link.socket.ampere.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.chat.Conversation
import link.socket.ampere.ui.theme.conversationCardHeight
import link.socket.ampere.ui.theme.themeTypography

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .requiredHeight(conversationCardHeight),
        elevation = 2.dp,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
        ) {
            Text(
                modifier = Modifier
                    .padding(bottom = 8.dp),
                style = themeTypography().h6,
                text = conversation.title,
            )

            Text(
                modifier = Modifier
                    .padding(bottom = 4.dp),
                style = themeTypography().body1,
                text = "${conversation.agent.name} Agent",
            )

            Text(
                style = themeTypography().body1,
                text = conversation.getChatPreview(),
            )
        }
    }
}

private fun Conversation.getChatPreview(): String =
    getChats().lastOrNull()
        ?.chatMessage
        ?.content
        ?: "..."
