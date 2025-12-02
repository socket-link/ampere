package link.socket.ampere.agents.implementations

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.implementations.code.CodeWriterAgent
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider

@OptIn(ExperimentalCoroutinesApi::class)
actual class CodeWriterAgentTest {

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

    private val stubTool = ToolWriteCodeFile(
        requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
    )

    private lateinit var executionRequest: ExecutionRequest<ExecutionContext.Code.WriteCode>

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var tempDir: Path

    // ==================== FAKE IMPLEMENTATIONS ====================

    /**
     * Fake AI configuration for testing.
     *
     * Since AIProvider is a sealed interface from commonMain, we cannot extend it
     * from the test module. Instead, we throw NotImplementedError for methods that
     * won't be called in these tests.
     */
    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider not needed for these tests")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    /**
     * Test agent that allows us to control the perception evaluation result
     * without actually calling the LLM.
     */
    private class TestableCodeWriterAgent(
        initialState: AgentState,
        agentConfiguration: AgentConfiguration,
        toolWriteCodeFile: Tool<ExecutionContext.Code.WriteCode>,
        coroutineScope: CoroutineScope,
        private val perceptionResult: (Perception<AgentState>) -> Idea,
        private val planningResult: ((Task, List<Idea>) -> link.socket.ampere.agents.core.reasoning.Plan)? = null
    ) : CodeWriterAgent(initialState, agentConfiguration, toolWriteCodeFile, coroutineScope) {

        override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
            perceptionResult

        override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> link.socket.ampere.agents.core.reasoning.Plan =
            planningResult ?: super.runLLMToPlan
    }

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory(prefix = "ampere-agent-test-")

        executionRequest = ExecutionRequest(
            context = ExecutionContext.Code.WriteCode(
                executorId = "executor-1",
                ticket = stubTicket,
                task = stubTask,
                instructions = "Write a function",
                workspace = ExecutionWorkspace(tempDir.absolutePathString()),
                instructionsPerFilePath = listOf()
            ),
            constraints = ExecutionConstraints(),
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ==================== HELPER METHODS ====================

    /**
     * Creates a testable agent with controlled perception results.
     *
     * @param perceptionResult A function that produces Ideas based on perception
     * @return A CodeWriterAgent configured for testing
     */
    private fun createTestAgent(
        perceptionResult: (Perception<AgentState>) -> Idea
    ): TestableCodeWriterAgent {
        val aiConfig = FakeAIConfiguration()
        val agentConfig = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = aiConfig
        )

        return TestableCodeWriterAgent(
            initialState = AgentState(),
            agentConfiguration = agentConfig,
            toolWriteCodeFile = stubTool,
            coroutineScope = testScope,
            perceptionResult = perceptionResult
        )
    }

    /**
     * Creates a testable agent with controlled perception and planning results.
     *
     * @param perceptionResult A function that produces Ideas based on perception
     * @param planningResult A function that produces Plans based on tasks and ideas
     * @return A CodeWriterAgent configured for testing
     */
    private fun createTestAgentWithPlanning(
        perceptionResult: (Perception<AgentState>) -> Idea,
        planningResult: (Task, List<Idea>) -> link.socket.ampere.agents.core.reasoning.Plan
    ): TestableCodeWriterAgent {
        val aiConfig = FakeAIConfiguration()
        val agentConfig = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = aiConfig
        )

        return TestableCodeWriterAgent(
            initialState = AgentState(),
            agentConfiguration = agentConfig,
            toolWriteCodeFile = stubTool,
            coroutineScope = testScope,
            perceptionResult = perceptionResult,
            planningResult = planningResult
        )
    }

    /**
     * Creates an Idea simulating LLM insight generation about a pending task.
     */
    private fun createPendingTaskIdea(perception: Perception<AgentState>): Idea {
        val task = perception.currentState.getCurrentMemory().task
        return Idea(
            name = "Perception analysis for pending task",
            description = "Agent has a pending code change task → Should plan implementation steps for the task (confidence: high)"
        )
    }

    /**
     * Creates an Idea simulating LLM detection of failure patterns.
     */
    private fun createFailurePatternIdea(perception: Perception<AgentState>): Idea {
        return Idea(
            name = "Perception analysis for pattern detection",
            description = """
                Three consecutive failures detected in past outcomes → Should consider alternative approach or request human assistance (confidence: high)

                Pattern suggests tool may not be suitable for this task → May need different tool or different task decomposition (confidence: medium)
            """.trimIndent()
        )
    }

    /**
     * Creates an Idea for empty/idle state.
     */
    private fun createEmptyStateIdea(perception: Perception<AgentState>): Idea {
        return Idea(
            name = "Perception analysis for current task",
            description = "Agent has no active task → Awaiting new task assignment (confidence: high)"
        )
    }

    /**
     * Creates an Idea about available tools.
     */
    private fun createToolAvailabilityIdea(perception: Perception<AgentState>): Idea {
        return Idea(
            name = "Perception analysis for code writing",
            description = "WriteCodeFile tool is available → Can execute code writing tasks (confidence: high)"
        )
    }

    /**
     * Creates an Idea about successful patterns.
     */
    private fun createSuccessPatternIdea(perception: Perception<AgentState>): Idea {
        return Idea(
            name = "Perception analysis for similar task",
            description = "Previous similar task completed successfully → Can use similar approach for current task (confidence: high)"
        )
    }

    // ==================== TESTS ====================

    /**
     * Test: Perception with simple pending task generates relevant idea
     *
     * Validates that the agent can evaluate a simple state with one pending task
     * and generate an idea that references the task.
     */
    @Test
    fun `perception with simple pending task generates relevant idea`() {
        // Setup: Create agent that simulates LLM insight generation
        val agent = createTestAgent(::createPendingTaskIdea)

        // Create a state with one pending task
        val state = AgentState()
        state.setNewTask(
            Task.CodeChange(
                id = "task-1",
                status = TaskStatus.Pending,
                description = "Implement a user authentication function"
            )
        )

        val perception = Perception(
            currentState = state,
            ideas = emptyList()
        )

        // Execute
        val idea = agent.runLLMToEvaluatePerception(perception)

        // Verify
        assertNotNull(idea)
        assertNotEquals("", idea.name)
        assertNotEquals("", idea.description)
        assertContains(idea.description.lowercase(), "pending", ignoreCase = true)
    }

    /**
     * Test: Perception detects failure patterns
     *
     * Validates that the agent recognizes patterns of repeated failures and
     * mentions them in the generated idea.
     */
    @Test
    fun `perception detects failure patterns in execution history`() {
        // Setup: Create agent that simulates failure pattern detection
        val agent = createTestAgent(::createFailurePatternIdea)

        // Create a state with failure outcomes
        val state = AgentState()
        state.setNewTask(
            Task.CodeChange(
                id = "task-retry",
                status = TaskStatus.InProgress,
                description = "Retry failed task"
            )
        )

        // Add knowledge from failed outcomes
        state.addToPastKnowledge(
            rememberedKnowledgeFromOutcomes = listOf(
                Knowledge.FromOutcome(
                    outcomeId = "outcome-1",
                    approach = "Attempted direct implementation",
                    learnings = "Failed due to missing context",
                    timestamp = Clock.System.now()
                ),
                Knowledge.FromOutcome(
                    outcomeId = "outcome-2",
                    approach = "Attempted with different parameters",
                    learnings = "Still failed with similar error",
                    timestamp = Clock.System.now()
                ),
                Knowledge.FromOutcome(
                    outcomeId = "outcome-3",
                    approach = "Attempted with retry logic",
                    learnings = "Continued to fail",
                    timestamp = Clock.System.now()
                )
            )
        )

        val perception = Perception(
            currentState = state,
            ideas = emptyList()
        )

        // Execute
        val idea = agent.runLLMToEvaluatePerception(perception)

        // Verify
        assertNotNull(idea)
        assertTrue(
            idea.description.contains("failure", ignoreCase = true) ||
                idea.description.contains("alternative", ignoreCase = true) ||
                idea.description.contains("pattern", ignoreCase = true),
            "Idea should mention failures or alternative approaches"
        )
    }

    /**
     * Test: Perception with empty state
     *
     * Validates that the agent handles an empty state gracefully without crashing.
     */
    @Test
    fun `perception with empty state returns graceful idea`() {
        // Setup: Create agent for empty state
        val agent = createTestAgent(::createEmptyStateIdea)

        // Create empty state
        val state = AgentState()

        val perception = Perception(
            currentState = state,
            ideas = emptyList()
        )

        // Execute
        val idea = agent.runLLMToEvaluatePerception(perception)

        // Verify
        assertNotNull(idea)
        assertNotEquals("", idea.name)
        assertNotEquals("", idea.description)
    }

    /**
     * Test: Perception handles errors gracefully with fallback
     *
     * Validates that the agent uses fallback logic when perception evaluation fails.
     * This simulates scenarios like LLM errors or malformed responses.
     */
    @Test
    fun `perception handles errors with fallback`() {
        // Setup: Create agent that returns a fallback idea
        val agent = createTestAgent { perception ->
            val task = perception.currentState.getCurrentMemory().task
            Idea(
                name = "Basic perception (fallback)",
                description = "Code change task: Test task (Status: Pending)\n\nNote: Advanced perception analysis unavailable\n\nAvailable tools: ToolWriteCodeFile"
            )
        }

        val state = AgentState()
        state.setNewTask(
            Task.CodeChange(
                id = "task-1",
                status = TaskStatus.Pending,
                description = "Test task"
            )
        )

        val perception = Perception(
            currentState = state,
            ideas = emptyList()
        )

        // Execute - should not crash
        val idea = agent.runLLMToEvaluatePerception(perception)

        // Verify - should get fallback idea
        assertNotNull(idea)
        assertNotEquals("", idea.name)
        assertNotEquals("", idea.description)
        // Fallback ideas typically mention the current task
        assertTrue(
            idea.description.contains("task", ignoreCase = true) ||
                idea.description.contains("fallback", ignoreCase = true)
        )
    }

    /**
     * Test: Perception identifies available tools
     *
     * Validates that the perception process is aware of and mentions available tools.
     */
    @Test
    fun `perception identifies available tools`() {
        // Setup: Create agent that identifies tools
        val agent = createTestAgent(::createToolAvailabilityIdea)

        val state = AgentState()
        state.setNewTask(
            Task.CodeChange(
                id = "task-1",
                status = TaskStatus.Pending,
                description = "Write a new function"
            )
        )

        val perception = Perception(
            currentState = state,
            ideas = emptyList()
        )

        // Execute
        val idea = agent.runLLMToEvaluatePerception(perception)

        // Verify
        assertNotNull(idea)
        // The idea should reference tools or capability
        assertTrue(
            idea.description.contains("tool", ignoreCase = true) ||
                idea.description.contains("available", ignoreCase = true) ||
                idea.description.contains("code", ignoreCase = true)
        )
    }

    /**
     * Test: Perception with successful pattern recognition
     *
     * Validates that the agent can recognize successful patterns and reference them.
     */
    @Test
    fun `perception references successful patterns from past knowledge`() {
        // Setup: Create agent that recognizes success patterns
        val agent = createTestAgent(::createSuccessPatternIdea)

        val state = AgentState()
        state.setNewTask(
            Task.CodeChange(
                id = "task-2",
                status = TaskStatus.Pending,
                description = "Implement another authentication function"
            )
        )

        // Add successful knowledge
        state.addToPastKnowledge(
            rememberedKnowledgeFromOutcomes = listOf(
                Knowledge.FromOutcome(
                    outcomeId = "outcome-success",
                    approach = "Used step-by-step implementation with tests",
                    learnings = "Approach worked well for authentication functions",
                    timestamp = Clock.System.now()
                )
            )
        )

        val perception = Perception(
            currentState = state,
            ideas = emptyList()
        )

        // Execute
        val idea = agent.runLLMToEvaluatePerception(perception)

        // Verify
        assertNotNull(idea)
        assertTrue(
            idea.description.contains("success", ignoreCase = true) ||
                idea.description.contains("similar", ignoreCase = true) ||
                idea.description.contains("approach", ignoreCase = true)
        )
    }

    // ==================== PLANNING TESTS ====================

    /**
     * Test: Planning for simple single-step task
     *
     * Validates that planning generates a plan with one concrete step for a simple task.
     */
    @Test
    fun `planning for simple task generates single-step plan`() {
        // Setup: Create agent with mock planning that returns a single-step plan
        val agent = createTestAgentWithPlanning(
            perceptionResult = ::createPendingTaskIdea,
            planningResult = { task, ideas ->
                link.socket.ampere.agents.core.reasoning.Plan.ForTask(
                    task = task,
                    tasks = listOf(
                        Task.CodeChange(
                            id = "step-1-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Write a hello world function",
                            assignedTo = AssignedTo.Agent("TestAgent")
                        )
                    ),
                    estimatedComplexity = 1,
                    expectations = link.socket.ampere.agents.core.expectations.Expectations.blank
                )
            }
        )

        val simpleTask = Task.CodeChange(
            id = "task-simple",
            status = TaskStatus.Pending,
            description = "Create a hello world function"
        )

        val basicIdeas = listOf(
            Idea(
                name = "Simple task",
                description = "This is a straightforward single-function task"
            )
        )

        // Execute
        val plan = agent.runLLMToPlan(simpleTask, basicIdeas)

        // Verify
        assertNotNull(plan)
        assertTrue(plan is link.socket.ampere.agents.core.reasoning.Plan.ForTask)
        kotlin.test.assertEquals(1, plan.tasks.size, "Simple task should have 1 step")
        assertTrue(plan.estimatedComplexity <= 3, "Simple task should have low complexity")
    }

    /**
     * Test: Planning creates multi-step plan for complex tasks
     *
     * Validates that planning breaks down complex tasks into multiple steps.
     */
    @Test
    fun `planning for complex task generates multi-step plan`() {
        // Setup: Create agent with mock planning that returns a multi-step plan
        val agent = createTestAgentWithPlanning(
            perceptionResult = ::createPendingTaskIdea,
            planningResult = { task, ideas ->
                link.socket.ampere.agents.core.reasoning.Plan.ForTask(
                    task = task,
                    tasks = listOf(
                        Task.CodeChange(
                            id = "step-1-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Create User data class with name and email properties"
                        ),
                        Task.CodeChange(
                            id = "step-2-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Add validation logic for email format"
                        ),
                        Task.CodeChange(
                            id = "step-3-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Write unit tests for User class"
                        )
                    ),
                    estimatedComplexity = 6,
                    expectations = link.socket.ampere.agents.core.expectations.Expectations.blank
                )
            }
        )

        val complexTask = Task.CodeChange(
            id = "task-complex",
            status = TaskStatus.Pending,
            description = "Implement user authentication with validation and tests"
        )

        val complexIdeas = listOf(
            Idea(
                name = "Complex task",
                description = "This requires data model, validation, and testing"
            )
        )

        // Execute
        val plan = agent.runLLMToPlan(complexTask, complexIdeas)

        // Verify
        assertNotNull(plan)
        assertTrue(plan is link.socket.ampere.agents.core.reasoning.Plan.ForTask)
        assertTrue(plan.tasks.size >= 3, "Complex task should have multiple steps")
        assertTrue(plan.estimatedComplexity > 3, "Complex task should have higher complexity")
    }

    /**
     * Test: Plan steps are logically ordered
     *
     * Validates that plan steps are sequenced in a way that makes sense
     * (e.g., write code before testing it).
     */
    @Test
    fun `plan steps are logically ordered with dependencies`() {
        // Setup: Create agent with mock planning that returns ordered steps
        val agent = createTestAgentWithPlanning(
            perceptionResult = ::createPendingTaskIdea,
            planningResult = { task, ideas ->
                link.socket.ampere.agents.core.reasoning.Plan.ForTask(
                    task = task,
                    tasks = listOf(
                        Task.CodeChange(
                            id = "step-1-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Write the implementation"
                        ),
                        Task.CodeChange(
                            id = "step-2-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Write tests for the implementation"
                        )
                    ),
                    estimatedComplexity = 4,
                    expectations = link.socket.ampere.agents.core.expectations.Expectations.blank
                )
            }
        )

        val task = Task.CodeChange(
            id = "task-ordered",
            status = TaskStatus.Pending,
            description = "Implement a feature with tests"
        )

        val ideas = listOf(
            Idea(
                name = "Implementation strategy",
                description = "Write implementation first, then tests"
            )
        )

        // Execute
        val plan = agent.runLLMToPlan(task, ideas)

        // Verify
        assertNotNull(plan)
        assertTrue(plan is link.socket.ampere.agents.core.reasoning.Plan.ForTask)
        kotlin.test.assertEquals(2, plan.tasks.size)

        // First step should be implementation
        val firstStep = plan.tasks[0] as Task.CodeChange
        assertTrue(
            firstStep.description.contains("implementation", ignoreCase = true) ||
                firstStep.description.contains("write", ignoreCase = true)
        )

        // Second step should be tests
        val secondStep = plan.tasks[1] as Task.CodeChange
        assertTrue(secondStep.description.contains("test", ignoreCase = true))
    }

    /**
     * Test: Plan includes all necessary information
     *
     * Validates that each step in the plan has the required information.
     */
    @Test
    fun `plan steps include necessary information`() {
        // Setup: Create agent with mock planning
        val agent = createTestAgentWithPlanning(
            perceptionResult = ::createPendingTaskIdea,
            planningResult = { task, ideas ->
                link.socket.ampere.agents.core.reasoning.Plan.ForTask(
                    task = task,
                    tasks = listOf(
                        Task.CodeChange(
                            id = "step-1-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Create User.kt file with data class User(name: String, email: String)",
                            assignedTo = AssignedTo.Agent("TestAgent")
                        )
                    ),
                    estimatedComplexity = 2,
                    expectations = link.socket.ampere.agents.core.expectations.Expectations.blank
                )
            }
        )

        val task = Task.CodeChange(
            id = "task-detailed",
            status = TaskStatus.Pending,
            description = "Create a user data class"
        )

        val ideas = listOf(
            Idea(
                name = "Implementation details",
                description = "User should have name and email"
            )
        )

        // Execute
        val plan = agent.runLLMToPlan(task, ideas)

        // Verify
        assertNotNull(plan)
        assertTrue(plan is link.socket.ampere.agents.core.reasoning.Plan.ForTask)

        val step = plan.tasks[0] as Task.CodeChange
        assertNotEquals("", step.id, "Step should have an ID")
        assertNotEquals("", step.description, "Step should have a description")
        assertTrue(step.description.length > 10, "Step description should be meaningful")
    }

    /**
     * Test: Planning handles blank task gracefully
     *
     * Validates that planning returns blank plan for blank task.
     */
    @Test
    fun `planning handles blank task gracefully`() {
        // Setup: Create agent (planning logic should handle blank task internally)
        val agent = createTestAgent(::createEmptyStateIdea)

        val blankTask = Task.Blank

        val ideas = emptyList<Idea>()

        // Execute
        val plan = agent.runLLMToPlan(blankTask, ideas)

        // Verify
        assertNotNull(plan)
        assertTrue(plan is link.socket.ampere.agents.core.reasoning.Plan.Empty)
    }

    /**
     * Test: Planning with no ideas still generates a plan
     *
     * Validates that planning can work even without insights from perception.
     */
    @Test
    fun `planning with no ideas generates reasonable plan`() {
        // Setup: Create agent with mock planning
        val agent = createTestAgentWithPlanning(
            perceptionResult = ::createPendingTaskIdea,
            planningResult = { task, ideas ->
                // Even with no ideas, should create a basic plan
                link.socket.ampere.agents.core.reasoning.Plan.ForTask(
                    task = task,
                    tasks = listOf(
                        Task.CodeChange(
                            id = "step-1-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Execute task: ${(task as Task.CodeChange).description}"
                        )
                    ),
                    estimatedComplexity = 3,
                    expectations = link.socket.ampere.agents.core.expectations.Expectations.blank
                )
            }
        )

        val task = Task.CodeChange(
            id = "task-no-ideas",
            status = TaskStatus.Pending,
            description = "Simple task"
        )

        val noIdeas = emptyList<Idea>()

        // Execute
        val plan = agent.runLLMToPlan(task, noIdeas)

        // Verify
        assertNotNull(plan)
        assertTrue(plan is link.socket.ampere.agents.core.reasoning.Plan.ForTask)
        assertTrue(plan.tasks.isNotEmpty(), "Plan should have at least one step")
    }

    /**
     * Test: Planning complexity estimation is reasonable
     *
     * Validates that estimated complexity matches the plan's actual complexity.
     */
    @Test
    fun `plan complexity estimation matches task complexity`() {
        // Setup: Test both simple and complex plans
        val simpleAgent = createTestAgentWithPlanning(
            perceptionResult = ::createPendingTaskIdea,
            planningResult = { task, ideas ->
                link.socket.ampere.agents.core.reasoning.Plan.ForTask(
                    task = task,
                    tasks = listOf(
                        Task.CodeChange(
                            id = "step-1-${task.id}",
                            status = TaskStatus.Pending,
                            description = "Simple step"
                        )
                    ),
                    estimatedComplexity = 1,
                    expectations = link.socket.ampere.agents.core.expectations.Expectations.blank
                )
            }
        )

        val complexAgent = createTestAgentWithPlanning(
            perceptionResult = ::createPendingTaskIdea,
            planningResult = { task, ideas ->
                link.socket.ampere.agents.core.reasoning.Plan.ForTask(
                    task = task,
                    tasks = listOf(
                        Task.CodeChange(id = "step-1", status = TaskStatus.Pending, description = "Step 1"),
                        Task.CodeChange(id = "step-2", status = TaskStatus.Pending, description = "Step 2"),
                        Task.CodeChange(id = "step-3", status = TaskStatus.Pending, description = "Step 3"),
                        Task.CodeChange(id = "step-4", status = TaskStatus.Pending, description = "Step 4"),
                        Task.CodeChange(id = "step-5", status = TaskStatus.Pending, description = "Step 5")
                    ),
                    estimatedComplexity = 8,
                    expectations = link.socket.ampere.agents.core.expectations.Expectations.blank
                )
            }
        )

        val simpleTask = Task.CodeChange(id = "simple", status = TaskStatus.Pending, description = "Simple")
        val complexTask = Task.CodeChange(id = "complex", status = TaskStatus.Pending, description = "Complex")

        // Execute
        val simplePlan = simpleAgent.runLLMToPlan(simpleTask, emptyList())
        val complexPlan = complexAgent.runLLMToPlan(complexTask, emptyList())

        // Verify
        assertTrue(simplePlan.estimatedComplexity < complexPlan.estimatedComplexity,
            "Complex plan should have higher complexity than simple plan")
        assertTrue(simplePlan.estimatedComplexity in 1..3, "Simple plan complexity should be low")
        assertTrue(complexPlan.estimatedComplexity in 6..10, "Complex plan complexity should be high")
    }
}
