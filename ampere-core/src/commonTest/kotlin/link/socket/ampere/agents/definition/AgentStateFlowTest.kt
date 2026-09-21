package link.socket.ampere.agents.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.IdeaId
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

class AgentStateFlowTest {

    @Test
    fun `stateFlow is the same instance on every access`() {
        val agent = StateFlowTestAgent()

        assertSame(agent.stateFlow, agent.stateFlow)
    }

    @Test
    fun `stateFlow starts at the initial state`() {
        val agent = StateFlowTestAgent()

        assertSame(agent.initialState, agent.stateFlow.value)
    }

    @Test
    fun `rememberNewIdea is visible on the next state read`() {
        val agent = StateFlowTestAgent()
        val idea = Idea(name = "Remembered idea")

        agent.rememberNewIdea(idea)

        assertEquals(idea, agent.getCurrentState().getCurrentMemory().idea)
        assertEquals(idea, agent.stateFlow.value.getCurrentMemory().idea)
    }

    @Test
    fun `rememberNewIdea is observed by a stateFlow collector`() = runTest {
        val agent = StateFlowTestAgent()
        val idea = Idea(name = "Collected idea")
        val observedIdeas = mutableListOf<IdeaId>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            agent.stateFlow.collect { state -> observedIdeas += state.getCurrentMemory().idea.id }
        }

        agent.rememberNewIdea(idea)

        assertEquals(listOf(Idea.blank.id, idea.id), observedIdeas)
    }

    @Test
    fun `every state update reaches a stateFlow collector`() = runTest {
        val agent = StateFlowTestAgent()
        var emissions = 0

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            agent.stateFlow.collect { emissions++ }
        }

        agent.rememberNewIdea(Idea(name = "First idea"))
        agent.rememberNewOutcome(Outcome.blank)
        agent.finishCurrentIdea()
        agent.resetAllMemory()

        // Initial value plus one emission per update; resetAllMemory performs two updates.
        assertEquals(6, emissions)
    }

    @Test
    fun `rememberNewIdea moves the previous idea into past memory`() {
        val agent = StateFlowTestAgent()
        val first = Idea(name = "First idea")
        val second = Idea(name = "Second idea")

        agent.rememberNewIdea(first)
        agent.rememberNewIdea(second)

        assertEquals(second, agent.getCurrentState().getCurrentMemory().idea)
        assertEquals(listOf(first.id), agent.getCurrentState().getPastMemory().ideas)
    }

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Not needed for tests")
        override val model: AIModel
            get() = throw NotImplementedError("Not needed for tests")
        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    private class StateFlowTestAgent : AutonomousAgent<AgentState>() {
        override val id: AgentId = "StateFlowTestAgent"
        override val initialState: AgentState = AgentState()
        override val agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
        )

        override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
            { _ -> Idea.blank }
        override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan = { _, _ -> Plan.blank }
        override val runLLMToExecuteTask: (task: Task) -> Outcome = { _ -> Outcome.blank }
        override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
            { _, _ -> throw NotImplementedError("Not needed for tests") }
        override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea = { _ -> Idea.blank }

        override fun callLLM(prompt: String): String {
            throw NotImplementedError("Not needed for tests")
        }

        override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge =
            Knowledge.FromOutcome(
                outcomeId = outcome.id,
                approach = "Test approach",
                learnings = "Test learnings",
                timestamp = Clock.System.now(),
            )
    }
}
