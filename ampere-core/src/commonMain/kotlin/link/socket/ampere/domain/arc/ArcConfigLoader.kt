package link.socket.ampere.domain.arc

/**
 * Loads Arc configurations with three-tier precedence:
 * 1. User-defined overrides from `.ampere/arc.yaml`
 * 2. Built-in configurations from ArcRegistry
 * 3. Default fallback (startup-saas)
 *
 * Merge behavior:
 * - User description overrides registry description
 * - User orchestration overrides registry orchestration
 * - Agent sparks are merged: registry default spark + user additional sparks
 */
object ArcConfigLoader {

    /**
     * Load an Arc configuration with merging support.
     *
     * @param userConfig User-provided configuration (typically from `.ampere/arc.yaml`)
     * @return Merged configuration combining user overrides with registry defaults
     */
    fun load(userConfig: ArcConfig?): ArcConfig {
        if (userConfig == null) {
            return ArcRegistry.getDefault()
        }

        val baseConfig = ArcRegistry.get(userConfig.name) ?: return userConfig

        return merge(baseConfig, userConfig)
    }

    /**
     * Load configuration by arc name, applying user overrides.
     *
     * @param arcName Name of the arc to load
     * @param userConfig Optional user overrides
     * @return Merged configuration
     */
    fun load(arcName: String, userConfig: ArcConfig? = null): ArcConfig {
        val baseConfig = ArcRegistry.get(arcName) ?: ArcRegistry.getDefault()

        if (userConfig == null) {
            return baseConfig
        }

        return merge(baseConfig, userConfig)
    }

    /**
     * Merge a base configuration with user overrides.
     *
     * Merge rules:
     * - name: always use base name (arc identity)
     * - description: user override if provided, otherwise base
     * - orchestration: user override if non-default, otherwise base
     * - agents: merge by role, combining sparks
     */
    fun merge(base: ArcConfig, override: ArcConfig): ArcConfig {
        return ArcConfig(
            name = base.name,
            description = override.description ?: base.description,
            agents = mergeAgents(base.agents, override.agents),
            orchestration = mergeOrchestration(base.orchestration, override.orchestration),
        )
    }

    private fun mergeAgents(
        baseAgents: List<ArcAgentConfig>,
        overrideAgents: List<ArcAgentConfig>,
    ): List<ArcAgentConfig> {
        val baseByRole = baseAgents.associateBy { it.role.lowercase() }
        val overrideByRole = overrideAgents.associateBy { it.role.lowercase() }

        val allRoles = (baseByRole.keys + overrideByRole.keys).toList()

        return allRoles.map { role ->
            val baseAgent = baseByRole[role]
            val overrideAgent = overrideByRole[role]

            when {
                baseAgent == null && overrideAgent != null -> overrideAgent
                baseAgent != null && overrideAgent == null -> baseAgent
                baseAgent != null && overrideAgent != null -> mergeAgent(baseAgent, overrideAgent)
                else -> error("Unexpected state: both base and override agents are null for role $role")
            }
        }
    }

    private fun mergeAgent(base: ArcAgentConfig, override: ArcAgentConfig): ArcAgentConfig {
        val mergedSparks = RoleSparkMapping.getAllSparks(base.role, override.sparks)

        return ArcAgentConfig(
            role = base.role,
            sparks = mergedSparks,
        )
    }

    private fun mergeOrchestration(
        base: OrchestrationConfig,
        override: OrchestrationConfig,
    ): OrchestrationConfig {
        val isOverrideDefault = override.type == OrchestrationType.SEQUENTIAL &&
            override.order.isEmpty()

        return if (isOverrideDefault) {
            base
        } else {
            override
        }
    }
}
