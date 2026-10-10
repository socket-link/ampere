package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.util.getEnvironmentVariable

/**
 * Manages the lifecycle of PhaseSparks during the cognitive cycle.
 *
 * **Ticket #230 / #482**: PhaseSparkManager applies phase-specific sparks at phase
 * entry and removes them at phase exit. State is held as a list of applied sparks
 * per active phase rather than a single boolean so that the manager can host the
 * built-in phase spark alongside any declarative sparks selected by a
 * [PhaseSparkLibrary] (see ticket #482).
 *
 * Phase Sparks are disabled by default to maintain backward compatibility.
 * Enable via [PhaseSparkConfig], the `enabled` property, or environment variable `AMPERE_PHASE_SPARKS`.
 *
 * **AMPR-387**: [enabled] is the master gate, and the two things it used to fuse are now
 * separate switches. [publishBrackets] governs the `PhaseEntered` / `PhaseExited` pair;
 * [injectPhaseSparks] governs everything that reaches the agent's prompt: the `PhaseSpark` pushed
 * onto its spark stack, the declarative sparks selected from its library, and
 * `currentCognitivePhase` (which `buildSystemPrompt` reads to pick each spark's per-phase
 * section). Both default on, so `enabled` alone behaves as it always has;
 * `injectPhaseSparks = false` brackets the phases of a run while leaving its prompt byte-for-byte
 * what it would be with phases off. `AMPERE_PHASE_SPARKS` is a developer override that forces all
 * three on.
 *
 * Phase brackets are published through [eventApi] (F1, AMPR-339): every `PhaseEntered` /
 * `PhaseExited` is persisted before it is dispatched, which is what lets a run's phase history
 * be replayed from the store (F18). The api is the owning agent's own door, so the events are
 * attributed to that agent. Left null — agents built without a door — phases still apply and
 * nothing is published. Because the door is a suspending seam, phase entry and cleanup are
 * suspending too.
 *
 * Each bracket is stamped with an Arc run id on its envelope (F4, AMPR-386), so a run's phase
 * history is returned by `ArcTraceProjection.project(runId)` without the payload `LIKE`
 * fallback — a `PhaseEntered` payload names no run. [enterPhase] and [withPhase] take it,
 * defaulting to the owning agent's [currentRunId][link.socket.ampere.agents.definition.Agent.currentRunId];
 * the entered run is held until the matching exit, so a bracket closed by [cleanup] (or by
 * [withPhase]'s `finally`) carries the run it opened under rather than whatever is current.
 */
