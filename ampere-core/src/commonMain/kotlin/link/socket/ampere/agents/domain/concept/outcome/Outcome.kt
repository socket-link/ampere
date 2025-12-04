package link.socket.ampere.agents.domain.concept.outcome

import kotlinx.serialization.Serializable

typealias OutcomeId = String

@Serializable
sealed interface Outcome {

    val id: OutcomeId

    @Serializable
    data object Blank : Outcome {
        override val id: OutcomeId = ""
    }

    @Serializable
    sealed interface Success : Outcome

    @Serializable
    sealed interface Failure : Outcome

    companion object {
        val blank: Outcome = Blank
    }
}
