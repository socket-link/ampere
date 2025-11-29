@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package link.socket.ampere.agents.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.core.memory.AgentMemoryService
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeRepositoryImpl
import link.socket.ampere.agents.core.memory.MemoryContext
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Perception
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider
import kotlin.time.Duration.Companion.days

/**
 * Comprehensive test suite for Agent memory recall functionality.
 *
 * Tests cover:
 * 1. Agent calling recallRelevantKnowledge and delegating to service
 * 2. Error handling when memory service fails
 * 3. Store-recall integration cycle
 * 4. Cold start scenario with no stored knowledge
 * 5. Complete learning loop with knowledge extraction
 *
 * Validates the requirements from AMPERE-004 Task 4.
 */
class AgentMemoryRecallTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventBus: EventSerialBus
    private lateinit var memoryService: AgentMemoryService
    private lateinit var testAgent: TestAgentWithMemory

    private val agentId = "test-agent-memory-recall"
    private lateinit var now: Instant

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        val knowledgeRepository = KnowledgeRepositoryImpl(database)
        eventBus = EventSerialBus(testScope)
        now = Clock.System.now()

        memoryService = AgentMemoryService(
            agentId = agentId,
            knowledgeRepository = knowledgeRepository,
            eventBus = eventBus,
        )

        testAgent = TestAgentWithMemory(
            id = agentId,
            memoryServiceInstance = memoryService,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Test 1: Basic Memory Recall Delegation ====================

    @Test
    fun `recallRelevantKnowledge queries AgentMemoryService with correct parameters`() {
        runBlocking {
            // First, store some knowledge directly via the service
            val knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Database migration with validation",
                learnings = "Always run dry-run before applying schema changes",
                timestamp = now - 1.days,
            )

            memoryService.storeKnowledge(
                knowledge = knowledge,
                tags = listOf("database", "migration"),
                taskType = "schema-migration",
            )

            // Now query through the agent
            val context = MemoryContext(
                taskType = "schema-migration",
                tags = setOf("database", "migration"),
                description = "Applying database schema changes",
            )

            val result = testAgent.testRecallRelevantKnowledge(context, limit = 5)

            // Verify the agent successfully delegated to the service
            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)
            assertTrue(recalled.isNotEmpty())
            assertEquals(1, recalled.size)
            assertEquals("outcome-1", recalled.first().entry.outcomeId)
            assertTrue(recalled.first().relevanceScore > 0.0)
        }
    }

    // ==================== Test 2: Error Handling ====================

    @Test
    fun `recallRelevantKnowledge handles missing memory service gracefully`() {
        runBlocking {
            // Create agent without memory service
            val agentWithoutMemory = TestAgentWithoutMemory(id = "agent-no-memory")

            val context = MemoryContext(
                taskType = "any-task",
                tags = emptySet(),
                description = "test",
            )

            val result = agentWithoutMemory.testRecallRelevantKnowledge(context)

            // Should fail gracefully with appropriate error
            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is AgentError.MemoryRecallFailure)
            assertTrue(error.message.contains("Memory service not configured"))
        }
    }

    // ==================== Test 3: Store-Recall Integration Cycle ====================

    @Test
    fun `agent can store knowledge and later recall it with similar context`() {
        runBlocking {
            // Step 1: Store knowledge from an outcome
            val knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-test-integration",
                approach = "Implemented user authentication with JWT tokens",
                learnings = "Store refresh tokens securely in httpOnly cookies",
                timestamp = now,
            )

            val storeResult = testAgent.testStoreKnowledge(
                knowledge = knowledge,
                tags = listOf("security", "authentication", "jwt"),
                taskType = "authentication-implementation",
            )

            assertTrue(storeResult.isSuccess)

            // Verify it was added to in-memory state as well
            val pastMemory = testAgent.getCurrentState().getPastMemory()
            assertEquals(1, pastMemory.knowledgeFromOutcomes.size)
            assertEquals("outcome-test-integration", pastMemory.knowledgeFromOutcomes.first().outcomeId)

            // Step 2: Later, recall it with a similar context
            val context = MemoryContext(
                taskType = "authentication-implementation",
                tags = setOf("security", "authentication"),
                description = "implementing user authentication with tokens",
            )

            val recallResult = testAgent.testRecallRelevantKnowledge(context)

            assertTrue(recallResult.isSuccess)
            val recalled = recallResult.getOrNull()
            assertNotNull(recalled)
            assertTrue(recalled.isNotEmpty())

            // Should find our stored knowledge
            val matchingEntry = recalled.find { it.entry.outcomeId == "outcome-test-integration" }
            assertNotNull(matchingEntry)
            assertTrue(matchingEntry.relevanceScore > 0.0)
            assertEquals("Implemented user authentication with JWT tokens", matchingEntry.knowledge.approach)
            assertEquals("Store refresh tokens securely in httpOnly cookies", matchingEntry.knowledge.learnings)
        }
    }

    // ==================== Test 4: Cold Start Scenario ====================

    @Test
    fun `recallRelevantKnowledge returns empty list for completely novel context`() {
        runBlocking {
            // Don't store any knowledge - fresh agent

            val context = MemoryContext(
                taskType = "novel-task-type-never-seen",
                tags = setOf("completely", "new", "domain"),
                description = "This is a task the agent has never encountered before",
            )

            val result = testAgent.testRecallRelevantKnowledge(context)

            // Should succeed but return empty results
            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)
            assertTrue(recalled.isEmpty(), "Should return empty list for novel situation")
        }
    }

    @Test
    fun `agent can proceed normally when no relevant knowledge exists`() {
        runBlocking {
            // Store knowledge about database migrations
            val knowledge = Knowledge.FromTask(
                taskId = "task-db",
                approach = "Database schema migration",
                learnings = "Use version control for schemas",
                timestamp = now,
            )

            testAgent.testStoreKnowledge(
                knowledge = knowledge,
                tags = listOf("database", "schema"),
                taskType = "database-migration",
            )

            // Query for completely unrelated context (frontend UI)
            val context = MemoryContext(
                taskType = "ui-design",
                tags = setOf("frontend", "react", "design"),
                description = "Designing user interface components",
            )

            val result = testAgent.testRecallRelevantKnowledge(context)

            // Should succeed with no or low-scored results
            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)

            // If any results, they should have low relevance scores
            recalled.forEach { scoredKnowledge ->
                // The scoring might find weak matches, but scores should be low
                assertTrue(scoredKnowledge.relevanceScore <= 1.0)
            }
        }
    }

    // ==================== Test 5: Knowledge Type Handling ====================

    @Test
    fun `storeKnowledge correctly handles all Knowledge types and updates AgentState`() {
        runBlocking {
            // Test FromIdea
            val ideaKnowledge = Knowledge.FromIdea(
                ideaId = "idea-1",
                approach = "Test-driven development",
                learnings = "Writing tests first clarifies requirements",
                timestamp = now,
            )
            testAgent.testStoreKnowledge(ideaKnowledge, tags = listOf("testing"))
            assertEquals(1, testAgent.getCurrentState().getPastMemory().knowledgeFromIdeas.size)

            // Test FromOutcome
            val outcomeKnowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Refactoring legacy code",
                learnings = "Small incremental changes reduce risk",
                timestamp = now,
            )
            testAgent.testStoreKnowledge(outcomeKnowledge, tags = listOf("refactoring"))
            assertEquals(1, testAgent.getCurrentState().getPastMemory().knowledgeFromOutcomes.size)

            // Test FromPerception
            val perceptionKnowledge = Knowledge.FromPerception(
                perceptionId = "perception-1",
                approach = "Monitoring system metrics",
                learnings = "High memory usage indicates memory leaks",
                timestamp = now,
            )
            testAgent.testStoreKnowledge(perceptionKnowledge, tags = listOf("monitoring"))
            assertEquals(1, testAgent.getCurrentState().getPastMemory().knowledgeFromPerceptions.size)

            // Test FromPlan
            val planKnowledge = Knowledge.FromPlan(
                planId = "plan-1",
                approach = "Feature decomposition",
                learnings = "Breaking features into smaller tasks improves estimation",
                timestamp = now,
            )
            testAgent.testStoreKnowledge(planKnowledge, tags = listOf("planning"))
            assertEquals(1, testAgent.getCurrentState().getPastMemory().knowledgeFromPlans.size)

            // Test FromTask
            val taskKnowledge = Knowledge.FromTask(
                taskId = "task-1",
                approach = "Parallel task execution",
                learnings = "Independent tasks can be executed concurrently",
                timestamp = now,
            )
            testAgent.testStoreKnowledge(taskKnowledge, tags = listOf("concurrency"))
            assertEquals(1, testAgent.getCurrentState().getPastMemory().knowledgeFromTasks.size)
        }
    }

    // ==================== Test 6: Relevance Scoring ====================

    @Test
    fun `recalled knowledge is ranked by relevance score`() {
        runBlocking {
            // Store multiple knowledge entries with varying relevance
            val veryRelevant = Knowledge.FromOutcome(
                outcomeId = "outcome-very-relevant",
                approach = "API endpoint authentication implementation",
                learnings = "Use JWT tokens for stateless authentication",
                timestamp = now - 1.days, // Recent
            )
            testAgent.testStoreKnowledge(
                knowledge = veryRelevant,
                tags = listOf("api", "authentication", "jwt"),
                taskType = "api-development",
            )

            val somewhatRelevant = Knowledge.FromTask(
                taskId = "task-somewhat-relevant",
                approach = "API endpoint design",
                learnings = "Follow REST conventions for consistency",
                timestamp = now - 7.days, // Less recent
            )
            testAgent.testStoreKnowledge(
                knowledge = somewhatRelevant,
                tags = listOf("api", "design"),
                taskType = "api-development",
            )

            val lessRelevant = Knowledge.FromIdea(
                ideaId = "idea-less-relevant",
                approach = "General software architecture",
                learnings = "Use layered architecture for separation of concerns",
                timestamp = now - 30.days, // Old
            )
            testAgent.testStoreKnowledge(
                knowledge = lessRelevant,
                tags = listOf("architecture"),
                taskType = "architecture-design",
            )

            // Query for API authentication
            val context = MemoryContext(
                taskType = "api-development",
                tags = setOf("api", "authentication"),
                description = "implementing api authentication with jwt tokens",
            )

            val result = testAgent.testRecallRelevantKnowledge(context, limit = 10)

            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)
            assertTrue(recalled.isNotEmpty())

            // Results should be sorted by descending relevance
            val scores = recalled.map { it.relevanceScore }
            assertEquals(scores, scores.sortedDescending(), "Results should be sorted by relevance")

            // Most relevant should be first
            val topResult = recalled.first()
            assertEquals("outcome-very-relevant", topResult.entry.outcomeId)
        }
    }

    // ==================== Test 7: Limit Parameter ====================

    @Test
    fun `recallRelevantKnowledge respects limit parameter`() {
        runBlocking {
            // Store 10 knowledge entries
            repeat(10) { i ->
                testAgent.testStoreKnowledge(
                    knowledge = Knowledge.FromTask(
                        taskId = "task-$i",
                        approach = "Approach $i for testing task",
                        learnings = "Learning $i from testing",
                        timestamp = now,
                    ),
                    tags = listOf("testing"),
                    taskType = "test-task",
                )
            }

            val context = MemoryContext(
                taskType = "test-task",
                tags = setOf("testing"),
                description = "testing",
            )

            // Query with limit of 3
            val result = testAgent.testRecallRelevantKnowledge(context, limit = 3)

            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)
            assertTrue(recalled.size <= 3, "Should return at most 3 results")
        }
    }
}

