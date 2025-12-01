package link.socket.ampere.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.Scaffold
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import link.socket.ampere.domain.chat.Conversation
import link.socket.ampere.io.exportToFile
import link.socket.ampere.ui.theme.themeColors
import link.socket.ampere.ui.widget.SmallSnackbarHost

@Composable
fun ConversationScreen(
    listState: LazyListState,
    conversation: Conversation,
    isLoading: Boolean,
    onChatSent: (String) -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberScaffoldState()

    val textFieldValue: MutableState<TextFieldValue> = remember { mutableStateOf(TextFieldValue()) }

    val displaySnackbar: (String) -> Unit = { message ->
        scope.launch {
            scaffoldState.snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize(),
            scaffoldState = scaffoldState,
            topBar = {
                ConversationHeader(
                    agentDefinition = conversation.agent.definition,
                    onBackClicked = onBackClicked,
                    onImportExportClicked = {
                        conversation.exportToFile()
                    },
                )
            },
            bottomBar = {
                ConversationTextEntry(
                    modifier = Modifier
                        .align(Alignment.BottomCenter),
                    textFieldValue = textFieldValue.value,
                    onSendClicked = { onChatSent(textFieldValue.value.text) },
                    onTextChanged = { text -> textFieldValue.value = text },
                )
            },
            snackbarHost = { snackbarState ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(),
                ) {
                    SmallSnackbarHost(
                        modifier = Modifier
                            .align(Alignment.BottomStart),
                        snackbarHostState = snackbarState,
                    )
                }
            },
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(themeColors().background)
                    .padding(contentPadding),
            ) {
                val messages = remember(conversation) {
                    conversation.getChats()
                }

                ChatHistorySection(
                    modifier = Modifier
                        .fillMaxSize(),
                    listState = listState,
                    messages = messages,
                    isLoading = isLoading,
                    displaySnackbar = displaySnackbar,
                )
            }
        }
    }
}
