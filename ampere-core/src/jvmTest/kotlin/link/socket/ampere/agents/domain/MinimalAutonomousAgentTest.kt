package link.socket.ampere.agents.domain

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

@OptIn(ExperimentalCoroutinesApi::class)
class MinimalAutonomousAgentTest {

    private val stubIdea = Idea(name = "Perceived")

    private val stubPerception = Perception(
        ideas = listOf(stubIdea),
        currentState = AgentState(),
        timestamp = Clock.System.now(),
    )

    private val stubTicket = Ticket(
        id = "TestTicket",
        title = "TestTitle",
        description = "TestDescription",
        type = TicketType.TASK,
        priority = TicketPriority.LOW,
        status = TicketStatus.Ready,
        assignedAgentId = "TestAgent",
        createdByAgentId = "TestAgent",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now() + 1.seconds,
        dueDate = Clock.System.now() + 1.minutes,
    )

    private val stubTask = Task.CodeChange(
        id = "TestTask",
        status = TaskStatus.InProgress,
        description = "TestDescription",
        assignedTo = AssignedTo.Agent("TestAgent"),
    )

    private val stubPlan = Plan.ForIdea(
        idea = stubIdea,
        estimatedComplexity = 1,
        tasks = listOf(stubTask),
    )

    private val stubOutcome = ExecutionOutcome.NoChanges.Success(
        executorId = "TestExecutor",
        ticketId = stubTicket.id,
        taskId = stubTask.id,
        executionStartTimestamp = Clock.System.now(),
        executionEndTimestamp = Clock.System.now() + 1.seconds,
        message = "Success",
    )

    private val testScope = TestScope(UnconfinedTestDispatcher())

    // ==================== FAKE IMPLEMENTATIONS ====================

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Not needed for tests")
        override val model: AIModel
            get() = throw NotImplementedError("Not needed for tests")
        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    private class TestAutonomousAgent(
        private val perceiveResult: Idea = stubIdea,
        private val planResult: Plan = stubPlan,
        private val executeResult: ExecutionOutcome = ExecutionOutcome.NoChanges.Success(
            executorId = "TestExecutor",
            ticketId = "TestTicket",
            taskId = "TestTask",
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now() + 1.seconds,
            message = "Success",
        ),
    ) : AutonomousAgent<AgentState>() {
        override val id: AgentId = "TestAgent"
        override val initialState: AgentState = AgentState()
        override val agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
        )

        override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea = { _ -> perceiveResult }
        override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan = { _, _ -> planResult }
        override val runLLMToExecuteTask: (task: Task) -> Outcome = { _ -> executeResult }
        override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome = { _, _ -> executeResult }
        override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea = { _ -> perceiveResult }

        override fun callLLM(prompt: String): String {
            throw NotImplementedError("callLLM not needed for minimal agent tests")
        }

        // Expose protected methods for testing
        fun testRememberIdea(idea: Idea) = rememberNewIdea(idea)
        fun testRememberOutcome(outcome: Outcome) = rememberNewOutcome(outcome)
        fun testRememberPerception(perception: Perception<AgentState>) = rememberNewPerception(perception)
        fun testRememberPlan(plan: Plan) = rememberNewPlan(plan)
        fun testRememberTask(task: Task) = rememberNewTask(task)

        // TODO: Test `finish` functions
        fun testFinishCurrentIdea() = finishCurrentIdea()
        fun testFinishCurrentOutcome() = finishCurrentOutcome()
        fun testFinishCurrentPerception() = finishCurrentPerception()
        fun testFinishCurrentPlan() = finishCurrentPlan()
        fun testFinishCurrentTask() = finishCurrentTask()

        fun testResetCurrentMemory() = resetCurrentMemory()
        fun testResetPastMemory() = resetPastMemory()
        fun testResetAllMemory() = resetAllMemory()

        override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge {
            return Knowledge.FromOutcome(
                outcomeId = outcome.id,
                approach = "Test approach",
                learnings = "Test learnings",
                timestamp = Clock.System.now(),
            )
        }

