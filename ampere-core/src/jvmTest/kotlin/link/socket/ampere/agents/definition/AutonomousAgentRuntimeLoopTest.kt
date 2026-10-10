package link.socket.ampere.agents.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

/**
 * The runtime loop's task lifecycle (AMPR-397).
 *
 * `executePlan` records each plan step as the agent's current task. Before the fix
 * nothing cleared it, so the next iteration read the plan's last step back as its
 * own assignment and planned it again once a second, forever — the only place an
 * agent's next task came from its own output.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutonomousAgentRuntimeLoopTest {

    private fun codeTask(id: String) = Task.CodeChange(
        id = id,
        status = TaskStatus.Pending,
        description = "description for $id",
    )

    private val assignmentA = codeTask("assignment-A")
    private val assignmentB = codeTask("assignment-B")
    private val planStepOne = codeTask("step-1")
    private val planStepTwo = codeTask("step-2")

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Not needed for tests")
        override val model: AIModel
            get() = throw NotImplementedError("Not needed for tests")

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    /**
     * Records every simulated model call the loop makes. Each `runLLMTo…` hook is
     * the agent's only route to a provider, so an empty [modelCalls] means the
     * iteration cost nothing.
     */
    private class RecordingAgent(
        private val planFor: (Task) -> Plan,
    ) : AutonomousAgent<AgentState>() {

        override val id: AgentId = "RecordingAgent"
        override val initialState: AgentState = AgentState()
        override val agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
        )

        val modelCalls = mutableListOf<String>()
        val plannedTasks = mutableListOf<Task>()
        val executedTasks = mutableListOf<Task>()

        override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea = { _ ->
            modelCalls += "perceive"
            Idea(name = "perceived")
        }

        override val runLLMToPlan: (
            task: Task,
            ideas: List<Idea>,
            relevantKnowledge: List<KnowledgeWithScore>,
        ) -> Plan = { task, _, _ ->
            modelCalls += "plan:${task.id}"
            plannedTasks += task
            planFor(task)
        }

        override val runLLMToExecuteTask: (task: Task, priorResults: List<StepOutcome>) -> Outcome = { task, _ ->
            modelCalls += "execute:${task.id}"
            executedTasks += task
            ExecutionOutcome.NoChanges.Success(
                executorId = id,
                ticketId = "RecordingTicket",
                taskId = task.id,
                executionStartTimestamp = Clock.System.now(),
                executionEndTimestamp = Clock.System.now(),
                message = "Success",
            )
        }

        override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
            { _, _ -> throw NotImplementedError("Tool execution not needed for runtime loop tests") }

        override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea = { _ ->
            modelCalls += "learn"
            Idea(name = "next")
        }

        override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge =
            Knowledge.FromOutcome(
                outcomeId = outcome.id,
                approach = "approach",
                learnings = "learnings",
                timestamp = Clock.System.now(),
            )
    }

    private fun agentPlanning(vararg steps: Task) = RecordingAgent { task ->
        Plan.ForIdea(
            idea = Idea(name = "plan for ${task.id}"),
            estimatedComplexity = 1,
            tasks = steps.toList(),
        )
    }

    @Test
    fun `loop does not re-plan the last step of its own plan`() = runTest {
        val agent = agentPlanning(planStepOne, planStepTwo)

        agent.rememberNewTask(assignmentA)
        agent.initialize(backgroundScope)

        // One iteration, then four more intervals' worth of virtual time.
        advanceTimeBy(100.milliseconds)
        assertEquals<List<Task>>(
            listOf(assignmentA),
            agent.plannedTasks,
            "first iteration plans its assignment",
        )
        assertEquals<List<Task>>(listOf(planStepOne, planStepTwo), agent.executedTasks)

        advanceTimeBy(4.seconds)
        agent.pauseAgent()

        assertEquals<List<Task>>(
            listOf(assignmentA),
            agent.plannedTasks,
            "the loop must not plan anything after the assignment is finished",
        )
        assertFalse(
            agent.plannedTasks.contains(planStepTwo),
            "the plan's last step must never be read back as an assignment",
        )
    }

    @Test
    fun `loop finishes the assignment it executed`() = runTest {
        val agent = agentPlanning(planStepOne, planStepTwo)
        val baselineSparkDepth = agent.sparkDepth

        agent.rememberNewTask(assignmentA)
        assertTrue(agent.sparkDepth > baselineSparkDepth, "assignment pushes a TaskSpark")

        agent.initialize(backgroundScope)
        advanceTimeBy(100.milliseconds)

        // Asserted before pausing: `pauseAgent` resets current memory itself,
        // which would blank the task whether the loop had finished it or not.
        assertEquals(
            Task.blank,
            agent.getCurrentState().getCurrentMemory().task,
            "the executed assignment is cleared, not left as the next iteration's task",
        )
        assertEquals(
            baselineSparkDepth,
            agent.sparkDepth,
            "the assignment's TaskSpark is released with the assignment",
        )

        agent.pauseAgent()
    }

    @Test
    fun `agent with no assignment makes no model call`() = runTest {
        val agent = agentPlanning(planStepOne)

        agent.initialize(backgroundScope)
        advanceTimeBy(5.seconds)
        agent.pauseAgent()

        assertEquals<List<String>>(
            emptyList(),
            agent.modelCalls,
            "an idle agent must not reason about a blank task",
        )
    }

    @Test
    fun `loop plans the next assignment once the previous one is finished`() = runTest {
        val agent = agentPlanning(planStepOne, planStepTwo)

        agent.rememberNewTask(assignmentA)
        agent.initialize(backgroundScope)
        advanceTimeBy(100.milliseconds)

        // Idle for two intervals, then hand the agent new work.
        advanceTimeBy(2.seconds)
        assertEquals<List<Task>>(listOf(assignmentA), agent.plannedTasks)

        agent.rememberNewTask(assignmentB)
        advanceTimeBy(2.seconds)
        agent.pauseAgent()

        assertEquals<List<Task>>(
            listOf(assignmentA, assignmentB),
            agent.plannedTasks,
            "idling must not make the loop deaf to a new assignment",
        )
    }
}
