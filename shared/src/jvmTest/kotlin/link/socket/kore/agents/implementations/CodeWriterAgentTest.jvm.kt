package link.socket.kore.agents.implementations

import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.kore.agents.core.Idea
import link.socket.kore.agents.core.Outcome
import link.socket.kore.agents.core.Plan
import link.socket.kore.agents.events.tasks.CodeChange
import link.socket.kore.agents.events.tasks.Task
import link.socket.kore.agents.tools.WriteCodeFileTool
import link.socket.kore.domain.ai.configuration.AIConfiguration
import link.socket.kore.domain.ai.model.AIModel
import link.socket.kore.domain.ai.provider.AIProvider

@OptIn(ExperimentalCoroutinesApi::class)
actual class CodeWriterAgentTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var tempDir: java.nio.file.Path

    // ==================== FAKE IMPLEMENTATIONS ====================

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Not needed for tests")
        override val model: AIModel
            get() = throw NotImplementedError("Not needed for tests")
        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory(prefix = "kore-agent-test-")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ==================== INITIALIZATION TESTS ====================

    @Test
    fun `CodeWriterAgent has correct id`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(tool)

        assertEquals("CodeWriterAgent", agent.id)
        agent.shutdownAgent()
    }

    @Test
    fun `CodeWriterAgent has WriteCodeFileTool in required tools`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(tool)

        assertEquals(1, agent.requiredTools.size)
        assertTrue(agent.requiredTools.contains(tool))
        agent.shutdownAgent()
    }

    @Test
    fun `CodeWriterAgent initializes with empty state`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(tool)

        val state = agent.getCurrentState()
        assertEquals(Idea.blank, state.currentIdea)
        assertEquals(Plan.blank, state.currentPlan)
        assertTrue(state.ideaHistory.isEmpty())
        assertTrue(state.planHistory.isEmpty())
        assertTrue(state.taskHistory.isEmpty())

        agent.shutdownAgent()
    }

    // ==================== LIFECYCLE TESTS ====================

    @Test
    fun `CodeWriterAgent can be initialized and shutdown`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(tool)

        runBlocking {
            agent.initialize(testScope)
            delay(50)
            agent.shutdownAgent()
        }

        // Should complete without exception
        val state = agent.getCurrentState()
        assertEquals(Idea.blank, state.currentIdea)
    }

    @Test
    fun `CodeWriterAgent can be paused and resumed`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(tool)

        runBlocking {
            agent.initialize(testScope)
            delay(20)

            agent.pauseAgent()

            agent.resumeAgent()
            delay(20)

            agent.shutdownAgent()
        }
    }

    // ==================== AGENT ACTIONS TESTS ====================

    @Test
    fun `perceiveState creates perception and returns idea`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val expectedIdea = Idea(name = "Code Task", description = "Write a function")

        val agent = createAgent(
            tool,
            perceiveResult = expectedIdea
        )

        runBlocking {
            val result = agent.perceiveState(Idea(name = "Input"))

            assertEquals(expectedIdea, result)
            assertEquals(1, agent.getRecentPerceptions().size)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `planIdea returns plan with tasks`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val task = CodeChange(id = "task-1", description = "Create file")
        val expectedPlan = Plan(estimatedComplexity = 3, tasks = listOf(task))

        val agent = createAgent(
            tool,
            planResult = expectedPlan
        )

        runBlocking {
            val result = agent.planIdea(Idea(name = "Write code"))

            assertEquals(expectedPlan, result)
            assertEquals(3, result.estimatedComplexity)
            assertEquals(1, result.tasks.size)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `executePlan executes all tasks and returns outcome`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val expectedOutcome = Outcome.Success.Full(Task.blank, "Code written successfully")

        val agent = createAgent(
            tool,
            executeResult = expectedOutcome
        )

        runBlocking {
            val task = CodeChange(id = "task-1", description = "Write code")
            val plan = Plan(estimatedComplexity = 2, tasks = listOf(task))

            val result = agent.executePlan(plan)

            assertIs<Outcome.Success.Full>(result)
            assertEquals("Code written successfully", result.value)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `executePlan with multiple tasks remembers all tasks`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(
            tool,
            executeResult = Outcome.Success.Full(Task.blank, "Done")
        )

        runBlocking {
            val task1 = CodeChange(id = "1", description = "First task")
            val task2 = CodeChange(id = "2", description = "Second task")
            val plan = Plan(estimatedComplexity = 4, tasks = listOf(task1, task2))

            agent.executePlan(plan)

            val tasks = agent.getRecentTasks()
            assertEquals(2, tasks.size)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `executePlan handles failure outcome`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val failureOutcome = Outcome.Failure(Task.blank, "Compilation error")

        val agent = createAgent(
            tool,
            executeResult = failureOutcome
        )

        runBlocking {
            val task = CodeChange(id = "1", description = "Bad code")
            val plan = Plan(estimatedComplexity = 1, tasks = listOf(task))

            val result = agent.executePlan(plan)

            assertIs<Outcome.Failure>(result)
            assertEquals("Compilation error", result.errorMessage)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `runTask returns outcome and remembers it`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val expectedOutcome = Outcome.Success.Full(Task.blank, "Task completed")

        val agent = createAgent(
            tool,
            executeResult = expectedOutcome
        )

        runBlocking {
            val task = CodeChange(id = "single-task", description = "Single task")
            val result = agent.runTask(task)

            assertIs<Outcome.Success.Full>(result)
            assertEquals(1, agent.getRecentOutcomes().size)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `runTool executes tool and returns outcome`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val expectedOutcome = Outcome.Success.Full(Task.blank, "Tool executed")

        val agent = createAgent(
            tool,
            toolResult = expectedOutcome
        )

        runBlocking {
            val result = agent.runTool(tool, mapOf("filePath" to "test.kt", "content" to "fun test() {}"))

            assertIs<Outcome.Success>(result)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `evaluateNewIdeas returns new idea based on outcomes`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val expectedIdea = Idea(name = "Next Step", description = "Run tests")

        val agent = createAgent(
            tool,
            evaluateResult = expectedIdea
        )

        runBlocking {
            val outcome = Outcome.Success.Full(Task.blank, "Code written")
            val result = agent.evaluateNewIdeas(outcome)

            assertEquals("Next Step", result.name)
            assertEquals("Run tests", result.description)
        }

        agent.shutdownAgent()
    }

    // ==================== STATE MANAGEMENT TESTS ====================

    @Test
    fun `agent maintains perception history across multiple perceiveState calls`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        var callCount = 0
        val ideas = listOf(
            Idea(name = "First Idea"),
            Idea(name = "Second Idea"),
            Idea(name = "Third Idea")
        )

        val agent = CodeWriterAgent(
            coroutineScope = testScope,
            writeCodeFileTool = tool,
            runLLMToPerceive = { ideas[callCount++ % ideas.size] },
            runLLMToPlan = { Plan.blank },
            runLLMToExecuteTask = { Outcome.blank },
            runLLMToExecuteTool = { _, _ -> Outcome.blank },
            runLLMToEvaluate = { Idea.blank },
            aiConfiguration = FakeAIConfiguration()
        )

        runBlocking {
            // perceiveState only stores perceptions, not the returned ideas
            val idea1 = agent.perceiveState(Idea.blank)
            val idea2 = agent.perceiveState(Idea.blank)
            val idea3 = agent.perceiveState(Idea.blank)

            // Verify returned ideas
            assertEquals("First Idea", idea1.name)
            assertEquals("Second Idea", idea2.name)
            assertEquals("Third Idea", idea3.name)

            // Verify perceptions are stored
            val perceptions = agent.getRecentPerceptions()
            assertEquals(3, perceptions.size)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `agent maintains outcome history across multiple executions`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(
            tool,
            executeResult = Outcome.Success.Full(Task.blank, "Done")
        )

        runBlocking {
            val task = CodeChange(id = "1", description = "Task")
            agent.runTask(task)
            agent.runTask(task)
            agent.runTask(task)

            val outcomes = agent.getRecentOutcomes()
            assertEquals(3, outcomes.size)
        }

        agent.shutdownAgent()
    }

    @Test
    fun `shutdown clears all agent state`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val agent = createAgent(tool)

        runBlocking {
            agent.perceiveState(Idea(name = "Test"))

            val task = CodeChange(id = "1", description = "Test")
            agent.runTask(task)

            agent.shutdownAgent()

            val state = agent.getCurrentState()
            assertEquals(Idea.blank, state.currentIdea)
            assertTrue(state.ideaHistory.isEmpty())
            assertTrue(state.outcomeHistory.isEmpty())
        }
    }

    // ==================== INTEGRATION TESTS ====================

    @Test
    fun `agent can complete full perceive-plan-execute-evaluate cycle`() {
        val tool = WriteCodeFileTool(tempDir.absolutePathString())
        val task = CodeChange(id = "code-task", description = "Write function")

        val agent = createAgent(
            tool,
            perceiveResult = Idea(name = "Write Code", description = "Create a new function"),
            planResult = Plan(estimatedComplexity = 2, tasks = listOf(task)),
            executeResult = Outcome.Success.Full(task, "Function created"),
            evaluateResult = Idea(name = "Complete", description = "Task finished")
        )

        runBlocking {
            // Perceive
            val perceivedIdea = agent.perceiveState(Idea.blank)
            assertEquals("Write Code", perceivedIdea.name)

            // Plan
            val plan = agent.planIdea(perceivedIdea)
            assertEquals(2, plan.estimatedComplexity)
            assertEquals(1, plan.tasks.size)

            // Execute
            val outcome = agent.executePlan(plan)
            assertIs<Outcome.Success.Full>(outcome)

            // Evaluate
            val nextIdea = agent.evaluateNewIdeas(outcome)
            assertEquals("Complete", nextIdea.name)

            // Verify state
            assertTrue(agent.getRecentPerceptions().isNotEmpty())
            assertTrue(agent.getRecentPlans().isNotEmpty())
            assertTrue(agent.getRecentTasks().isNotEmpty())
            assertTrue(agent.getRecentOutcomes().isNotEmpty())
        }

        agent.shutdownAgent()
    }

    // ==================== HELPER FUNCTIONS ====================

    private fun createAgent(
        tool: WriteCodeFileTool,
        perceiveResult: Idea = Idea(name = "Default Perception"),
        planResult: Plan = Plan(estimatedComplexity = 1, tasks = emptyList()),
        executeResult: Outcome = Outcome.Success.Full(Task.blank, "Done"),
        toolResult: Outcome = Outcome.Success.Full(Task.blank, "Tool done"),
        evaluateResult: Idea = Idea(name = "Default Evaluation")
    ): CodeWriterAgent = CodeWriterAgent(
        coroutineScope = testScope,
        writeCodeFileTool = tool,
        runLLMToPerceive = { perceiveResult },
        runLLMToPlan = { planResult },
        runLLMToExecuteTask = { executeResult },
        runLLMToExecuteTool = { _, _ -> toolResult },
        runLLMToEvaluate = { evaluateResult },
        aiConfiguration = FakeAIConfiguration()
    )
}
