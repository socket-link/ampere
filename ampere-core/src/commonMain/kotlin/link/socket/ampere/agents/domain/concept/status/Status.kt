package link.socket.ampere.agents.domain.concept.status

import kotlinx.serialization.Serializable

@Serializable
sealed interface Status {
    val isClosed: Boolean
}
