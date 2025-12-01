package link.socket.ampere.agents.core.status

import kotlinx.serialization.Serializable

@Serializable
sealed interface Status {
    val isClosed: Boolean
}
