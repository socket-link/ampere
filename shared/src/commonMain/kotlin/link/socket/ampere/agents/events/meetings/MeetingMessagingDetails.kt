package link.socket.ampere.agents.events.meetings

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.messages.MessageChannelId
import link.socket.ampere.agents.events.messages.MessageThreadId

@Serializable
data class MeetingMessagingDetails(
    val messageChannelId: MessageChannelId,
    val messageThreadId: MessageThreadId,
)
