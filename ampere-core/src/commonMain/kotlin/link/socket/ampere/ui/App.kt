package link.socket.ampere.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.RepositoryFactory
import link.socket.ampere.data.UserConversationRepository
import link.socket.ampere.domain.agent.KoreAgent
import link.socket.ampere.domain.agent.KoreAgentFactory
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.chat.Conversation
import link.socket.ampere.domain.chat.ConversationId
import link.socket.ampere.domain.koog.KoogAgentFactory
import link.socket.ampere.ui.agent.AgentSelectionSection
import link.socket.ampere.ui.agent.setup.AgentSetupScreen
import link.socket.ampere.ui.conversation.ConversationScreen
import link.socket.ampere.ui.home.HomeScreen
import link.socket.ampere.ui.theme.themeColors
import link.socket.ampere.ui.theme.themeShapes
import link.socket.ampere.ui.theme.themeTypography

@Composable
fun App(
    databaseDriver: SqlDriver,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val json = remember {
        DEFAULT_JSON
    }

    val koogAgentFactory = remember {
        KoogAgentFactory()
    }

    val repositoryFactory = remember(scope, json) {
        RepositoryFactory(scope, databaseDriver, json)
    }

    val userConversationRepository = remember(repositoryFactory) {
        repositoryFactory.createRepository<UserConversationRepository>()
    }

    val agentEventRepository = remember(repositoryFactory) {
        repositoryFactory.createRepository<EventRepository>()
    }

    val agentMessageRepository = remember(repositoryFactory) {
        repositoryFactory.createRepository<MessageRepository>()
    }

    val agentFactory = remember(userConversationRepository, scope) {
        KoreAgentFactory(userConversationRepository, scope)
    }

    val allUserConversations: State<Map<ConversationId, Conversation>> =
        userConversationRepository
            .observeValues()
            .collectAsState()

    MaterialTheme(
        colors = themeColors(),
        typography = themeTypography(),
        shapes = themeShapes(),
    ) {
        val selectedScreen: MutableState<Screen> = remember { mutableStateOf(Screen.HOME) }
        val selectedConversationId: MutableState<ConversationId?> = remember { mutableStateOf(null) }
        val conversationListState = rememberLazyListState()

        val selectedConversationValue: State<Conversation?> =
            selectedConversationId.value?.let { id ->
                userConversationRepository
                    .observeValue(id)
                    .collectAsState(null)
            } ?: mutableStateOf(null)

        val selectedConversation: State<Conversation?> = remember(selectedConversationId, selectedConversationValue) {
            derivedStateOf {
                if (selectedConversationId.value != null) {
                    selectedConversationValue.value
                } else {
                    null
                }
            }
        }

        val isLoading: MutableState<Boolean> = remember { mutableStateOf(false) }

        val onExecutingConversation = {
            isLoading.value = true
        }

        val onConversationFinishedExecuting: () -> Unit = {
            isLoading.value = false

            val conversationId = selectedConversationId.value
            if (conversationId != null) {
                scope.launch {
                    val conversation = userConversationRepository.getValue(conversationId)
                    val lastIndex = conversation?.getChats()?.lastIndex ?: 0

                    delay(500)
                    conversationListState.animateScrollToItem(lastIndex)
                }
            }
        }

        val onConversationSelected: (ConversationId) -> Unit = { conversationId ->
            selectedConversationId.value = conversationId
            selectedScreen.value = Screen.CONVERSATION
        }

        val onNewConversation: (KoreAgent) -> Unit = { agent ->
            val newId = userConversationRepository.createConversation(agent)
            onConversationSelected(newId)

            scope.launch {
                onExecutingConversation()

                userConversationRepository.runConversation(
                    conversationId = selectedConversationId.value!!,
                )

                val koogAgent = koogAgentFactory.createKoogAgent(
                    aiConfiguration = agent.config,
                    agent = agent,
                )

                koogAgent?.run("")

                onConversationFinishedExecuting()
            }
        }

        val selectedAgentDefinition = remember {
            mutableStateOf<AgentDefinition?>(null)
        }

        Box(
            modifier = modifier,
        ) {
            when (selectedScreen.value) {
                Screen.HOME -> {
                    HomeScreen(
                        modifier = Modifier
                            .fillMaxSize(),
                        agentConversationsList = allUserConversations.value.values.toList(),
                        onCreateConversationSelected = {
                            selectedScreen.value = Screen.AGENT_SELECTION
                        },
                        onConversationSelected = { conversation ->
                            onConversationSelected(conversation.id)
                        },
                    )
                }

                Screen.AGENT_SELECTION -> {
                    AgentSelectionSection(
                        modifier = Modifier
                            .fillMaxWidth(),
                        onAgentPartiallySelected = { agent ->
                            selectedAgentDefinition.value = agent
                            selectedScreen.value = Screen.AGENT_SETUP
                        },
                    )
                }

                Screen.AGENT_SETUP -> {
                    selectedAgentDefinition.value?.let { agentDefinition ->
                        AgentSetupScreen(
                            modifier = Modifier
                                .fillMaxSize(),
                            agentDefinition = agentDefinition,
                            agentFactory = agentFactory,
                            onAgentCreated = { agent ->
                                selectedScreen.value = Screen.CONVERSATION
                                onNewConversation(agent)
                            },
                            onBackClicked = {
                                selectedScreen.value = Screen.HOME
                            },
                        )
                    }
                }

                Screen.CONVERSATION -> {
                    selectedConversation.value?.let { conversation ->
                        ConversationScreen(
                            modifier = Modifier
                                .fillMaxSize(),
                            listState = conversationListState,
                            conversation = conversation,
                            isLoading = isLoading.value,
                            onChatSent = { input ->
                                selectedConversationId.value?.let { conversationId ->
                                    scope.launch {
                                        onExecutingConversation()
                                        userConversationRepository.addUserChat(conversationId, input)
                                        onConversationFinishedExecuting()
                                    }
                                }
                            },
                            onBackClicked = {
                                selectedConversationId.value = null
                                selectedScreen.value = Screen.HOME
                            },
                        )
                    }
                }
            }
        }
    }
}
