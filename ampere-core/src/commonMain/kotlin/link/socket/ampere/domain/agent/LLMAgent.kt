package link.socket.ampere.domain.agent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.FinishReason
import com.aallam.openai.api.core.Role
import com.aallam.openai.client.OpenAI as Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.chat.Chat
import link.socket.ampere.domain.chat.ConversationHistory
import link.socket.ampere.domain.chat.ConversationId
import link.socket.ampere.domain.tool.FunctionDefinition
import link.socket.ampere.domain.tool.FunctionProvider
import link.socket.ampere.domain.util.toClientModelId
import link.socket.ampere.util.logWith

/**
 * Abstract class representing an Agent that interacts with an LLM.
 */
interface LLMAgent {

    val tag: String
        get() = "LLMAgent"

    val config: AIConfiguration

    val scope: CoroutineScope

    /**
     * Base-level System Prompt instructing the AI Agent about its roles and responsibilities.
     * This will be refined over time with improved prompt engineering techniques.
     *
     * 2024/08/28: `multi_tool_use.parallel` function blocking - https://community.openai.com/t/model-tries-to-call-unknown-function-multi-tool-use-parallel/490653/35
     */
    val prompt: String
        get() = """
            You represent an instance of an AI Agent that is operating within the Ampere library. \
            
           There are two types of humans you will interact with:
            1. **Developers**: They configure your parameters and initialize chat sessions. They are responsible for setting up your environment and providing you with specific instructions.
            2. **Users**: They engage with you during chat sessions, seeking your expertise to address their queries and tasks.
            
            Your primary role is to leverage your domain-specific knowledge to assist Users in accomplishing a task that you are well suited to.
            If a User's query falls outside your area of expertise, guide them towards utilizing your specialized skills.
            
            Your responses should be concise and focused on the task at hand. 
            Avoid providing detailed explanations unless explicitly instructed to do so. 
            
            You should always start a conversation with the User by:
            - Asking a relevant question based on your specialized instructions.
            - Offering suggestions to the User about which of your capabilities might be able to assist them, based upon 

            Remember, your primary goal is to assist users efficiently while adhering to the guidelines provided by developers.
        """.trimIndent()

    fun initialSystemMessage(conversationId: ConversationId): Chat.System =
        """
            {
                "conversationId": "$conversationId"
            }
        """.trimIndent().let { metadata ->
            Chat.System("$metadata\n\n$prompt")
        }

    /**
     * @return Map of every available [FunctionProvider], can be extended in concrete implementations
     */
    val availableFunctions: Map<String, FunctionProvider>
        get() = emptyMap()

    /**
     * @return List of Tools derived from [availableFunctions]
     */
    val tools: List<Tool>
        get() =
            availableFunctions.map { entry ->
                entry.value.definition.tool
            }.toList()

    /**
     * Executes a given ChatCompletionRequest and returns a pair containing the chat response and
     * a boolean indicating if there were tool calls pending execution.
     *
     * @param completionRequest - the request to be processed by OpenAI's chat model
     * @param onNewChats - a lambda that is executed whenever new Chats are added to the conversation
     * @return A boolean indicating if Tool calls were ran during this execution
     */
    suspend fun execute(
        client: Client,
        completionRequest: ChatCompletionRequest,
        onNewChats: (List<Chat>) -> Unit,
    ): Boolean {
        val completion = client.chatCompletion(completionRequest)
        val response = completion.choices.first()

        response.message.content?.takeIf { it.isNotBlank() }?.let { content ->
            val completionChat = Chat.Text(
                role = response.message.role,
                content = content,
            )
            onNewChats(listOf(completionChat))
        }

        return if (response.finishReason == FinishReason.ToolCalls) {
            val toolChats = response.message.executePendingToolCalls()
            onNewChats(toolChats)
            logWith("$tag-execute").i("Response:\n$response\n$toolChats")
            true
        } else {
            logWith("$tag-execute").i("Response:\n$response")
            false
        }
    }

    /**
     * Executes any pending tool calls in the given ChatMessage and returns the responses as a list
     *
     * @return The resulting Function Chat objects
     */
    suspend fun ChatMessage.executePendingToolCalls(): List<Chat> {
        val jobs =
            toolCalls?.map { call ->
                // TODO: Invert, to remove scope
                scope.async {
                    when (call) {
                        is ToolCall.Function ->
                            call.function.let { function ->
                                logWith("$tag-executePendingToolCalls").i(
                                    "Executing ${function.name} with Args:\n${function.arguments}",
                                )
                                function.execute().also { response ->
                                    logWith("$tag-executePendingToolCalls").i(
                                        "Tool ${function.name} Response: $response",
                                    )
                                }
                            }
                    }
                }
            } ?: emptyList()

        return jobs.awaitAll()
    }

    /**
     * Executes the function call and returns the response as a Chat object
     *
     * @return Chat response from the function call execution
     */
    suspend fun FunctionCall.execute(): Chat {
        val functionTool = availableFunctions[name] ?: error("Function $name not found")

        val functionArgs = argumentsAsJson()

        return when (val definition = functionTool.definition) {
            is FunctionDefinition.StringReturn -> {
                val content = definition.execute(functionArgs)

                Chat.Text(
                    role = ChatRole.Function,
                    functionName = nameOrNull,
                    content = content,
                )
            }
            is FunctionDefinition.CSVReturn -> {
                val content = definition(functionArgs)

                Chat.CSV(
                    role = ChatRole.Function,
                    functionName = nameOrNull,
                    csvContent = content,
                )
            }
        }
    }

    /**
     * Creates a ChatCompletionRequest object with the given conversation history
     *
     * @param conversationHistory - the history of the conversation to include in the request
     * @return ChatCompletionRequest ready to be sent to the OpenAI API
     */
    fun createCompletionRequest(
        model: AIModel,
        conversationHistory: ConversationHistory,
    ): ChatCompletionRequest {
        val filteredMessages = conversationHistory.getChats()
            .filter { it.chatMessage.content?.isNotBlank() == true }
            .map { it.chatMessage }

        val messages = when (model) {
            is AIModel_Gemini -> {
                filteredMessages.map { message ->
                    if (message.role == Role.System) {
                        message.copy(
                            role = ChatRole.User,
                        )
                    } else {
                        message
                    }
                }
            }
            else -> filteredMessages
        }

        return ChatCompletionRequest(
            model = model.toClientModelId(),
            messages = messages,
            tools = tools.ifEmpty { null },
            topP = 0.2,
        )
    }
}
