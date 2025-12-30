package link.socket.ampere.agents.domain.status

import kotlinx.serialization.Serializable

@Serializable
sealed interface Status {
    val isClosed: Boolean
}
