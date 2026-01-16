package link.socket.ampere.dsl.team

import link.socket.ampere.dsl.agent.AgentRole
import link.socket.ampere.dsl.agent.Personality
import link.socket.ampere.dsl.agent.PersonalityBuilder

/**
 * Represents a configured agent within a team.
 */
data class TeamMember(
    val role: AgentRole,
    val personality: Personality?,
)

/**
 * DSL builder for configuring a team member.
 */
@DslMarker
annotation class TeamMemberDsl

@TeamMemberDsl
class TeamMemberBuilder(private val role: AgentRole) {
    private var personality: Personality? = null

    /**
     * Configure the personality traits for this agent.
     *
     * Example:
     * ```kotlin
     * agent(Engineer) {
     *     personality {
     *         creativity = 0.8
     *         thoroughness = 0.9
     *     }
     * }
     * ```
     */
    fun personality(block: PersonalityBuilder.() -> Unit) {
        personality = PersonalityBuilder().apply(block).build()
    }

    internal fun build(): TeamMember = TeamMember(
        role = role,
        personality = personality,
    )
}
