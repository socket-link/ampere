package link.socket.ampere.agents.domain.cognition.sparks

/**
 * Sealed parser output for any `.spark.md` document. Replaces the previous
 * `DeclarativePhaseSparkSource`-only return shape so the parser can speak
 * about phase and role variants under a single naming axis.
 *
 * Each variant carries its decoded [SparkFrontmatter] sibling plus the
 * post-fence body. Adapters (`toPhaseSpark`, `toRoleSpark`) live alongside
 * the runtime types they produce, not on this sealed family, so each runtime
 * spark owns its conversion contract.
 */
internal sealed interface DeclarativeSparkSource {
    val id: String
    val name: String
    val body: String

    /**
     * A `"phase"` spark: prompt-only, optionally split into per-phase sections
     * via `## When Perceiving/Planning/Executing/Learning` headers.
     */
    data class Phase(
        val frontmatter: PhaseSparkFrontmatter,
        override val body: String,
        val phaseContributions: Map<CognitivePhase, String>,
    ) : DeclarativeSparkSource {
        override val id: String get() = frontmatter.id
        override val name: String get() = frontmatter.name
    }

    /**
     * A `"role"` spark: capability-bearing, single body block surfaced
     * directly as `promptContribution`. No per-phase section extraction —
     * role guidance applies uniformly across phases.
     */
    data class Role(
        val frontmatter: RoleSparkFrontmatter,
        override val body: String,
    ) : DeclarativeSparkSource {
        override val id: String get() = frontmatter.id
        override val name: String get() = frontmatter.name
    }
}

/**
 * Bridges a parsed [DeclarativeSparkSource.Phase] back into the existing
 * [DeclarativePhaseSparkSource] data path used by [DeclarativePhaseSpark.toPhaseSpark].
 * Keeping this adapter local to the parser layer means downstream phase-spark
 * consumers don't need to know the JSON variant exists.
 */
internal fun DeclarativeSparkSource.Phase.toLegacySource(): DeclarativePhaseSparkSource =
    DeclarativePhaseSparkSource(
        id = frontmatter.id,
        name = frontmatter.name,
        whenToUse = frontmatter.whenToUse,
        body = body,
        phases = frontmatter.phases,
        tags = frontmatter.tags,
        modelPreference = frontmatter.modelPreference,
        phaseContributions = phaseContributions,
        agentRole = frontmatter.agentRole,
        requestedToolIds = frontmatter.requestedToolIds,
    )
