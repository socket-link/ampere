package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.stubAgentConfiguration

private val stubOutcome = ExecutionOutcome.NoChanges.Success(
    executorId = "test-executor",
    ticketId = "test-ticket",
    taskId = "test-task",
    executionStartTimestamp = Clock.System.now(),
    executionEndTimestamp = Clock.System.now(),
    message = "ok",
)

class PhaseSparkManagerTest {

    private class TestAgent(
        override val agentConfiguration: AgentConfiguration = stubAgentConfiguration(),
    ) : AutonomousAgent<AgentState>() {
        override val id: AgentId = "test-agent"
        override val initialState: AgentState = AgentState()

        override val runLLMToEvaluatePerception: (Perception<AgentState>) -> Idea = { Idea.blank }
        override val runLLMToPlan: (Task, List<Idea>) -> Plan = { _, _ -> Plan.blank }
        override val runLLMToExecuteTask: (Task) -> Outcome = { stubOutcome }
        override val runLLMToExecuteTool: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome = { _, _ -> stubOutcome }
        override val runLLMToEvaluateOutcomes: (List<Outcome>) -> Idea = { Idea.blank }

        override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge {
            return Knowledge.FromOutcome(
                outcomeId = outcome.id,
                approach = "test approach",
                learnings = "test learnings",
                timestamp = Clock.System.now(),
            )
        }
    }

    private suspend fun MutableList<CognitivePhaseEvent>.awaitCount(count: Int): List<CognitivePhaseEvent> {
        withTimeout(1000) {
            while (size < count) {
                delay(10)
            }
        }
        return toList()
    }

