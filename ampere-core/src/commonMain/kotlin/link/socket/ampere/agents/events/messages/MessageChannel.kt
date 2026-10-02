package link.socket.ampere.agents.events.messages

import kotlinx.serialization.SerialName
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

    /**
     * A Room: the channel one project's roster coordinates in (AMPR-379).
     *
     * [roomId] is the `link.socket.ampere.room.RoomId` value, which always carries
     * [ROOM_PREFIX]. That prefix is what [fromMessageChannelId] recognises, so a Room
     * thread survives a save and a load — any other unknown channel id comes back as
     * a [Direct] channel, which is why the set of channels was closed until now.
     */
    @Serializable
    @SerialName("MessageChannel.Room")
    data class Room(
        val roomId: MessageChannelId,
    ) : MessageChannel {

        override fun getIdentifier(): String = roomId
    }

    // Function to format channel name as displayable string (e.g., "#engineering")
    fun getIdentifier(): String

    companion object {
        /** Every [Room] channel id starts with this; see `RoomId.forProject`. */
        const val ROOM_PREFIX: String = "room:"

        val ALL_PUBLIC_CHANNELS: List<MessageChannel> = listOf(
            Public.Engineering,
            Public.Design,
            Public.Product,
        )

        fun fromMessageChannelId(id: MessageChannelId): MessageChannel = when {
            id == Public.Engineering.id -> Public.Engineering
            id == Public.Design.id -> Public.Design
            id == Public.Product.id -> Public.Product
            id.startsWith(ROOM_PREFIX) -> Room(id)
            else -> {
                val agentId = id.substringBefore("@")
                Direct(MessageSender.Agent(agentId))
            }
        }
    }
}
