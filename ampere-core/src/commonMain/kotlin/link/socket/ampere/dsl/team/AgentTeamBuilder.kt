package link.socket.ampere.dsl.team

import link.socket.ampere.dsl.agent.AgentRole
import link.socket.ampere.dsl.config.ProviderConfig

/**
 * DSL builder for creating an AgentTeam.
 *
 * Example:
 * ```kotlin
 * val team = AgentTeam.create {
 *     config(AnthropicConfig(model = Claude.Sonnet4))
 *
 *     agent(ProductManager) {
 *         personality { directness = 0.8 }
 *     }
 *     agent(Engineer) {
 *         personality { creativity = 0.7 }
 *     }
 *     agent(QATester)
 * }
 * ```
 */
@DslMarker
annotation class AgentTeamDsl

@AgentTeamDsl
class AgentTeamBuilder {
    private val members = mutableListOf<TeamMember>()
    private var providerConfig: ProviderConfig? = null

    /**
     * Add an agent to the team with optional configuration.
     *
     * Example:
     * ```kotlin
     * agent(Engineer) {
     *     personality { creativity = 0.8 }
     * }
     * ```
     */
    fun agent(role: AgentRole, block: TeamMemberBuilder.() -> Unit = {}) {
        val member = TeamMemberBuilder(role).apply(block).build()
        members.add(member)
    }

    /**
     * Set the AI provider configuration for the team.
     * All agents will use this configuration unless they have a specific override.
     */
    fun config(config: ProviderConfig) {
        this.providerConfig = config
    }

    internal fun build(): AgentTeamConfig = AgentTeamConfig(
        members = members.toList(),
        providerConfig = providerConfig,
    )
}

/**
 * Internal configuration holder for team building.
 */
internal data class AgentTeamConfig(
    val members: List<TeamMember>,
    val providerConfig: ProviderConfig?,
)