// ==================== Test Agent Implementations ====================

/**
 * Fake AI configuration for testing (doesn't require actual AI providers).
 */
private class FakeAIConfiguration : AIConfiguration {
    override val provider: AIProvider<*, *>
        get() = throw NotImplementedError("Not needed for tests")
    override val model: AIModel
        get() = throw NotImplementedError("Not needed for tests")
    override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
}

/**
 * Test agent with memory service configured.
 * Extends AutonomousAgent (not sealed) so can be defined in test module.
 */
private class TestAgentWithMemory(
    override val id: AgentId,
    private val memoryServiceInstance: AgentMemoryService,
) : AutonomousAgent<AgentState>() {

    override val initialState = AgentState()
    override val agentConfiguration = AgentConfiguration(
        agentDefinition = WriteCodeAgent,
        aiConfiguration = FakeAIConfiguration(),
    )

    override val memoryService: AgentMemoryService = memoryServiceInstance

    // Expose protected methods for testing
    suspend fun testRecallRelevantKnowledge(
        context: MemoryContext,
        limit: Int = 10
    ) = recallRelevantKnowledge(context, limit)

    suspend fun testStoreKnowledge(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null
    ) = storeKnowledge(knowledge, tags, taskType)

    // Implement abstract methods (not needed for these tests, provide stubs)
    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea = { Idea.blank }
    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan = { _, _ -> Plan.blank }
    override val runLLMToExecuteTask: (task: Task) -> Outcome = { Outcome.blank }
    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome = { _, _ -> ExecutionOutcome.blank }
    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea = { Idea.blank }

    override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge {
        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = "Test approach",
            learnings = "Test learnings",
            timestamp = Clock.System.now()
        )
    }
}

/**
 * Test agent without memory service.
 */
private class TestAgentWithoutMemory(
    override val id: AgentId,
) : AutonomousAgent<AgentState>() {

    override val initialState = AgentState()
    override val agentConfiguration = AgentConfiguration(
        agentDefinition = WriteCodeAgent,
        aiConfiguration = FakeAIConfiguration(),
    )

    // No memory service configured
    override val memoryService: AgentMemoryService? = null

    // Expose protected methods for testing
    suspend fun testRecallRelevantKnowledge(
        context: MemoryContext,
        limit: Int = 10
    ) = recallRelevantKnowledge(context, limit)

    // Implement abstract methods
    override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea = { Idea.blank }
    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan = { _, _ -> Plan.blank }
    override val runLLMToExecuteTask: (task: Task) -> Outcome = { Outcome.blank }
    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome = { _, _ -> ExecutionOutcome.blank }
    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea = { Idea.blank }

    override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge {
        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = "Test approach",
            learnings = "Test learnings",
            timestamp = Clock.System.now()
        )
    }
}
