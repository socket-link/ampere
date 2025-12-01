package link.socket.ampere.agents.core.states

import kotlinx.serialization.Serializable

@Serializable
sealed interface State {

    @Serializable
    data object Blank : State
}
