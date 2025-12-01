package link.socket.ampere.domain.chat

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole

/**
 * Sealed class representing different types of messages.
 * Each Chat has a ChatRole and a corresponding ChatMessage.
 */
sealed class Chat(
    open val role: ChatRole,
    open val chatMessage: ChatMessage,
) {
    abstract val content: String

    /**
     * Data class for text messages that contain the System prompt for the LLM.
     */
    data class System(
        val prompt: String,
    ) : Chat(
        role = ChatRole.System,
        chatMessage = ChatMessage(
            role = ChatRole.System,
            content = prompt,
        ),
    ) {
        override val content: String = prompt
    }

    /**
     * Data class for text messages, containing content and an optional function name.
     */
    data class Text(
        override val role: ChatRole,
        override val content: String,
        val functionName: String? = null,
    ) : Chat(
        role = role,
        chatMessage = ChatMessage(
            role = role,
            name = functionName,
            content = content,
        ),
    ) {
        companion object {
            /**
             * Factory method for creating Text instances from ChatMessage objects.
             */
            fun fromChatMessage(chatMessage: ChatMessage): Text =
                Text(
                    role = chatMessage.role,
                    functionName = chatMessage.name,
                    content = chatMessage.content ?: "",
                )
        }
    }

    /**
     * Data class for CSV messages, containing CSV content and an optional function name.
     */
    data class CSV(
        override val role: ChatRole,
        val csvContent: List<List<String>>,
        val functionName: String? = null,
    ) : Chat(
        role = role,
        chatMessage = ChatMessage(
            role = role,
            name = functionName,
            content = with(csvContent) {
                val resultString = StringBuilder()
                forEach { line ->
                    line.forEach { cell ->
                        resultString.append(cell)
                        resultString.append("\t")
                    }
                    resultString.append("\n")
                }
                resultString.toString()
            },
        ),
    ) {
        override val content: String = csvContent.joinToString { it.joinToString("\t") }
    }
}