class PhaseSparkManager<S : AgentState> private constructor(
    private val agent: AutonomousAgent<S>,
    val enabled: Boolean,
    private val publishBrackets: Boolean,
    private val injectPhaseSparks: Boolean,
    private val activePhases: Set<CognitivePhase>,
    private val library: PhaseSparkLibrary?,
    private val eventApi: AgentEventApi?,
) {
    constructor(
        agent: AutonomousAgent<S>,
        enabled: Boolean = isPhaseSparkEnabled(),
        publishBrackets: Boolean = true,
        injectPhaseSparks: Boolean = true,
        activePhases: Set<CognitivePhase> = DEFAULT_PHASES,
        eventApi: AgentEventApi? = null,
    ) : this(
        agent = agent,
        enabled = enabled,
        publishBrackets = publishBrackets,
        injectPhaseSparks = injectPhaseSparks,
        activePhases = activePhases,
        library = null,
        eventApi = eventApi,
    )

    /**
     * The sparks pushed for the active phase. Empty while a phase is active and
     * [injectPhaseSparks] is off, which is why [currentPhase] — not this list — is
     * what marks a phase as entered.
     */
    private var appliedSparks: MutableList<PhaseSpark> = mutableListOf()
    private var currentPhase: CognitivePhase? = null
    private var currentPhaseNestingDepth: Int = 0
    private var withPhaseNestingDepth: Int = 0

    /** The run the active bracket was entered under, so its `PhaseExited` is stamped the same. */
    private var currentPhaseRunId: RunId? = null

    suspend fun enterPhase(phase: CognitivePhase, runId: RunId? = agent.currentRunId) {
        enterPhaseInternal(phase, selectionContext = null, runId = runId)
    }

    internal suspend fun enterPhase(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
        runId: RunId? = agent.currentRunId,
    ) {
        enterPhaseInternal(phase, selectionContext, runId = runId)
    }

    private suspend fun enterPhaseInternal(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
        runId: RunId?,
        nestingDepth: Int = 0,
        oldPhaseOverride: CognitivePhase? = null,
    ) {
        if (!enabled) return

        val oldPhase = oldPhaseOverride ?: currentPhase
        if (currentPhase != null && currentPhase != phase) {
            removeAppliedSparks()
        }

        if (!isPhaseEnabled(phase)) return

        if (currentPhase == phase) return

        val sparksToApply = if (injectPhaseSparks) selectSparksFor(phase, selectionContext) else emptyList()

        // `currentCognitivePhase` is read by exactly one thing — `buildSystemPrompt` — so it is
        // prompt injection as much as the sparks are: left set, every role/language spark's
        // `## When <Phase>` section would still reach a silently bracketed run's prompt.
        if (injectPhaseSparks) agent.currentCognitivePhase = phase
        publishPhaseEntered(
            oldPhase = oldPhase,
            newPhase = phase,
            nestingDepth = nestingDepth,
            runId = runId,
        )
        for (spark in sparksToApply) {
            agent.spark<AutonomousAgent<S>>(spark)
            appliedSparks += spark
        }
        currentPhase = phase
        currentPhaseNestingDepth = nestingDepth
        currentPhaseRunId = runId
    }

    suspend fun <R> withPhase(
        phase: CognitivePhase,
        runId: RunId? = agent.currentRunId,
        block: suspend () -> R,
    ): R = withPhaseInternal(phase, selectionContext = null, runId = runId, block = block)

    internal suspend fun <R> withPhase(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
        runId: RunId? = agent.currentRunId,
        block: suspend () -> R,
    ): R = withPhaseInternal(phase, selectionContext, runId = runId, block = block)

    private suspend fun <R> withPhaseInternal(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
        runId: RunId?,
        block: suspend () -> R,
    ): R {
        if (!enabled) return block()
        if (!isPhaseEnabled(phase)) return block()

        val nestingDepth = withPhaseNestingDepth
        val previousPhase = currentPhase
        val previousSparks = appliedSparks.toList()
        val previousPhaseNestingDepth = currentPhaseNestingDepth
        val previousPhaseRunId = currentPhaseRunId
        appliedSparks = mutableListOf()
        currentPhase = null
        enterPhaseInternal(
            phase = phase,
            selectionContext = selectionContext,
            runId = runId,
            nestingDepth = nestingDepth,
            oldPhaseOverride = previousPhase,
        )
        withPhaseNestingDepth = nestingDepth + 1

        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                withPhaseNestingDepth = nestingDepth
                removeAppliedSparks(
                    restoredToPhase = previousPhase,
                    nestingDepth = nestingDepth,
                )

                if (previousPhase != null) {
                    appliedSparks = previousSparks.toMutableList()
                    currentPhase = previousPhase
                    currentPhaseNestingDepth = previousPhaseNestingDepth
                    currentPhaseRunId = previousPhaseRunId
                    publishPhaseEntered(
                        oldPhase = phase,
                        newPhase = previousPhase,
                        nestingDepth = previousPhaseNestingDepth,
                        runId = previousPhaseRunId,
                    )
                }
            }
        }
    }

    suspend fun cleanup() {
        if (!enabled) return
        removeAppliedSparks()
    }

    fun getCurrentPhase(): CognitivePhase? = if (enabled) currentPhase else null

    fun isPhaseActive(): Boolean = enabled && currentPhase != null

    private fun isPhaseEnabled(phase: CognitivePhase): Boolean = activePhases.contains(phase)

    /**
     * The built-in [PhaseSpark] for [phase] plus any declarative sparks the agent's
     * library selects. Only called when [injectPhaseSparks] is on: selection is part
     * of prompt injection, so a silently bracketed run never consults the library.
     */
    private fun selectSparksFor(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
    ): List<PhaseSpark> {
        val sparks = mutableListOf<PhaseSpark>(PhaseSpark.forPhase(phase))
        val lib = library
        if (AmpereSpikeFlags.declarativeSparksEnabled && lib != null) {
            val context = selectionContext ?: SparkSelectionContext(phase = phase, text = "")
            sparks += runCatching { lib.selectFor(context) }.getOrElse { emptyList() }
        }
        return sparks
    }

    private suspend fun removeAppliedSparks(
        restoredToPhase: CognitivePhase? = null,
        nestingDepth: Int = currentPhaseNestingDepth,
    ) {
        val exitedPhase = currentPhase ?: return
        val exitedRunId = currentPhaseRunId
        repeat(appliedSparks.size) {
            agent.unspark()
        }
        appliedSparks.clear()
        currentPhase = null
        currentPhaseNestingDepth = 0
        currentPhaseRunId = null
        if (injectPhaseSparks) agent.currentCognitivePhase = restoredToPhase
        publishPhaseExited(
            exitedPhase = exitedPhase,
            restoredToPhase = restoredToPhase,
            nestingDepth = nestingDepth,
            runId = exitedRunId,
        )
    }

    private suspend fun publishPhaseEntered(
        oldPhase: CognitivePhase?,
        newPhase: CognitivePhase,
        nestingDepth: Int,
        runId: RunId?,
    ) {
        if (!publishBrackets) return
        eventApi?.let { api ->
            api.publish(
                event = CognitivePhaseEvent.PhaseEntered(
                    eventId = generateUUID(agent.id, newPhase.name, nestingDepth.toString()),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Agent(agent.id),
                    agentId = agent.id,
                    oldPhase = oldPhase,
                    newPhase = newPhase,
                    nestingDepth = nestingDepth,
                ),
                runId = runId,
            )
        }
    }

    private suspend fun publishPhaseExited(
        exitedPhase: CognitivePhase,
        restoredToPhase: CognitivePhase?,
        nestingDepth: Int,
        runId: RunId?,
    ) {
        if (!publishBrackets) return
        eventApi?.let { api ->
            api.publish(
                event = CognitivePhaseEvent.PhaseExited(
                    eventId = generateUUID(agent.id, exitedPhase.name, nestingDepth.toString()),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Agent(agent.id),
                    agentId = agent.id,
                    exitedPhase = exitedPhase,
                    restoredToPhase = restoredToPhase,
                    nestingDepth = nestingDepth,
                ),
                runId = runId,
            )
        }
    }

    companion object {
        private val DEFAULT_PHASES: Set<CognitivePhase> = enumValues<CognitivePhase>().toSet()

        /**
         * Whether the `AMPERE_PHASE_SPARKS` developer switch is set to `"true"`.
         *
         * This is an override, not a default: when set it enables phases, bracket
         * publication, and prompt injection together, whatever a caller's
         * [PhaseSparkConfig] says. Unset (the normal case) it contributes nothing and
         * the config decides on its own.
         */
        fun isPhaseSparkEnabled(): Boolean {
            return try {
                getEnvironmentVariable("AMPERE_PHASE_SPARKS")
                    ?.equals("true", ignoreCase = true)
                    ?: false
            } catch (_: Exception) {
                false
            }
        }

        fun <S : AgentState> create(
            agent: AutonomousAgent<S>,
            phaseConfig: PhaseSparkConfig? = null,
            eventApi: AgentEventApi? = null,
        ): PhaseSparkManager<S> = createInternal(agent, phaseConfig, library = null, eventApi = eventApi)

        internal fun <S : AgentState> createWithLibrary(
            agent: AutonomousAgent<S>,
            phaseConfig: PhaseSparkConfig? = null,
            library: PhaseSparkLibrary? = null,
            eventApi: AgentEventApi? = null,
        ): PhaseSparkManager<S> = createInternal(agent, phaseConfig, library, eventApi)

        internal fun <S : AgentState> internalCreate(
            agent: AutonomousAgent<S>,
            enabled: Boolean,
            publishBrackets: Boolean = true,
            injectPhaseSparks: Boolean = true,
            activePhases: Set<CognitivePhase> = DEFAULT_PHASES,
            library: PhaseSparkLibrary? = null,
            eventApi: AgentEventApi? = null,
        ): PhaseSparkManager<S> = PhaseSparkManager(
            agent = agent,
            enabled = enabled,
            publishBrackets = publishBrackets,
            injectPhaseSparks = injectPhaseSparks,
            activePhases = activePhases,
            library = library,
            eventApi = eventApi,
        )

        private fun <S : AgentState> createInternal(
            agent: AutonomousAgent<S>,
            phaseConfig: PhaseSparkConfig?,
            library: PhaseSparkLibrary?,
            eventApi: AgentEventApi?,
        ): PhaseSparkManager<S> {
            // The env var is a developer override (AMPR-387): it enables phases and both
            // switches, whatever the config says. Absent it, the config alone decides.
            val envOverride = isPhaseSparkEnabled()
            val enabled = (phaseConfig?.enabled ?: false) || envOverride
            val phases = phaseConfig?.phases ?: DEFAULT_PHASES
            return PhaseSparkManager(
                agent = agent,
                enabled = enabled,
                publishBrackets = envOverride || (phaseConfig?.publishBrackets ?: true),
                injectPhaseSparks = envOverride || (phaseConfig?.injectPhaseSparks ?: true),
                activePhases = phases,
                library = library,
                eventApi = eventApi,
            )
        }
    }
}
