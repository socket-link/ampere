package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
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
    fun `withPhase restores previous phase spark`() = runBlocking {
        val agent = TestAgent()
        val manager = PhaseSparkManager(agent, enabled = true)

        manager.enterPhase(CognitivePhase.PERCEIVE)
        manager.withPhase(CognitivePhase.PLAN) {
            assertTrue(agent.cognitiveState.contains("[Phase:Plan]"))
        }

        assertTrue(agent.cognitiveState.contains("[Phase:Perceive]"))
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
}
