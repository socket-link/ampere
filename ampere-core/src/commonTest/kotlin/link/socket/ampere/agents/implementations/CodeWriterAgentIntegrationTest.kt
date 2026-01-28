package link.socket.ampere.agents.implementations

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.EvaluationResult
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.executor.InstrumentedExecutor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.stubAgentConfiguration

/**
 * Comprehensive integration tests for the CodeWriterAgent's complete cognitive loop.
 *
 * These tests validate that the agent can autonomously:
 * - Perceive its current state and tasks
 * - Plan concrete steps to accomplish goals
 * - Execute plans using tools through executors
 * - Evaluate outcomes and generate learnings
 * - Apply learnings to future tasks
 *
 * This suite verifies the "metabolic loop" of autonomous agency—the continuous cycle
 * of perception → planning → execution → evaluation that enables genuine autonomy.
 */
class CodeWriterAgentIntegrationTest {

    /**
     * Test 1: Complete cognitive loop for simple task
     *
     * Validates that the agent can:
     * - Receive a simple task
     * - Perceive the current state
     * - Generate a plan
     * - Execute the plan
     * - Evaluate the outcome
     * - Complete successfully without human intervention
     */
    @Ignore
    @Test
    fun `test complete cognitive loop for simple task`() = runBlocking {
        // Create a mock tool that always succeeds
        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = true)
        val executor = FunctionExecutor.create()

