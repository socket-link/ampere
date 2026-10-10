package link.socket.ampere.agents.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.LanguageSparkIds
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.events.api.AgentEventApi

/**
 * AMPR-387: `CognitiveConfig.phaseSparks` is the documented way to turn phase handling
 * on, and until this ticket [SparkBasedAgent] built its `AgentConfiguration` without a
 * `cognitiveConfig` — so the manager it built for itself always saw the default (off)
 * and only `AMPERE_PHASE_SPARKS` could enable anything.
 *
 * These tests drive the agent's *own* manager ([PhaseDrivableAgent.ownPhaseSparkManager])
 * rather than one assembled in the test, because the seam under test is exactly how the
 * agent configures it.
 *
 * Bodies use `runBlocking`: the door persists on a real IO dispatcher, which `runTest`'s
 * virtual clock would skip past.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SparkBasedAgentPhaseSparkConfigTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())
    private val library: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

    /**
     * A [SparkBasedAgent] that exposes the protected manager hook. Nothing else is
     * customized: the point is to observe the manager the agent builds from its own
     * configuration.
     */
    private class PhaseDrivableAgent(
        agentId: AgentId,
        eventApi: AgentEventApi?,
        cognitiveConfig: CognitiveConfig,
    ) : SparkBasedAgent<CodeState>(
        agentId = agentId,
        cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
        initialState = CodeState.blank,
        _eventApi = eventApi,
        _cognitiveConfig = cognitiveConfig,
    ) {
        fun ownPhaseSparkManager(): PhaseSparkManager<CodeState> = createPhaseSparkManager()
    }

    /**
     * An agent carrying the Kotlin language spark. That fixture has a
     * `## When Planning` section, so entering PLAN with any prompt injection at all
     * changes [SparkBasedAgent.currentSystemPrompt] — which is what makes
     * "the prompt is unchanged" a real assertion rather than a vacuous one.
     */
    private fun agentWith(
        config: PhaseSparkConfig,
        door: InMemoryEventApi.Handle? = null,
    ): PhaseDrivableAgent {
        val agent = PhaseDrivableAgent(
            agentId = AGENT_ID,
            eventApi = door?.api,
            cognitiveConfig = CognitiveConfig(phaseSparks = config),
        )
        agent.spark<SparkBasedAgent<CodeState>>(
            requireNotNull(library.languageSparkById(LanguageSparkIds.KOTLIN)) {
                "language-kotlin.spark.md should be in the bundled library"
            },
        )
        return agent
    }

    private fun door(): InMemoryEventApi.Handle =
        InMemoryEventApi.open(agentId = AGENT_ID, scope = doorScope)

    /** Phases entered, oldest first: the store returns rows newest-first. */
    private suspend fun InMemoryEventApi.Handle.enteredPhases(): List<CognitivePhase> =
        repository.getEventsByType(CognitivePhaseEvent.PhaseEntered.EVENT_TYPE).getOrThrow()
            .reversed()
            .map { assertIs<CognitivePhaseEvent.PhaseEntered>(it).newPhase }

    /** Phases exited, oldest first. */
    private suspend fun InMemoryEventApi.Handle.exitedPhases(): List<CognitivePhase> =
        repository.getEventsByType(CognitivePhaseEvent.PhaseExited.EVENT_TYPE).getOrThrow()
            .reversed()
            .map { assertIs<CognitivePhaseEvent.PhaseExited>(it).exitedPhase }

    @Test
    fun `an agent built with phaseSparks enabled carries it into its AgentConfiguration`() {
        val config = PhaseSparkConfig(enabled = true, injectPhaseSparks = false)
        val agent = agentWith(config)

        assertEquals(config, agent.agentConfiguration.cognitiveConfig.phaseSparks)
        assertTrue(
            agent.ownPhaseSparkManager().enabled,
            "the manager the agent builds for itself must honour the configured switch",
        )
    }

    @Test
    fun `phase handling stays off when nothing is configured`() = runBlocking<Unit> {
        door().use { door ->
            val agent = agentWith(PhaseSparkConfig(), door)
            val baseline = agent.currentSystemPrompt
            val manager = agent.ownPhaseSparkManager()

            assertFalse(manager.enabled)
            manager.withPhase(CognitivePhase.PLAN) {
                assertFalse(manager.isPhaseActive())
            }

            assertEquals(baseline, agent.currentSystemPrompt)
            assertEquals(emptyList(), door.enteredPhases())
            assertEquals(emptyList(), door.exitedPhases())
        }
    }

    /**
     * The outcome AMPR-387 exists for: a consumer brackets a run's phases without
     * touching the environment and without changing what the agent is told.
     */
    @Test
    fun `enabled with injectPhaseSparks off brackets each withPhase and leaves the prompt unchanged`() =
        runBlocking<Unit> {
            door().use { door ->
                val agent = agentWith(
                    PhaseSparkConfig(enabled = true, injectPhaseSparks = false),
                    door,
                )
                val baseline = agent.currentSystemPrompt
                val baselineDepth = agent.sparkDepth
                val manager = agent.ownPhaseSparkManager()

                for (phase in listOf(CognitivePhase.PLAN, CognitivePhase.EXECUTE)) {
                    manager.withPhase(phase) {
                        assertTrue(manager.isPhaseActive(), "$phase should be entered")
                        assertEquals(phase, manager.getCurrentPhase())
                        assertEquals(baselineDepth, agent.sparkDepth, "$phase should push no spark")
                        assertEquals(baseline, agent.currentSystemPrompt, "$phase should leave the prompt alone")
                    }
                }

                assertEquals(baseline, agent.currentSystemPrompt)
                assertEquals(listOf(CognitivePhase.PLAN, CognitivePhase.EXECUTE), door.enteredPhases())
                assertEquals(listOf(CognitivePhase.PLAN, CognitivePhase.EXECUTE), door.exitedPhases())
            }
        }

    /**
     * `enterPhase` / `cleanup` is the other way to drive the manager, and it is where
     * the bookkeeping had to change: "a phase is active" used to mean "sparks are on
     * the stack", which is false for a bracketed-but-not-injected phase and would have
     * swallowed the `PhaseExited`.
     */
    @Test
    fun `enterPhase then cleanup brackets a silent phase`() = runBlocking<Unit> {
        door().use { door ->
            val agent = agentWith(
                PhaseSparkConfig(enabled = true, injectPhaseSparks = false),
                door,
            )
            val baseline = agent.currentSystemPrompt
            val manager = agent.ownPhaseSparkManager()

            manager.enterPhase(CognitivePhase.PERCEIVE)
            assertTrue(manager.isPhaseActive())
            assertEquals(CognitivePhase.PERCEIVE, manager.getCurrentPhase())
            assertEquals(baseline, agent.currentSystemPrompt)

            manager.cleanup()
            assertFalse(manager.isPhaseActive())
            assertEquals(listOf(CognitivePhase.PERCEIVE), door.enteredPhases())
            assertEquals(listOf(CognitivePhase.PERCEIVE), door.exitedPhases())
        }
    }

    /**
     * The default when `enabled = true`: both switches on, i.e. pre-AMPR-387 behaviour
     * for anyone who had managed to enable phases at all.
     */
    @Test
    fun `enabled alone brackets and injects`() = runBlocking<Unit> {
        door().use { door ->
            val agent = agentWith(PhaseSparkConfig(enabled = true), door)
            val baseline = agent.currentSystemPrompt
            val baselineDepth = agent.sparkDepth
            val manager = agent.ownPhaseSparkManager()

            manager.withPhase(CognitivePhase.PLAN) {
                assertEquals(baselineDepth + 1, agent.sparkDepth)
                assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
                assertTrue(agent.currentSystemPrompt.contains("## Cognitive Phase: PLAN"))
                assertNotEquals(baseline, agent.currentSystemPrompt)
            }

            assertEquals(baseline, agent.currentSystemPrompt)
            assertEquals(listOf(CognitivePhase.PLAN), door.enteredPhases())
            assertEquals(listOf(CognitivePhase.PLAN), door.exitedPhases())
        }
    }

    /** The mirror switch: inject the guidance, publish nothing. */
    @Test
    fun `publishBrackets off injects without publishing`() = runBlocking<Unit> {
        door().use { door ->
            val agent = agentWith(
                PhaseSparkConfig(enabled = true, publishBrackets = false),
                door,
            )
            val baselineDepth = agent.sparkDepth
            val manager = agent.ownPhaseSparkManager()

            manager.withPhase(CognitivePhase.PLAN) {
                assertEquals(baselineDepth + 1, agent.sparkDepth)
                assertTrue(agent.currentSystemPrompt.contains("## Cognitive Phase: PLAN"))
            }

            assertEquals(emptyList(), door.enteredPhases())
            assertEquals(emptyList(), door.exitedPhases())
        }
    }

    @Test
    fun `configured phases still narrow which phases are bracketed`() = runBlocking<Unit> {
        door().use { door ->
            val agent = agentWith(
                PhaseSparkConfig(
                    enabled = true,
                    phases = setOf(CognitivePhase.PLAN),
                    injectPhaseSparks = false,
                ),
                door,
            )
            val manager = agent.ownPhaseSparkManager()

            manager.withPhase(CognitivePhase.EXECUTE) {
                assertFalse(manager.isPhaseActive(), "EXECUTE is not in the configured set")
            }
            manager.withPhase(CognitivePhase.PLAN) {
                assertTrue(manager.isPhaseActive())
            }

            assertEquals(listOf(CognitivePhase.PLAN), door.enteredPhases())
        }
    }

    @Test
    fun `SparkAgentFactory passes its cognitiveConfig to the agents it builds`() {
        val config = PhaseSparkConfig(enabled = true, injectPhaseSparks = false)
        val agent = SparkAgentFactory(
            scope = doorScope,
            workspace = ExecutionWorkspace(baseDirectory = "/tmp/ampr-387"),
            sparkRegistry = library,
            cognitiveConfig = CognitiveConfig(phaseSparks = config),
        ).createAgent(id = "spark-factory-agent", affinity = CognitiveAffinity.ANALYTICAL)

        assertEquals(config, agent.agentConfiguration.cognitiveConfig.phaseSparks)
    }

    @Test
    fun `the Code role factory passes its cognitiveConfig through`() {
        val config = PhaseSparkConfig(enabled = true, publishBrackets = false)
        val agent = SparkBasedAgent.Code(
            sparkRegistry = library,
            agentId = "code-role-factory-agent",
            cognitiveConfig = CognitiveConfig(phaseSparks = config),
        )

        assertEquals(config, agent.agentConfiguration.cognitiveConfig.phaseSparks)
    }

    private companion object {
        const val AGENT_ID: AgentId = "phase-spark-config-test-agent"
    }
}
