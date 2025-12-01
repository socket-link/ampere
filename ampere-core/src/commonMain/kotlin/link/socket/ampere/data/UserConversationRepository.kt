package link.socket.ampere.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import link.socket.ampere.domain.agent.KoreAgent
import link.socket.ampere.domain.chat.Chat
import link.socket.ampere.domain.chat.Conversation
import link.socket.ampere.domain.chat.ConversationId
import link.socket.ampere.util.logWith
import link.socket.ampere.util.randomUUID

/**
 * ConversationRepository handles the creation and execution of conversations as well as adding user chats to a given conversation.
 *
 * @property scope - CoroutineScope for managing coroutines
 */
class UserConversationRepository(
    override val json: Json,
    override val scope: CoroutineScope,
) : Repository<ConversationId, Conversation>(json, scope) {

    override val tag: String = "Conversation${super.tag}"

    /**
     * Creates a new conversation with the given agent and an optional initial message.
     * The new conversation is initialized and stored in the repository.
     *
     * @param agent The agent to be used in the conversation
     * @param initialMessage An optional initial chat message
     * @return The ConversationId of the created conversation
     */
    fun createConversation(
        agent: KoreAgent,
        parentConversationId: ConversationId? = null,
        initialMessage: Chat? = null,
    ): ConversationId {
        val parentKey = parentConversationId?.let { "$it/" }.orEmpty()
        val key = randomUUID()
        val id = "$parentKey$key"

        val conversation =
            Conversation(
                id = id,
                title = "Sub Conversation",
                agent = agent,
            )
        storeValue(id, conversation.initialize(initialMessage))
        logWith(tag).i("${agent.tag} - Conversation Created: $id")

        return id
    }

    suspend fun runConversation(
        conversationId: ConversationId,
    ) {
        var shouldRerun = false

        logWith("$tag-runConversation").i("Starting Conversation: $conversationId")

        do {
            logWith("$tag-runConversation").i("Running Conversation: $conversationId")

            getValue(conversationId)?.let { conversation ->
                with(conversation) {
                    val completionRequest = getCompletionRequest(agent.config.model)
                    val client = agent.config.provider.client

                    val ranTools = agent.execute(
                        client = client,
                        completionRequest = completionRequest,
                    ) { chats ->
                        storeValue(conversationId, add(*chats.toTypedArray()))
                    }

                    if (ranTools) {
                        logWith("$tag-runConversation").i("${agent.tag} ran tools: $conversationId")
                    }

                    shouldRerun = ranTools
                }
            }
        } while (shouldRerun)

        logWith("$tag-runConversation").i("Finished Conversation: $conversationId")
    }

    /**
     * Adds a user chat to the conversation with the given ConversationId.
     * After adding the user chat, it runs the conversation.
     *
     * @param conversationId The ID of the conversation
     * @param input The user input to be added as a chat
     */
    suspend fun addUserChat(
        conversationId: ConversationId,
        input: String,
    ) {
        logWith("$tag-addUserChat").i("addUserChat to Conversation:\nid: $conversationId")

        getValue(conversationId)?.apply {
            storeValue(conversationId, addUserChat(input))
            runConversation(conversationId)
        }
    }
}