        // Create mock reasoning that returns predetermined responses
        val mockReasoning = createMockReasoning()

        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = executor,
            reasoningOverride = mockReasoning,
        )

        // Create a simple code change task
        val task = Task.CodeChange(
            id = "simple-task-1",
            status = TaskStatus.Pending,
            description = "Create a simple data class User with fields name and email",
        )

        // Run through the complete cognitive cycle
        // 1. Perceive current state
        val perception = agent.perceiveState(agent.getCurrentState())
        assertNotNull(perception)
        assertTrue(perception.ideas.first().name.isNotEmpty(), "Perception should generate insights")

        // 2. Generate a plan
        val plan = agent.determinePlanForTask(task, perception.ideas.first(), relevantKnowledge = emptyList())
        assertNotNull(plan)
        assertIs<Plan.ForTask>(plan)
        assertTrue(plan.tasks.isNotEmpty(), "Plan should contain steps")

        // 3. Execute the plan
        val outcome = agent.executePlan(plan)
        assertNotNull(outcome)

        // For a mock tool that always succeeds, we expect success
        assertTrue(outcome is Outcome.Success, "Execution should succeed with mock tool")

        // 4. Evaluate outcomes to generate learnings
        val learningIdea = agent.evaluateNextIdeaFromOutcomes(outcome)
        assertNotNull(learningIdea)
        assertTrue(learningIdea.name.isNotEmpty(), "Should generate learning insights")

        // Verify the complete cognitive cycle executed successfully
        // (Note: Knowledge extraction is tested separately in other tests)
    }

    /**
     * Test 2: Cognitive state transitions
     *
     * Validates that:
     * - Agent state transitions correctly through the cognitive cycle
     * - Current memory is properly updated at each stage
     * - Past memory accumulates correctly
     *
     * Note: This integration test requires a configured LLM (Anthropic API key in local.properties).
     * To run this test, configure your API credentials and remove the @Ignore annotation.
     */
    @Ignore
    @Test
    fun `test cognitive state transitions`() = runBlocking {
        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = true)
        val mockReasoning = createMockReasoning()

        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = FunctionExecutor.create(),
            reasoningOverride = mockReasoning,
        )

        val task = Task.CodeChange(
            id = "state-transition-task",
            status = TaskStatus.Pending,
            description = "Test state transitions",
        )

        // Initial state - should be blank
        val initialState = agent.getCurrentState()
        val initialMemory = initialState.getCurrentMemory()
        assertTrue(initialMemory.task is Task.Blank, "Initial task should be blank")
        assertTrue(initialMemory.plan is Plan.Blank, "Initial plan should be blank")
        assertTrue(initialMemory.outcome is Outcome.Blank, "Initial outcome should be blank")

        // Perceive state
        val idea = agent.perceiveState(agent.getCurrentState())
        val afterPerception = agent.getCurrentState()
        assertEquals(idea.id, afterPerception.getCurrentMemory().idea.id, "Idea should be stored in current memory")

        // Generate plan
        val plan = agent.determinePlanForTask(task, idea.ideas.first(), relevantKnowledge = emptyList())
        val afterPlanning = agent.getCurrentState()
        assertEquals(plan.id, afterPlanning.getCurrentMemory().plan.id, "Plan should be stored in current memory")

        // Execute task
        val outcome = agent.runTask(task)
        val afterExecution = agent.getCurrentState()
        assertEquals(
            outcome.id,
            afterExecution.getCurrentMemory().outcome.id,
            "Outcome should be stored in current memory",
        )

        // Verify past memory has accumulated
        val finalState = agent.getCurrentState()
        val pastMemory = finalState.getPastMemory()
        assertTrue(pastMemory.ideas.isNotEmpty(), "Past memory should contain ideas")
        assertTrue(pastMemory.plans.isNotEmpty(), "Past memory should contain plans")
        assertTrue(pastMemory.outcomes.isNotEmpty(), "Past memory should contain outcomes")
    }

    /**
     * Test 3: Learnings persist across tasks
     *
     * Validates that:
     * - Knowledge extracted from one task is available to future tasks
     * - The agent can recall relevant learnings when planning new tasks
     * - The learning loop actually closes (outcomes → knowledge → planning)
     */
    @Ignore
    @Test
    fun `test learnings persist across tasks`() = runBlocking {
        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = true)
        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = FunctionExecutor.create(),
        )

        // First task - execute and generate learnings
        val firstTask = Task.CodeChange(
            id = "learning-task-1",
            status = TaskStatus.Pending,
            description = "Implement user authentication",
        )

        val firstPlan = agent.determinePlanForTask(firstTask, relevantKnowledge = emptyList())
        val firstOutcome = agent.executePlan(firstPlan)

        // Extract knowledge from first task
        val firstKnowledge = agent.extractKnowledgeFromOutcome(firstOutcome, firstTask, firstPlan)
        assertNotNull(firstKnowledge)
        assertTrue(firstKnowledge.approach.isNotEmpty())
        assertTrue(firstKnowledge.learnings.isNotEmpty())

        // Store knowledge in agent state
        agent.getCurrentState().addToPastKnowledge(
            rememberedKnowledgeFromOutcomes = listOf(firstKnowledge),
        )

        // Second task - should benefit from first task's learnings
        val secondTask = Task.CodeChange(
            id = "learning-task-2",
            status = TaskStatus.Pending,
            description = "Implement user authorization",
        )

        // Verify learnings are available in state
        val stateBeforeSecondTask = agent.getCurrentState()
        val knowledgeFromPastOutcomes = stateBeforeSecondTask.getPastMemory().knowledgeFromOutcomes
        assertTrue(
            knowledgeFromPastOutcomes.isNotEmpty(),
            "Past knowledge should be available for second task",
        )
        assertTrue(
            knowledgeFromPastOutcomes.any { it.approach == firstKnowledge.approach },
            "First task's knowledge should be retrievable",
        )

        // Execute second task
        val secondPlan = agent.determinePlanForTask(secondTask, relevantKnowledge = emptyList())
        assertNotNull(secondPlan)
        assertTrue(secondPlan.tasks.isNotEmpty())
    }

    /**
     * Test 4: Failure recovery
     *
     * Validates that:
     * - When execution fails, the agent handles it gracefully
     * - Failure outcomes are properly recorded
     * - The agent can continue operating after failures
     * - Learnings are extracted from failures
     */
    @Ignore
    @Test
    fun `test failure recovery`() = runBlocking {
        // Create a mock tool that always fails
        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = false)
        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = FunctionExecutor.create(),
        )

        val task = Task.CodeChange(
            id = "failure-task",
            status = TaskStatus.Pending,
            description = "Task that will fail",
        )

        // Execute task (should fail)
        val outcome = agent.runTask(task)

        // Verify it's a failure outcome
        assertIs<Outcome.Failure>(outcome, "Outcome should be a failure")

        // Verify agent state reflects the failure
        val state = agent.getCurrentState()
        val currentOutcome = state.getCurrentMemory().outcome
        assertIs<Outcome.Failure>(currentOutcome, "Current memory should have failure outcome")

        // Extract knowledge from failure
        val failureKnowledge = agent.extractKnowledgeFromOutcome(
            outcome,
            task,
            Plan.ForTask(task = task),
        )
        assertNotNull(failureKnowledge)
        assertTrue(
            failureKnowledge.learnings.contains("fail", ignoreCase = true),
            "Learnings should mention failure",
        )

        // Verify agent can continue - create another task
        val recoveryTask = Task.CodeChange(
            id = "recovery-task",
            status = TaskStatus.Pending,
            description = "Task after failure",
        )

        // Agent should still be able to plan and execute
        val recoveryPlan = agent.determinePlanForTask(recoveryTask, relevantKnowledge = emptyList())
        assertNotNull(recoveryPlan)
        assertTrue(recoveryPlan.tasks.isNotEmpty(), "Agent should continue functioning after failure")
    }

    /**
     * Test 5: Multiple tasks processed sequentially
     *
     * Validates that:
     * - Agent can handle multiple tasks in sequence
     * - Each task gets its own perception, plan, execution, evaluation cycle
     * - State doesn't corrupt across tasks
     * - Memory accumulates correctly
     *
     * Note: This integration test requires a configured LLM (Anthropic API key in local.properties).
     * To run this test, configure your API credentials and remove the @Ignore annotation.
     */
    @Ignore
    @Test
    fun `test multiple tasks processed sequentially`() = runBlocking {
        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = true)
        val mockReasoning = createMockReasoning()

        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = FunctionExecutor.create(),
            reasoningOverride = mockReasoning,
        )

        val tasks = listOf(
            Task.CodeChange(
                id = "seq-task-1",
                status = TaskStatus.Pending,
                description = "Create User data class",
            ),
            Task.CodeChange(
                id = "seq-task-2",
                status = TaskStatus.Pending,
                description = "Create UserRepository interface",
            ),
            Task.CodeChange(
                id = "seq-task-3",
                status = TaskStatus.Pending,
                description = "Create UserService class",
            ),
        )

        val outcomes = mutableListOf<Outcome>()

        // Process each task sequentially
        for (task in tasks) {
            // Full cognitive cycle for each task
            val idea = agent.perceiveState(agent.getCurrentState())
            val plan = agent.determinePlanForTask(task, idea.ideas.first(), relevantKnowledge = emptyList())
            val outcome = agent.executePlan(plan)
            val learningIdea = agent.evaluateNextIdeaFromOutcomes(outcome)

            outcomes.add(outcome)

            // Extract and store knowledge
            val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)
            agent.getCurrentState().addToPastKnowledge(
                rememberedKnowledgeFromOutcomes = listOf(knowledge),
            )
        }

        // Verify all tasks completed
        assertEquals(3, outcomes.size, "Should have processed all 3 tasks")

        // Verify all succeeded (with mock tool)
        assertTrue(outcomes.all { it is Outcome.Success }, "All tasks should succeed with mock tool")

        // Verify memory accumulated correctly
        val finalState = agent.getCurrentState()
        val pastMemory = finalState.getPastMemory()
        assertTrue(
            pastMemory.knowledgeFromOutcomes.size >= 3,
            "Should have accumulated knowledge from all tasks",
        )
        assertTrue(
            pastMemory.tasks.size >= 3,
            "Should have accumulated task history",
        )
        assertTrue(
            pastMemory.outcomes.size >= 3,
            "Should have accumulated outcome history",
        )
    }

    /**
     * Test 6: Executor abstraction is used correctly
     *
     * Validates that:
     * - Agent invokes tools through executors, not directly
     * - Executors are properly passed to cognitive functions
     * - The architectural pattern (agents → executors → tools) is respected
     *
     * Note: This integration test requires a configured LLM (Anthropic API key in local.properties).
     * To run this test, configure your API credentials and remove the @Ignore annotation.
     */
    @Ignore
    @Test
    fun `test executor abstraction is used correctly`() = runBlocking {
        // Create an instrumented executor to track calls
        val instrumentedExecutor = InstrumentedExecutor()

        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = true)
        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = instrumentedExecutor,
        )

        val task = Task.CodeChange(
            id = "executor-test-task",
            status = TaskStatus.Pending,
            description = "Test executor usage",
        )

        // Execute task
        agent.runTask(task)

        // Verify executor was called (not tool directly)
        assertTrue(
            instrumentedExecutor.executorWasCalled,
            "Agent should invoke tools through executor, not directly",
        )
    }

    /**
     * Test 7: Vague requirement to working code
     *
     * This is the ultimate validation of autonomous agency: given a vague,
     * natural language requirement, the agent should autonomously produce
     * a reasonable working implementation.
     *
     * This test validates:
     * - Natural language understanding
     * - Code generation from high-level intent
     * - Autonomous transformation: idea → plan → code
     */
    @Ignore
    @Test
    fun `test vague requirement to working code`() = runBlocking {
        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = true)
        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = FunctionExecutor.create(),
        )

        // Vague, natural language requirement (like a PM might give)
        val vagueRequirement = "I need a way to store user information"

        val task = Task.CodeChange(
            id = "vague-req-task",
            status = TaskStatus.Pending,
            description = vagueRequirement,
        )

        // Agent should autonomously transform this vague requirement into concrete code

        // 1. Perceive and understand the requirement
        val idea = agent.perceiveState(agent.getCurrentState())
        assertNotNull(idea)
        assertTrue(idea.ideas.first().name.isNotEmpty(), "Agent should generate insights from vague requirement")

        // 2. Plan concrete steps
        val plan = agent.determinePlanForTask(task, idea.ideas.first(), relevantKnowledge = emptyList())
        assertNotNull(plan)
        assertIs<Plan.ForTask>(plan)
        assertTrue(plan.tasks.isNotEmpty(), "Agent should break down vague requirement into steps")

        // 3. Execute the plan
        val outcome = agent.executePlan(plan)
        assertNotNull(outcome)

        // 4. Verify execution completed (success or failure, but not blank)
        assertFalse(outcome is Outcome.Blank, "Agent should produce a real outcome")

        // 5. Evaluate and learn
        val learningIdea = agent.evaluateNextIdeaFromOutcomes(outcome)
        assertNotNull(learningIdea)

        // The test passes if the agent:
        // - Understood the vague requirement (generated insights)
        // - Created a concrete plan (has actionable steps)
        // - Attempted execution (produced an outcome)
        // - Learned from the experience (generated learnings)
        // This demonstrates autonomous agency: vague → concrete → action → learning

        assertTrue(
            plan.tasks.isNotEmpty() && outcome !is Outcome.Blank,
            "Agent should autonomously transform vague requirement into concrete action",
        )
    }

    /**
     * Test 8: Runtime loop integration
     *
     * Validates that:
     * - The agent's runtime loop (from AutonomousAgent) works correctly
     * - Agent can be initialized, run, paused, and shutdown
     * - The continuous cognitive loop operates as expected
     */
    @Ignore
    @Test
    fun `test runtime loop integration`() = runBlocking {
        val mockTool = createMockWriteCodeFileTool(alwaysSucceed = true)
        val agent = CodeAgent(
            initialState = CodeState.blank,
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockTool,
            coroutineScope = this,
            executor = FunctionExecutor.create(),
        )

        // Set initial task
        val task = Task.CodeChange(
            id = "runtime-loop-task",
            status = TaskStatus.Pending,
            description = "Test runtime loop",
        )
        agent.getCurrentState().setNewTask(task)

        // Initialize agent (starts runtime loop)
        agent.initialize(this)

        // Give the runtime loop time to execute
        delay(2.seconds)

        // Pause agent
        agent.pauseAgent()

        // Verify the agent executed the cognitive loop
        val state = agent.getCurrentState()
        val pastMemory = state.getPastMemory()

        // The runtime loop should have:
        // - Generated ideas through perception
        // - Created plans
        // - Executed tasks
        // - Evaluated outcomes
        assertTrue(
            pastMemory.ideas.isNotEmpty() || pastMemory.plans.isNotEmpty(),
            "Runtime loop should have executed cognitive functions",
        )

        // Cleanup
        agent.shutdownAgent()
    }

    // ==================== Helper Functions ====================

    /**
     * Creates a mock AgentReasoning that returns predetermined responses
     * without requiring a real LLM connection.
     *
     * This enables testing the cognitive loop without LLM credentials.
     */
    private fun createMockReasoning(): AgentReasoning {
        return AgentReasoning.createForTesting("test-executor") {
            onPerception { perception: Perception<AgentState> ->
                Idea(
                    name = "Mock perception analysis",
                    description = "Agent should execute the pending task (confidence: high)",
                )
            }

            onPlanning { task: Task, ideas: List<Idea> ->
                Plan.ForTask(
                    task = task,
                    tasks = listOf(task),
                    estimatedComplexity = 1,
                )
            }

            onToolExecution { tool, request ->
                val now = Clock.System.now()
                ExecutionOutcome.CodeChanged.Success(
                    executorId = "mock-executor",
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = now + 100.milliseconds,
                    changedFiles = listOf("MockFile.kt"),
                    validation = ExecutionResult(
                        codeChanges = null,
                        compilation = null,
                        linting = null,
                        tests = null,
                    ),
                )
            }

            onOutcomeEvaluation { outcomes: List<Outcome> ->
                EvaluationResult(
                    summaryIdea = Idea(
                        name = "Mock outcome evaluation",
                        description = "Task completed successfully. Learning: Mock tools work as expected.",
                    ),
                    knowledge = emptyList(),
                )
            }

            onLLMCall { prompt: String ->
                "Mock LLM response for: $prompt"
            }
        }
    }

    /**
     * Creates a mock write_code_file tool for testing.
     *
     * @param alwaysSucceed If true, tool always returns success; if false, always returns failure
     */
    private fun createMockWriteCodeFileTool(alwaysSucceed: Boolean): Tool<ExecutionContext.Code.WriteCode> {
        return FunctionTool(
            id = "write_code_file",
            name = "Write Code File",
            description = "Mock tool for writing code to files",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                val now = Clock.System.now()

                if (alwaysSucceed) {
                    // Simulate successful file write
                    val changedFiles = request.context.instructionsPerFilePath.map { it.first }

                    ExecutionOutcome.CodeChanged.Success(
                        executorId = "mock-executor",
                        ticketId = request.context.ticket.id,
                        taskId = request.context.task.id,
                        executionStartTimestamp = now,
                        executionEndTimestamp = now + 100.milliseconds,
                        changedFiles = changedFiles,
                        validation = ExecutionResult(
                            codeChanges = null,
                            compilation = null,
                            linting = null,
                            tests = null,
                        ),
                    )
                } else {
                    // Simulate failure
                    ExecutionOutcome.CodeChanged.Failure(
                        executorId = "mock-executor",
                        ticketId = request.context.ticket.id,
                        taskId = request.context.task.id,
                        executionStartTimestamp = now,
                        executionEndTimestamp = now + 50.milliseconds,
                        error = ExecutionError(
                            type = ExecutionError.Type.TOOL_UNAVAILABLE,
                            message = "Mock tool failed intentionally",
                        ),
                        partiallyChangedFiles = null,
                    )
                }
            },
        )
    }
}