    private fun EventSerialBus.collectPhaseEvents(
        agentId: AgentId,
        events: MutableList<CognitivePhaseEvent>,
    ) {
        subscribe<CognitivePhaseEvent.PhaseEntered, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = CognitivePhaseEvent.PhaseEntered.EVENT_TYPE,
        ) { event, _ ->
            events += event
        }
        subscribe<CognitivePhaseEvent.PhaseExited, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = CognitivePhaseEvent.PhaseExited.EVENT_TYPE,
        ) { event, _ ->
            events += event
        }
    }

    @Test
    fun `enterPhase applies and switches phase sparks`() {
        val agent = TestAgent()
        val manager = PhaseSparkManager(agent, enabled = true)

        manager.enterPhase(CognitivePhase.PERCEIVE)
        assertEquals(1, agent.sparkDepth)
        assertTrue(agent.cognitiveState.contains("[Phase:Perceive]"))

        manager.enterPhase(CognitivePhase.PLAN)
        assertEquals(1, agent.sparkDepth)
        assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
        assertFalse(agent.cognitiveState.contains("Phase:Perceive"))
    }

    @Test
    fun `enterPhase publishes PhaseEntered after current phase is assigned`() = runTest(UnconfinedTestDispatcher()) {
        val agent = TestAgent()
        val bus = EventSerialBus(this)
        val events = mutableListOf<CognitivePhaseEvent>()
        bus.collectPhaseEvents(agent.id, events)
        val manager = PhaseSparkManager(agent, enabled = true, eventBus = bus)

        manager.enterPhase(CognitivePhase.PERCEIVE)

        val event = events.awaitCount(1).single()
        assertEquals(
            CognitivePhaseEvent.PhaseEntered(
                eventId = event.eventId,
                timestamp = event.timestamp,
                eventSource = event.eventSource,
                agentId = agent.id,
                oldPhase = null,
                newPhase = CognitivePhase.PERCEIVE,
                nestingDepth = 0,
            ),
            event,
        )
    }

    @Test
    fun `sequential transitions publish exit before enter`() = runTest(UnconfinedTestDispatcher()) {
        val agent = TestAgent()
        val bus = EventSerialBus(this)
        val events = mutableListOf<CognitivePhaseEvent>()
        bus.collectPhaseEvents(agent.id, events)
        val manager = PhaseSparkManager(agent, enabled = true, eventBus = bus)

        manager.enterPhase(CognitivePhase.PERCEIVE)
        manager.enterPhase(CognitivePhase.PLAN)

        val sequence = events.awaitCount(3)
        assertEquals(CognitivePhaseEvent.PhaseEntered.EVENT_TYPE, sequence[0].eventType)
        assertEquals(CognitivePhase.PERCEIVE, (sequence[0] as CognitivePhaseEvent.PhaseEntered).newPhase)
        assertEquals(CognitivePhaseEvent.PhaseExited.EVENT_TYPE, sequence[1].eventType)
        assertEquals(CognitivePhase.PERCEIVE, (sequence[1] as CognitivePhaseEvent.PhaseExited).exitedPhase)
        assertEquals(null, (sequence[1] as CognitivePhaseEvent.PhaseExited).restoredToPhase)
        assertEquals(CognitivePhaseEvent.PhaseEntered.EVENT_TYPE, sequence[2].eventType)
        assertEquals(CognitivePhase.PERCEIVE, (sequence[2] as CognitivePhaseEvent.PhaseEntered).oldPhase)
        assertEquals(CognitivePhase.PLAN, (sequence[2] as CognitivePhaseEvent.PhaseEntered).newPhase)
    }

    @Test
    fun `withPhase restores previous phase spark`() = runTest(UnconfinedTestDispatcher()) {
        val agent = TestAgent()
        val manager = PhaseSparkManager(agent, enabled = true)

        manager.enterPhase(CognitivePhase.PERCEIVE)
        manager.withPhase(CognitivePhase.PLAN) {
            assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
        }

        assertTrue(agent.cognitiveState.contains("[Phase:Perceive]"))
    }

    @Test
    fun `nested withPhase publishes depth-aware bracket events`() = runTest(UnconfinedTestDispatcher()) {
        val agent = TestAgent()
        val bus = EventSerialBus(this)
        val events = mutableListOf<CognitivePhaseEvent>()
        bus.collectPhaseEvents(agent.id, events)
        val manager = PhaseSparkManager(agent, enabled = true, eventBus = bus)

        manager.withPhase(CognitivePhase.PERCEIVE) {
            assertEquals(1, agent.sparkDepth)
            manager.withPhase(CognitivePhase.PLAN) {
                assertEquals(2, agent.sparkDepth)
                assertTrue(agent.cognitiveState.contains("[Phase:Perceive]"))
                assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
            }
            assertEquals(1, agent.sparkDepth)
            assertTrue(agent.cognitiveState.contains("[Phase:Perceive]"))
        }

        assertEquals(0, agent.sparkDepth)
        val sequence = events.awaitCount(5)
        assertEquals(
            listOf(
                "PhaseEntered:PERCEIVE:null:0",
                "PhaseEntered:PLAN:PERCEIVE:1",
                "PhaseExited:PLAN:PERCEIVE:1",
                "PhaseEntered:PERCEIVE:PLAN:0",
                "PhaseExited:PERCEIVE:null:0",
            ),
            sequence.map { event ->
                when (event) {
                    is CognitivePhaseEvent.PhaseEntered ->
                        "PhaseEntered:${event.newPhase}:${event.oldPhase}:${event.nestingDepth}"
                    is CognitivePhaseEvent.PhaseExited ->
                        "PhaseExited:${event.exitedPhase}:${event.restoredToPhase}:${event.nestingDepth}"
                }
            },
        )
    }

    @Test
    fun `create honors configured phases`() {
        val phaseConfig = PhaseSparkConfig(
            enabled = true,
            phases = setOf(CognitivePhase.PLAN),
        )
        val agent = TestAgent(
            agentConfiguration = stubAgentConfiguration().copy(
                cognitiveConfig = CognitiveConfig(phaseSparks = phaseConfig),
            ),
        )
        val manager = PhaseSparkManager.create(agent, phaseConfig)

        manager.enterPhase(CognitivePhase.PERCEIVE)
        assertEquals(0, agent.sparkDepth)

        manager.enterPhase(CognitivePhase.PLAN)
        assertEquals(1, agent.sparkDepth)
        assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
    }

    @Test
    fun `disabled manager leaves spark stack unchanged`() {
        val agent = TestAgent()
        val manager = PhaseSparkManager(agent, enabled = false)

        manager.enterPhase(CognitivePhase.PERCEIVE)
        assertEquals(0, agent.sparkDepth)
    }

    @Test
    fun `disabled manager does not publish phase events`() = runTest(UnconfinedTestDispatcher()) {
        val agent = TestAgent()
        val bus = EventSerialBus(this)
        val events = mutableListOf<CognitivePhaseEvent>()
        bus.collectPhaseEvents(agent.id, events)
        val manager = PhaseSparkManager(agent, enabled = false, eventBus = bus)

        manager.enterPhase(CognitivePhase.PERCEIVE)
        manager.cleanup()
        delay(100)

        assertEquals(emptyList(), events)
    }

    @Test
    fun `library is ignored when spike flag is off`() {
        val sources = listOf(
            DeclarativePhaseSparkSource(
                id = "cooking-domain",
                name = "Cooking Domain",
                whenToUse = "recipes",
                body = "Body",
                phases = setOf(CognitivePhase.PLAN),
                tags = setOf("cooking"),
            ),
        )
        val library = DefaultPhaseSparkLibrary.fromSources(sources)
        val agent = TestAgent()
        val manager = PhaseSparkManager.internalCreate(agent, enabled = true, library = library)
        // Flag defaults to off.
        manager.enterPhase(CognitivePhase.PLAN)
        assertEquals(1, agent.sparkDepth)
        manager.cleanup()
    }

    @Test
    fun `library augments built-in phase spark when spike flag is on and selection matches`() = runTest(UnconfinedTestDispatcher()) {
        val sources = listOf(
            DeclarativePhaseSparkSource(
                id = "cooking-domain",
                name = "Cooking Domain",
                whenToUse = "tasks about recipes and ingredients",
                body = "Cooking body",
                phases = setOf(CognitivePhase.PLAN),
                tags = setOf("cooking"),
            ),
        )
        val library = DefaultPhaseSparkLibrary.fromSources(sources)
        val agent = TestAgent()
        val manager = PhaseSparkManager.internalCreate(agent, enabled = true, library = library)
        try {
            AmpereSpikeFlags.declarativeSparksEnabled = true
            manager.enterPhase(
                CognitivePhase.PLAN,
                SparkSelectionContext(phase = CognitivePhase.PLAN, text = "draft a recipe"),
            )
            assertEquals(2, agent.sparkDepth)
            assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
            assertTrue(agent.cognitiveState.contains("[PhaseSpark:cooking-domain]"))

            manager.cleanup()
            assertEquals(0, agent.sparkDepth)
        } finally {
            AmpereSpikeFlags.declarativeSparksEnabled = false
        }
    }

    @Test
    fun `multi-spark library push and pop preserves order on exit`() = runTest(UnconfinedTestDispatcher()) {
        val extraSources = listOf(
            DeclarativePhaseSparkSource(
                id = "extra-a",
                name = "Extra A",
                whenToUse = "always",
                body = "Body A",
                phases = setOf(CognitivePhase.PLAN),
            ),
            DeclarativePhaseSparkSource(
                id = "extra-b",
                name = "Extra B",
                whenToUse = "always",
                body = "Body B",
                phases = setOf(CognitivePhase.PLAN),
            ),
        )
        val library = DefaultPhaseSparkLibrary.fromSources(extraSources)
        val agent = TestAgent()
        val manager = PhaseSparkManager.internalCreate(agent, enabled = true, library = library)
        try {
            AmpereSpikeFlags.declarativeSparksEnabled = true
            manager.enterPhase(CognitivePhase.PLAN)
            // Built-in Phase:Plan + 2 declarative sparks
            assertEquals(3, agent.sparkDepth)
            assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
            assertTrue(agent.cognitiveState.contains("[PhaseSpark:extra-a]"))
            assertTrue(agent.cognitiveState.contains("[PhaseSpark:extra-b]"))

            manager.cleanup()
            assertEquals(0, agent.sparkDepth)
        } finally {
            AmpereSpikeFlags.declarativeSparksEnabled = false
        }
    }
}
