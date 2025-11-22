package link.socket.kore.domain.agent

import co.touchlab.kermit.Logger.Companion.tag
import kotlinx.coroutines.CoroutineScope
import link.socket.kore.data.UserConversationRepository
import link.socket.kore.domain.agent.bundled.AgentDefinition
import link.socket.kore.domain.agent.bundled.getAgentDefinition
import link.socket.kore.domain.ai.configuration.AIConfiguration
import link.socket.kore.domain.chat.ConversationId
import link.socket.kore.util.logWith

data class PromptSubAgentParams(
    val config: AIConfiguration,
    val parentConversationId: ConversationId,
    val scope: CoroutineScope,
    val agentName: String?,
    val prompt: String?,
    val initialUserChat: String?,
)

class KoreAgentFactory(
    private val conversationRepository: UserConversationRepository,
    private val coroutineScope: CoroutineScope,
) {
    val promptSubAgent: suspend (PromptSubAgentParams) -> String
        get() = { params ->
            with(params) {
                // TODO: Improve repetitive logging for function calls, use better format
                logWith(tag).i(
                    "\nllm=${config.model.name}\nparentConversationId=$parentConversationId\nArgs:\nagentName=$agentName\nprompt=$prompt\n$initialUserChat",
                )

                val agent = KoreAgent(
                    config = config,
                    scope = coroutineScope,
                    definition = agentName.getAgentDefinition(prompt),
                    agentFactory = this@KoreAgentFactory,
                )

                val conversationId = conversationRepository.createConversation(
                    agent = agent,
                    parentConversationId = parentConversationId,
                    initialMessage = null,
                )

                if (initialUserChat != null) {
                    // Option to get the _second_ LLM response, after a User provides their initial reply to the _first_ LLM response
                    conversationRepository.runConversation(conversationId)
                    conversationRepository.addUserChat(
                        conversationId = conversationId,
                        input = initialUserChat,
                    )
                }

                conversationRepository.runConversation(conversationId)

                conversationRepository
                    .getValue(conversationId)
                    ?.conversationHistory
                    ?.getChats()
                    ?.joinToString("\n\n") { chat ->
                        """
                        {
                            "role": "${chat.role}",
                            "content": "${chat.content}"
                        }
                        """.trimIndent()
                    }
                    ?: "".also { message ->
                        logWith(tag).i("\nResponse:\n$message")
                    }
            }
        }

    fun buildAgent(
        config: AIConfiguration,
        definition: AgentDefinition,
        scope: CoroutineScope,
    ): KoreAgent {
        return KoreAgent(
            definition = definition,
            config = config,
            scope = scope,
            agentFactory = this,
        )
    }
}
