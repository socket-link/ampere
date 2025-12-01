package link.socket.ampere.agents.core.reasoning

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.states.State
import link.socket.ampere.agents.events.utils.generateUUID

typealias PerceptionId = String

@Serializable
data class Perception <S : State>(
    val currentState: S,
    val ideas: List<Idea>,
    val id: PerceptionId = generateUUID(*ideas.map { it.id }.toTypedArray()),
    val timestamp: Instant = Clock.System.now(),
) {
    companion object {
        val blank: Perception<*> = Perception<State.Blank>(
            id = "",
            currentState = State.Blank,
            ideas = emptyList(),
            timestamp = Clock.System.now(),
        )
    }
}
