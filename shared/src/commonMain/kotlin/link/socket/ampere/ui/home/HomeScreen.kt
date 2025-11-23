package link.socket.ampere.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.ampere.domain.chat.Conversation
import link.socket.ampere.ui.widget.ConversationCard

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    agentConversationsList: List<Conversation>,
    onCreateConversationSelected: () -> Unit,
    onConversationSelected: (Conversation) -> Unit,
) {
    val scaffoldState = rememberScaffoldState()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            topBar = {
                HomeHeader()
            },
            floatingActionButton = {
                Box(
                    modifier = Modifier.padding(
                        end = 8.dp,
                        bottom = 16.dp,
                    ),
                ) {
                    FloatingActionButton(
                        onClick = onCreateConversationSelected,
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Add,
                            contentDescription = "Create Conversation",
                        )
                    }
                }
            },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier
                    .padding(contentPadding)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 8.dp,
                    ),
            ) {
                item {
                    Spacer(
                        modifier = Modifier.requiredHeight(24.dp),
                    )
                }

                items(agentConversationsList) { conversation ->
                    ConversationCard(
                        modifier = Modifier.padding(bottom = 8.dp),
                        conversation = conversation,
                        onClick = {
                            onConversationSelected(conversation)
                        },
                    )
                }
            }
        }
    }
}
