package link.socket.ampere.agents.domain.cognition.sparks

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.bus.EventSerialBus
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
 */
class PhaseSparkManager<S : AgentState> private constructor(
    private val agent: AutonomousAgent<S>,
    val enabled: Boolean,
    private val activePhases: Set<CognitivePhase>,
    private val library: PhaseSparkLibrary?,
    private val eventBus: EventSerialBus?,
) {
    constructor(
        agent: AutonomousAgent<S>,
        enabled: Boolean = isPhaseSparkEnabled(),
        activePhases: Set<CognitivePhase> = DEFAULT_PHASES,
        eventBus: EventSerialBus? = null,
    ) : this(
        agent = agent,
        enabled = enabled,
        activePhases = activePhases,
        library = null,
        eventBus = eventBus,
    )

    private var appliedSparks: MutableList<PhaseSpark> = mutableListOf()
    private var currentPhase: CognitivePhase? = null
    private var currentPhaseNestingDepth: Int = 0
    private var withPhaseNestingDepth: Int = 0

    fun enterPhase(phase: CognitivePhase) {
        enterPhaseInternal(phase, selectionContext = null)
    }

    internal fun enterPhase(phase: CognitivePhase, selectionContext: SparkSelectionContext?) {
        enterPhaseInternal(phase, selectionContext)
    }

    private fun enterPhaseInternal(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
        nestingDepth: Int = 0,
        oldPhaseOverride: CognitivePhase? = null,
    ) {
        if (!enabled) return

        val oldPhase = oldPhaseOverride ?: currentPhase
        if (appliedSparks.isNotEmpty() && currentPhase != phase) {
            removeAppliedSparks()
        }

        if (!isPhaseEnabled(phase)) return

        if (currentPhase == phase && appliedSparks.isNotEmpty()) return

        val sparksToApply = mutableListOf<PhaseSpark>(PhaseSpark.forPhase(phase))
        val lib = library
        if (AmpereSpikeFlags.declarativeSparksEnabled && lib != null) {
            val context = selectionContext ?: SparkSelectionContext(phase = phase, text = "")
            val declarative = runCatching { lib.selectFor(context) }.getOrElse { emptyList() }
            sparksToApply += declarative
        }

        agent.currentCognitivePhase = phase
        publishPhaseEntered(oldPhase = oldPhase, newPhase = phase, nestingDepth = nestingDepth)
        for (spark in sparksToApply) {
            agent.spark<AutonomousAgent<S>>(spark)
            appliedSparks += spark
        }
        currentPhase = phase
        currentPhaseNestingDepth = nestingDepth
    }

    suspend fun <R> withPhase(phase: CognitivePhase, block: suspend () -> R): R =
        withPhaseInternal(phase, selectionContext = null, block)

    internal suspend fun <R> withPhase(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
        block: suspend () -> R,
    ): R = withPhaseInternal(phase, selectionContext, block)

    private suspend fun <R> withPhaseInternal(
        phase: CognitivePhase,
        selectionContext: SparkSelectionContext?,
        block: suspend () -> R,
    ): R {
        if (!enabled) return block()
        if (!isPhaseEnabled(phase)) return block()

        val nestingDepth = withPhaseNestingDepth
        val previousPhase = currentPhase
        val previousSparks = appliedSparks.toList()
        val previousPhaseNestingDepth = currentPhaseNestingDepth
        appliedSparks = mutableListOf()
        currentPhase = null
        enterPhaseInternal(
            phase = phase,
            selectionContext = selectionContext,
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

                if (previousPhase != null && previousSparks.isNotEmpty()) {
                    appliedSparks = previousSparks.toMutableList()
                    currentPhase = previousPhase
                    currentPhaseNestingDepth = previousPhaseNestingDepth
                    publishPhaseEntered(
                        oldPhase = phase,
                        newPhase = previousPhase,
                        nestingDepth = previousPhaseNestingDepth,
                    )
                }
            }
        }
    }

    fun cleanup() {
        if (!enabled) return
        removeAppliedSparks()
    }

    fun getCurrentPhase(): CognitivePhase? =
        if (enabled && appliedSparks.isNotEmpty()) currentPhase else null

    fun isPhaseActive(): Boolean = enabled && appliedSparks.isNotEmpty()

    private fun isPhaseEnabled(phase: CognitivePhase): Boolean = activePhases.contains(phase)

    private fun removeAppliedSparks(
        restoredToPhase: CognitivePhase? = null,
        nestingDepth: Int = currentPhaseNestingDepth,
    ) {
        if (appliedSparks.isEmpty()) return
        val exitedPhase = currentPhase ?: return
        repeat(appliedSparks.size) {
            agent.unspark()
        }
        appliedSparks.clear()
        currentPhase = null
        currentPhaseNestingDepth = 0
        agent.currentCognitivePhase = restoredToPhase
        publishPhaseExited(
            exitedPhase = exitedPhase,
            restoredToPhase = restoredToPhase,
            nestingDepth = nestingDepth,
        )
    }

    private fun publishPhaseEntered(
        oldPhase: CognitivePhase?,
        newPhase: CognitivePhase,
        nestingDepth: Int,
    ) {
        eventBus?.let { bus ->
            bus.publishAsync(
                CognitivePhaseEvent.PhaseEntered(
                    eventId = generateUUID(agent.id, newPhase.name, nestingDepth.toString()),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Agent(agent.id),
                    agentId = agent.id,
                    oldPhase = oldPhase,
                    newPhase = newPhase,
                    nestingDepth = nestingDepth,
                ),
            )
        }
    }

    private fun publishPhaseExited(
        exitedPhase: CognitivePhase,
        restoredToPhase: CognitivePhase?,
        nestingDepth: Int,
    ) {
        eventBus?.let { bus ->
            bus.publishAsync(
                CognitivePhaseEvent.PhaseExited(
                    eventId = generateUUID(agent.id, exitedPhase.name, nestingDepth.toString()),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Agent(agent.id),
                    agentId = agent.id,
                    exitedPhase = exitedPhase,
                    restoredToPhase = restoredToPhase,
                    nestingDepth = nestingDepth,
                ),
            )
        }
    }

    companion object {
        private val DEFAULT_PHASES: Set<CognitivePhase> = enumValues<CognitivePhase>().toSet()

        /**
         * Checks if phase Sparks should be enabled.
         *
         * Resolution order:
         * 1. `AMPERE_PHASE_SPARKS` environment variable ("true" to enable)
         * 2. Default: disabled (false)
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
            eventBus: EventSerialBus? = null,
        ): PhaseSparkManager<S> = createInternal(agent, phaseConfig, library = null, eventBus = eventBus)

        internal fun <S : AgentState> createWithLibrary(
            agent: AutonomousAgent<S>,
            phaseConfig: PhaseSparkConfig? = null,
            library: PhaseSparkLibrary? = null,
            eventBus: EventSerialBus? = null,
        ): PhaseSparkManager<S> = createInternal(agent, phaseConfig, library, eventBus)

        internal fun <S : AgentState> internalCreate(
            agent: AutonomousAgent<S>,
            enabled: Boolean,
            activePhases: Set<CognitivePhase> = DEFAULT_PHASES,
            library: PhaseSparkLibrary? = null,
            eventBus: EventSerialBus? = null,
        ): PhaseSparkManager<S> = PhaseSparkManager(
            agent = agent,
            enabled = enabled,
            activePhases = activePhases,
            library = library,
            eventBus = eventBus,
        )

        private fun <S : AgentState> createInternal(
            agent: AutonomousAgent<S>,
            phaseConfig: PhaseSparkConfig?,
            library: PhaseSparkLibrary?,
            eventBus: EventSerialBus?,
        ): PhaseSparkManager<S> {
            val enabledFromConfig = phaseConfig?.enabled ?: false
            val enabled = enabledFromConfig || isPhaseSparkEnabled()
            val phases = phaseConfig?.phases ?: DEFAULT_PHASES
            return PhaseSparkManager(
                agent = agent,
                enabled = enabled,
                activePhases = phases,
                library = library,
                eventBus = eventBus,
            )
        }
    }
}
