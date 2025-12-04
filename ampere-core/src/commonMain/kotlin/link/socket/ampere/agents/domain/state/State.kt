package link.socket.ampere.agents.domain.state

import kotlinx.serialization.Serializable

@Serializable
sealed interface State {

    @Serializable
    data object Blank : State
}
