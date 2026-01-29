package link.socket.ampere.agents.events.messages

import kotlinx.serialization.Serializable

typealias MessageChannelId = String

@Serializable
sealed interface MessageChannel {

    @Serializable
    sealed class Public(
        val id: MessageChannelId,
    ) : MessageChannel {

        @Serializable
        data object Engineering : Public("#engineering")

        @Serializable
        data object Design : Public("#design")

        @Serializable
        data object Product : Public("#product")

        override fun getIdentifier(): String = id
    }

    @Serializable
    data class Direct(
        val sender: MessageSender.Agent,
    ) : MessageChannel {

        override fun getIdentifier(): String =
            "${sender.agentId}@me"
    }

    // Function to format channel name as displayable string (e.g., "#engineering")
    fun getIdentifier(): String

    companion object {
        val ALL_PUBLIC_CHANNELS: List<MessageChannel> = listOf(
            Public.Engineering,
            Public.Design,
            Public.Product,
        )

        fun fromMessageChannelId(id: MessageChannelId): MessageChannel = when (id) {
            Public.Engineering.id -> {
                Public.Engineering
            }
            Public.Design.id -> {
                Public.Design
            }
            Public.Product.id -> {
                Public.Product
            }
            else -> {
                val agentId = id.substringBefore("@")
                Direct(MessageSender.Agent(agentId))
            }
        }
    }
}
