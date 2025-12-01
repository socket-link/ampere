package link.socket.ampere.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aallam.openai.api.core.Role
import link.socket.ampere.domain.chat.Chat
import link.socket.ampere.ui.theme.themeColors

@Composable
fun ChatHistorySection(
    listState: LazyListState,
    messages: List<Chat>,
    isLoading: Boolean,
    displaySnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSystemMessages: Boolean = true,
    backgroundColor: Color = themeColors().surface,
    textColor: Color = MaterialTheme.colors.onBackground,
    codeTextColor: Color = MaterialTheme.colors.onBackground,
    linkTextColor: Color = textColor,
    codeBackgroundColor: Color = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
    inlineCodeBackgroundColor: Color = codeBackgroundColor,
    dividerColorColor: Color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 16.dp,
            end = 8.dp,
            bottom = 72.dp,
        ),
    ) {
        itemsIndexed(
            messages.filter { message ->
                showSystemMessages || (message.role != Role.System)
            },
        ) { index, message ->
            ChatMessage(
                modifier = Modifier
                    .wrapContentHeight()
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                message = message,
                displaySnackbar = displaySnackbar,
                showRegenerate = (index == messages.size - 1) && (message.role == Role.Assistant),
                backgroundColor = backgroundColor,
                textColor = textColor,
                codeTextColor = codeTextColor,
                linkTextColor = linkTextColor,
                codeBackgroundColor = codeBackgroundColor,
                inlineCodeBackgroundColor = inlineCodeBackgroundColor,
                dividerColor = dividerColorColor,
            )
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
