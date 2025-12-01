package link.socket.ampere.domain.chat

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatRole
import link.socket.ampere.domain.agent.KoreAgent
import link.socket.ampere.domain.ai.model.AIModel

typealias ConversationId = String

/**
 * Data class representing a Conversation with a unique ID, title, associated agent,
 * and conversation history.
 *
 * @property id The unique identifier of the conversation.
 * @property title The title of the conversation.
 * @property agent The agent associated with the conversation.
 * @property conversationHistory The history of chats in the conversation, defaulting to uninitialized.
 */
data class Conversation(
    val id: ConversationId,
    val title: String,
    val agent: KoreAgent,
    val conversationHistory: ConversationHistory = ConversationHistory.Threaded.Uninitialized,
) {
    /**
     * Initializes the conversation with an initial message if provided.
     *
     * @param initialMessage The initial chat message to start the conversation, or null if not provided.
     * @return A new Conversation instance with updated conversation history.
     */
    fun initialize(initialMessage: Chat? = null): Conversation =
        if (conversationHistory is ConversationHistory.Threaded.Uninitialized) {
            copy(
                conversationHistory = ConversationHistory.NonThreaded(
                    initialMessage?.let { message ->
                        listOf(agent.initialSystemMessage(id), message)
                    } ?: listOf(agent.initialSystemMessage(id)),
                ),
            )
        } else {
            // TODO: Allow for Threaded history
            this
        }

    /**
     * Adds a user chat message to the conversation.
     *
     * @param input The content of the user chat message.
     * @return A new Conversation instance with the added user chat.
     */
    fun addUserChat(input: String): Conversation =
        add(
            Chat.Text(
                role = ChatRole.User,
                content = input,
            ),
        )

    /**
     * Creates a ChatCompletionRequest based on the current conversation history.
     *
     * @return The ChatCompletionRequest for the conversation.
     */
    fun getCompletionRequest(
        model: AIModel,
    ): ChatCompletionRequest = agent.createCompletionRequest(
        model = model,
        conversationHistory = conversationHistory,
    )

    /**
     * Retrieves all chat messages in the conversation that have non-empty content.
     *
     * @return A list of non-empty chat messages.
     */
    fun getChats(): List<Chat> =
        conversationHistory.getChats()
            .filter { it.chatMessage.content?.isNotEmpty() == true }

    /**
     * Adds a chat message to the conversation.
     *
     * @param chats The chat messages to be added.
     * @return A new Conversation instance with the added chat message.
     */
    fun add(vararg chats: Chat): Conversation =
        copy(
            conversationHistory = conversationHistory.appendChats(*chats),
        )
}
