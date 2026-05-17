package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal typealias PhaseSparkId = String

/**
 * Raw, language-neutral declaration parsed from a `.spark.md` source.
 *
 * A source produces exactly one [DeclarativePhaseSpark] via [toPhaseSpark]; multi-phase
 * eligibility lives in [phases] and per-phase body sections live in [phaseContributions].
 */
@Serializable
internal data class DeclarativePhaseSparkSource(
    val id: PhaseSparkId,
    val name: String,
    val whenToUse: String,
    val body: String,
    val phases: Set<CognitivePhase> = setOf(CognitivePhase.PLAN),
    val tags: Set<String> = emptySet(),
    val modelPreference: String? = null,
    val phaseContributions: Map<CognitivePhase, String> = emptyMap(),
    val agentRole: String? = null,
    val requestedToolIds: Set<String> = emptySet(),
)

/**
 * A PhaseSpark authored as markdown rather than hardcoded as a sealed subclass.
 *
 * The [name] is `"PhaseSpark:<id>"` so trace projections that bucket by the
 * `"Phase:"` prefix continue to ignore declarative sparks while still surfacing
 * them via the `"PhaseSpark"` prefix when desired.
 *
 * Unlike built-in PhaseSparks, a single DeclarativePhaseSpark can span multiple
 * phases via [eligiblePhases] and carry distinct per-phase guidance via
 * [phaseContributions].
 */
@Serializable
@SerialName("PhaseSpark.Declarative")
internal data class DeclarativePhaseSpark(
    val sparkId: PhaseSparkId,
    val displayName: String,
    override val eligiblePhases: Set<CognitivePhase>,
    override val promptContribution: String,
    override val phaseContributions: Map<CognitivePhase, String>,
    val whenToUse: String,
    val tags: Set<String> = emptySet(),
    val modelPreference: String? = null,
    override val agentRole: String? = null,
    override val requestedToolIds: Set<String> = emptySet(),
) : PhaseSpark() {
    override val name: String = "PhaseSpark:$sparkId"

    /**
     * Back-compat anchor for callers that still read [PhaseSpark.phase]. Returns the
     * first eligible phase; prefer [eligiblePhases] for new code.
     */
    override val phase: CognitivePhase
        get() = eligiblePhases.firstOrNull() ?: CognitivePhase.PLAN
}

/**
 * Produces a single [DeclarativePhaseSpark] from a source. Eligibility across multiple
 * phases is carried on the spark itself via [DeclarativePhaseSpark.eligiblePhases].
 */
internal fun DeclarativePhaseSparkSource.toPhaseSpark(): DeclarativePhaseSpark =
    DeclarativePhaseSpark(
        sparkId = id,
        displayName = name,
        eligiblePhases = phases,
        promptContribution = body,
        phaseContributions = phaseContributions,
        whenToUse = whenToUse,
        tags = tags,
        modelPreference = modelPreference,
        agentRole = agentRole,
        requestedToolIds = requestedToolIds,
    )

/**
 * Back-compat alias for callers still using the plural form. Returns a single-element list.
 *
 * Prefer [toPhaseSpark] for new code.
 */
internal fun DeclarativePhaseSparkSource.toPhaseSparks(): List<PhaseSpark> =
    listOf(toPhaseSpark())
