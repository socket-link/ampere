package link.socket.ampere.agents.events.subscription

import kotlinx.serialization.Serializable

@Serializable
sealed interface Subscription {
    val subscriptionId: String
}
