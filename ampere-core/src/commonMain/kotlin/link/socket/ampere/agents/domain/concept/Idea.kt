package link.socket.ampere.agents.domain.concept

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.utils.generateUUID

typealias IdeaId = String

@Serializable
data class Idea(
    val name: String,
    val id: IdeaId = generateUUID(),
    val description: String = "",
) {
    companion object {
        val blank: Idea
            get() = Idea(
                id = "",
                name = "",
                description = "",
            )
    }
}