        companion object {
            private val stubIdea = Idea(name = "Test Idea")
            private val stubPlan = Plan.ForIdea(
                idea = stubIdea,
                estimatedComplexity = 1,
                tasks = emptyList(),
            )
        }
    }

    private lateinit var agent: TestAutonomousAgent

    @BeforeTest
    fun setup() {
        agent = TestAutonomousAgent()
    }

    @AfterTest
    fun tearDown() {
        agent.shutdownAgent()
    }

    // ==================== MEMORY FUNCTION TESTS ====================

    @Test
    fun `getCurrentState returns initial empty state`() {
        val state = agent.getCurrentState()

        val currentMemory = state.getCurrentMemory()
        assertEquals(Idea.blank, currentMemory.idea)
        assertEquals(Outcome.blank, currentMemory.outcome)
        assertEquals(Perception.blank, currentMemory.perception)
        assertEquals(Plan.blank, currentMemory.plan)
        assertEquals(Task.blank, currentMemory.task)

        val pastMemory = state.getPastMemory()
        assertEquals(emptyList(), pastMemory.ideas)
        assertEquals(emptyList(), pastMemory.outcomes)
        assertEquals(emptyList(), pastMemory.perceptions)
        assertEquals(emptyList(), pastMemory.plans)
        assertEquals(emptyList(), pastMemory.tasks)
    }

    @Test
    fun `ideas can be remembered as knowledge`() {
        agent.testRememberIdea(stubIdea)
        assertEquals(stubIdea, agent.getCurrentState().getCurrentMemory().idea)

        val idea1 = Idea(name = "Idea 2")
        agent.testRememberIdea(idea1)

        val outputState = agent.getCurrentState()
        assertEquals(idea1, outputState.getCurrentMemory().idea)
        assertEquals(listOf(stubIdea.id), outputState.getPastMemory().ideas)
    }

    @Test
    fun `outcomes can be remembered as knowledge`() {
        agent.testRememberOutcome(stubOutcome)
        assertEquals(stubOutcome, agent.getCurrentState().getCurrentMemory().outcome)

        val outcome1 = stubOutcome.copy(executorId = "Executor 2")
        agent.testRememberOutcome(outcome1)

        val outputState = agent.getCurrentState()
        assertEquals(outcome1, outputState.getCurrentMemory().outcome)
        assertEquals(listOf(stubOutcome.id), outputState.getPastMemory().outcomes)
    }

    @Test
    fun `perceptions can be remembered as knowledge`() {
        agent.testRememberPerception(stubPerception)
        assertEquals(stubPerception, agent.getCurrentState().getCurrentMemory().perception)

        val perception1 = stubPerception.copy(ideas = listOf(Idea(name = "Perception 2")))
        agent.testRememberPerception(perception1)

        val outputState = agent.getCurrentState()
        assertEquals(perception1, outputState.getCurrentMemory().perception)
        assertEquals(listOf(stubPerception.id), outputState.getPastMemory().perceptions)
    }

    @Test
    fun `plans can be remembered as knowledge`() {
        agent.testRememberPlan(stubPlan)
        assertEquals(stubPlan, agent.getCurrentState().getCurrentMemory().plan)

        val plan1 = stubPlan.copy(estimatedComplexity = 2)
        agent.testRememberPlan(plan1)

        val outputState = agent.getCurrentState()
        assertEquals(plan1, outputState.getCurrentMemory().plan)
        assertEquals(listOf(stubPlan.id), outputState.getPastMemory().plans)
    }

    @Test
    fun `tasks can be remembered as knowledge`() {
        agent.testRememberTask(stubTask)
        assertEquals(stubTask, agent.getCurrentState().getCurrentMemory().task)

        val task1 = stubTask.copy(id = "Task 2")
        agent.testRememberTask(task1)

        val outputState = agent.getCurrentState()
        assertEquals(task1, outputState.getCurrentMemory().task)
        assertEquals(listOf(stubTask.id), outputState.getPastMemory().tasks)
    }

    @Test
    fun `resetWorkingMemory saves current data to history and then clears current memory`() {
        agent.testRememberIdea(stubIdea)
        agent.testRememberOutcome(stubOutcome)
        agent.testRememberPerception(stubPerception)
        agent.testRememberPlan(stubPlan)
        agent.testRememberTask(stubTask)

        val inputState = agent.getCurrentState()
        val inputCurrentMemory = inputState.getCurrentMemory()

        assertEquals(stubIdea, inputCurrentMemory.idea)
        assertEquals(stubOutcome, inputCurrentMemory.outcome)
        assertEquals(stubPerception, inputCurrentMemory.perception)
        assertEquals(stubPlan, inputCurrentMemory.plan)
        assertEquals(stubTask, inputCurrentMemory.task)

        agent.testResetCurrentMemory()

        val outputState = agent.getCurrentState()
        val outputCurrentMemory = outputState.getCurrentMemory()
        val outputPastMemory = outputState.getPastMemory()

        assertEquals(Idea.blank, outputCurrentMemory.idea)
        assertEquals(Outcome.blank, outputCurrentMemory.outcome)
        assertEquals(Perception.blank, outputCurrentMemory.perception)
        assertEquals(Plan.blank, outputCurrentMemory.plan)
        assertEquals(Task.blank, outputCurrentMemory.task)

        assertEquals(listOf(stubIdea.id), outputPastMemory.ideas)
        assertEquals(listOf(stubOutcome.id), outputPastMemory.outcomes)
        assertEquals(listOf(stubPerception.id), outputPastMemory.perceptions)
        assertEquals(listOf(stubPlan.id), outputPastMemory.plans)
        assertEquals(listOf(stubTask.id), outputPastMemory.tasks)
    }

    @Test
    fun `resetPastMemory clears all history`() {
        agent.testRememberIdea(stubIdea)
        agent.testRememberOutcome(stubOutcome)
        agent.testRememberPerception(stubPerception)
        agent.testRememberPlan(stubPlan)
        agent.testRememberTask(stubTask)

        agent.testResetCurrentMemory()

        val inputState = agent.getCurrentState()
        val inputPastMemory = inputState.getPastMemory()

        assertEquals(listOf(stubIdea.id), inputPastMemory.ideas)
        assertEquals(listOf(stubOutcome.id), inputPastMemory.outcomes)
        assertEquals(listOf(stubPerception.id), inputPastMemory.perceptions)
        assertEquals(listOf(stubPlan.id), inputPastMemory.plans)
        assertEquals(listOf(stubTask.id), inputPastMemory.tasks)

        agent.testResetPastMemory()

        val outputState = agent.getCurrentState()
        val outputPastMemory = outputState.getPastMemory()

        assertEquals(emptyList(), outputPastMemory.ideas)
        assertEquals(emptyList(), outputPastMemory.outcomes)
        assertEquals(emptyList(), outputPastMemory.perceptions)
        assertEquals(emptyList(), outputPastMemory.plans)
        assertEquals(emptyList(), outputPastMemory.tasks)
    }

    @Test
    fun `resetAllMemory clears everything`() {
        agent.testRememberIdea(stubIdea)
        agent.testRememberOutcome(stubOutcome)
        agent.testRememberPerception(stubPerception)
        agent.testRememberPlan(stubPlan)
        agent.testRememberTask(stubTask)

        agent.testResetCurrentMemory()

        agent.testRememberIdea(stubIdea)
        agent.testRememberOutcome(stubOutcome)
        agent.testRememberPerception(stubPerception)
        agent.testRememberPlan(stubPlan)
        agent.testRememberTask(stubTask)

        agent.testResetAllMemory()

        val outputState = agent.getCurrentState()
        val outputCurrentMemory = outputState.getCurrentMemory()
        val outputPastMemory = outputState.getPastMemory()

        assertEquals(Idea.blank, outputCurrentMemory.idea)
        assertEquals(Outcome.blank, outputCurrentMemory.outcome)
        assertEquals(Perception.blank, outputCurrentMemory.perception)
        assertEquals(Plan.blank, outputCurrentMemory.plan)
        assertEquals(Task.blank, outputCurrentMemory.task)

        assertEquals(emptyList(), outputPastMemory.ideas)
        assertEquals(emptyList(), outputPastMemory.outcomes)
        assertEquals(emptyList(), outputPastMemory.perceptions)
        assertEquals(emptyList(), outputPastMemory.plans)
        assertEquals(emptyList(), outputPastMemory.tasks)
    }

    // ==================== LIFECYCLE TESTS ====================

    @Test
    fun `initialize starts agent without error`() {
        runBlocking {
            agent.initialize(testScope)

            // Wait for loop to start executing
            delay(50)

            // Agent should be running - verify by checking it can be shut down
            agent.shutdownAgent()
        }
    }

    @Test
    fun `pauseAgent stops the runtime loop and resets working memory`() {
        runBlocking {
            // Add some state before initializing
            agent.testRememberIdea(stubIdea)

            val stateBeforePause = agent.getCurrentState()
            val currentMemoryBeforePause = stateBeforePause.getCurrentMemory()
            assertEquals("Perceived", currentMemoryBeforePause.idea.name)

            agent.pauseAgent()

            // Working memory should be reset (current idea moved to history)
            val stateAfterPause = agent.getCurrentState()
            val currentMemoryAfterPause = stateAfterPause.getCurrentMemory()
            val pastMemoryAfterPause = stateAfterPause.getPastMemory()

            assertEquals(Idea.blank, currentMemoryAfterPause.idea)
            assertEquals(listOf(stubIdea.id), pastMemoryAfterPause.ideas)
        }
    }

    @Test
    fun `resumeAgent can be called after pause`() {
        runBlocking {
            agent.initialize(testScope)
            delay(20)

            agent.pauseAgent()

            // Resume should not throw
            agent.resumeAgent()
            delay(20)

            agent.shutdownAgent()
        }
    }

    @Test
    fun `shutdownAgent stops loop and clears all memory`() {
        runBlocking {
            agent.testRememberIdea(stubIdea)

            val idea1 = stubIdea.copy(name = "Idea 1")
            agent.testRememberIdea(idea1)

            agent.initialize(testScope)
            delay(20)

            agent.shutdownAgent()

            val state = agent.getCurrentState()
            val currentMemory = state.getCurrentMemory()
            val pastMemory = state.getPastMemory()

            assertEquals(Idea.blank, currentMemory.idea)
            assertEquals(emptyList(), pastMemory.ideas)
        }
    }

    // ==================== REQUIRED TOOLS TESTS ====================

    @Test
    fun `requiredTools defaults to empty set`() {
        assertTrue(agent.requiredTools.isEmpty())
    }

    @Test
    fun `agent id is correctly set`() {
        assertEquals("TestAgent", agent.id)
    }
}
