package link.socket.ampere.agents.domain

import kotlinx.serialization.Serializable

/** Urgency levels for questions raised by agents. */
@Serializable
enum class Urgency {
    LOW,
    MEDIUM,
    HIGH,
}
