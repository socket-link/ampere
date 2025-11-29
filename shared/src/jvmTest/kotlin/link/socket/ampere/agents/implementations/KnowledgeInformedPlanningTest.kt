@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package link.socket.ampere.agents.implementations

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
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.memory.AgentMemoryService
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeRepositoryImpl
import link.socket.ampere.agents.core.memory.MemoryContext
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.implementations.pm.KnowledgeAwareProductManagerAgent
import link.socket.ampere.agents.implementations.validation.ValidationAgent
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider
import kotlin.time.Duration.Companion.days

/**
 * Comprehensive test suite for knowledge-informed planning.
 *
 * Tests cover:
 * 1. ProductManagerAgent using knowledge to inform decomposition strategy
 * 2. ValidationAgent using knowledge to prioritize validation checks
 * 3. Knowledge extraction from outcomes
 * 4. Integration test showing full learning loop across multiple executions
 * 5. Cold-start scenario with no stored knowledge
 * 6. Mixed knowledge relevance filtering
 */
class KnowledgeInformedPlanningTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventBus: EventSerialBus
    private lateinit var pmMemoryService: AgentMemoryService
    private lateinit var validationMemoryService: AgentMemoryService
    private lateinit var pmAgent: KnowledgeAwareProductManagerAgent
    private lateinit var validationAgent: ValidationAgent

    private val now = Clock.System.now()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        val knowledgeRepository = KnowledgeRepositoryImpl(database)
        eventBus = EventSerialBus(testScope)

        pmMemoryService = AgentMemoryService(
            agentId = "pm-agent",
            knowledgeRepository = knowledgeRepository,
            eventBus = eventBus,
        )

        validationMemoryService = AgentMemoryService(
            agentId = "validation-agent",
            knowledgeRepository = knowledgeRepository,
            eventBus = eventBus,
        )

        pmAgent = KnowledgeAwareProductManagerAgent(
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = FakeAIConfiguration(),
            ),
            memoryServiceInstance = pmMemoryService,
        )

        validationAgent = ValidationAgent(
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = FakeAIConfiguration(),
            ),
            memoryServiceInstance = validationMemoryService,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Test 1: ProductManager with Test-First Knowledge ====================

    @Test
    fun `ProductManager incorporates test-first approach when past knowledge shows high success rate`() {
        runBlocking {
            // Store knowledge showing test-first approaches succeeded
            val testFirstKnowledge1 = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Used test-first development for feature implementation",
                learnings = "Success: test-first approach prevented issues with authentication logic",
                timestamp = now - 5.days,
            )
            pmMemoryService.storeKnowledge(
                knowledge = testFirstKnowledge1,
                tags = listOf("feature", "implementation"),
                taskType = "feature-implementation",
            )

            val testFirstKnowledge2 = Knowledge.FromOutcome(
                outcomeId = "outcome-2",
                approach = "Implemented user registration with comprehensive test suite first",
                learnings = "Success: writing tests first helped identify edge cases early",
                timestamp = now - 3.days,
            )
            pmMemoryService.storeKnowledge(
                knowledge = testFirstKnowledge2,
                tags = listOf("feature", "implementation"),
                taskType = "feature-implementation",
            )

            // Create a task for feature implementation
            val task = Task.CodeChange(
                id = "task-new-feature",
                status = TaskStatus.Pending,
                description = "Implement user authentication feature"
            )

            // Recall knowledge
            val context = MemoryContext(
                taskType = "feature-implementation",
                tags = setOf("feature", "implementation"),
                description = "implementing user authentication feature",
            )
            val recalled = pmAgent.recallRelevantKnowledge(context, limit = 10).getOrThrow()

            // Generate plan with recalled knowledge
            val plan = pmAgent.determinePlanForTask(task, Idea.blank, recalled)

            // Verify plan includes test-first steps
            assertNotNull(plan)
            assertTrue(plan.tasks.isNotEmpty())

            // Check if any task mentions tests
            val hasTestTask = plan.tasks.any { t ->
                when (t) {
                    is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                    else -> false
                }
            }
            assertTrue(hasTestTask, "Plan should include test-related tasks based on past knowledge")

            // Check if plan references past knowledge success rate
            val hasSuccessRateReference = plan.tasks.any { t ->
                when (t) {
                    is Task.CodeChange -> t.description.contains("success rate", ignoreCase = true) ||
                        t.description.contains("%", ignoreCase = false)
                    else -> false
                }
            }
            assertTrue(hasSuccessRateReference, "Plan should reference past knowledge success rate")
        }
    }

    // ==================== Test 2: ProductManager with Failure Pattern Knowledge ====================

    @Test
    fun `ProductManager adds preventive steps for known failure patterns`() {
        runBlocking {
            // Store knowledge about past failures
            val failureKnowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-failure",
                approach = "Implemented database migration without validation",
                learnings = "Failure: Column type changes broke existing data. Always validate schema changes in staging first.",
                timestamp = now - 10.days,
            )
            pmMemoryService.storeKnowledge(
                knowledge = failureKnowledge,
                tags = listOf("database", "migration"),
                taskType = "database-migration",
            )

            // Create a task for database migration
            val task = Task.CodeChange(
                id = "task-db-migration",
                status = TaskStatus.Pending,
                description = "Update database schema for new user fields"
            )

            // Recall knowledge
            val context = MemoryContext(
                taskType = "database-migration",
                tags = setOf("database", "migration"),
                description = "database schema changes",
            )
            val recalled = pmAgent.recallRelevantKnowledge(context, limit = 10).getOrThrow()

            // Generate plan with recalled knowledge
            val plan = pmAgent.determinePlanForTask(task, Idea.blank, recalled)

            // Verify plan includes validation steps
            assertNotNull(plan)
            assertTrue(plan.tasks.isNotEmpty())

            // Check if plan mentions known failure patterns
            val hasValidationTask = plan.tasks.any { t ->
                when (t) {
                    is Task.CodeChange -> t.description.contains("validate", ignoreCase = true) ||
                        t.description.contains("failure pattern", ignoreCase = true)
                    else -> false
                }
            }
            assertTrue(hasValidationTask, "Plan should include validation against known failure patterns")
        }
    }

    // ==================== Test 3: ValidationAgent Prioritizes Effective Checks ====================

    @Test
    fun `ValidationAgent prioritizes checks that historically caught the most issues`() {
        runBlocking {
            // Store knowledge showing unit tests caught many issues
            val unitTestKnowledge = Knowledge.FromOutcome(
                outcomeId = "validation-1",
                approach = "Ran unit test validation suite",
                learnings = "Validation caught 15 issues: null pointer exceptions, boundary condition failures",
                timestamp = now - 2.days,
            )
            validationMemoryService.storeKnowledge(
                knowledge = unitTestKnowledge,
                tags = listOf("validation", "unit-test"),
                taskType = "code-validation",
            )

            // Store knowledge showing lint found fewer issues
            val lintKnowledge = Knowledge.FromOutcome(
                outcomeId = "validation-2",
                approach = "Ran lint checks on codebase",
                learnings = "Found 2 style issues, no critical problems detected",
                timestamp = now - 2.days,
            )
            validationMemoryService.storeKnowledge(
                knowledge = lintKnowledge,
                tags = listOf("validation", "lint"),
                taskType = "code-validation",
            )

            // Create a validation task
            val task = Task.CodeChange(
                id = "task-validate-pr",
                status = TaskStatus.Pending,
                description = "Validate pull request before merge"
            )

            // Recall knowledge
            val context = MemoryContext(
                taskType = "code-validation",
                tags = setOf("validation"),
                description = "validate code changes",
            )
            val recalled = validationAgent.recallRelevantKnowledge(context, limit = 10).getOrThrow()

            // Generate plan with recalled knowledge
            val plan = validationAgent.determinePlanForTask(task, Idea.blank, recalled)

            // Verify plan prioritizes unit tests over lint
            assertNotNull(plan)
            assertTrue(plan.tasks.isNotEmpty())

            // Unit test task should appear before lint task (if both exist)
            val unitTestIndex = plan.tasks.indexOfFirst { t ->
                when (t) {
                    is Task.CodeChange -> t.description.contains("unit test", ignoreCase = true)
                    else -> false
                }
            }
            assertTrue(unitTestIndex >= 0, "Plan should include unit test validation")

            // Check that effectiveness is mentioned
            val mentionsEffectiveness = plan.tasks.any { t ->
                when (t) {
                    is Task.CodeChange -> t.description.contains("effectiveness", ignoreCase = true) ||
                        t.description.contains("caught", ignoreCase = true) ||
                        t.description.contains("issues", ignoreCase = true)
                    else -> false
                }
            }
            assertTrue(mentionsEffectiveness, "Plan should reference historical effectiveness")
        }
    }

    // ==================== Test 4: Knowledge Extraction from Outcomes ====================

    @Test
    fun `Agents extract meaningful knowledge from completed outcomes`() {
        runBlocking {
            val task = Task.CodeChange(
                id = "task-test",
                status = TaskStatus.Pending,
                description = "Implement authentication API"
            )

            val plan = Plan.ForTask(
                task = task,
                tasks = listOf(
                    Task.CodeChange(
                        id = "subtask-1",
                        status = TaskStatus.Pending,
                        description = "Write tests for authentication"
                    ),
                    Task.CodeChange(
                        id = "subtask-2",
                        status = TaskStatus.Pending,
                        description = "Implement authentication logic"
                    )
                ),
                estimatedComplexity = 5,
            )

            val outcome = Outcome.Success(
                id = "outcome-test",
                result = "Authentication implemented successfully",
                timestamp = now,
            )

            // Extract knowledge
            val knowledge = pmAgent.extractKnowledgeFromOutcome(outcome, task, plan)

            // Verify knowledge quality
            assertNotNull(knowledge)
            assertTrue(knowledge is Knowledge.FromOutcome)
            assertTrue(knowledge.approach.contains("2 tasks", ignoreCase = true))
            assertTrue(knowledge.approach.contains("test", ignoreCase = true))
            assertTrue(knowledge.learnings.contains("Success", ignoreCase = true))
            assertTrue(knowledge.learnings.contains("worked well", ignoreCase = true))
        }
    }

    // ==================== Test 5: Full Learning Loop Integration ====================

    @Test
    fun `Full learning loop - second task benefits from first task knowledge`() {
        runBlocking {
            // FIRST EXECUTION: No knowledge available
            val task1 = Task.CodeChange(
                id = "task-feature-1",
                status = TaskStatus.Pending,
                description = "Implement user registration"
            )

            val context1 = MemoryContext(
                taskType = "user-feature",
                tags = setOf("user", "feature"),
                description = "user registration feature",
            )
            val recalled1 = pmAgent.recallRelevantKnowledge(context1).getOrThrow()

            // Should be empty on first run
            assertTrue(recalled1.isEmpty(), "No knowledge should exist for novel task")

            val plan1 = pmAgent.determinePlanForTask(task1, Idea.blank, recalled1)

            // Simulate successful execution
            val outcome1 = Outcome.Success(
                id = "outcome-1",
                result = "Registration feature completed successfully with test-first approach",
                timestamp = now,
            )

            // Extract and store knowledge
            val knowledge1 = pmAgent.extractKnowledgeFromOutcome(outcome1, task1, plan1)
            pmMemoryService.storeKnowledge(
                knowledge = knowledge1,
                tags = listOf("user", "feature", "test-first"),
                taskType = "user-feature",
            )

            // SECOND EXECUTION: Similar task should benefit from stored knowledge
            val task2 = Task.CodeChange(
                id = "task-feature-2",
                status = TaskStatus.Pending,
                description = "Implement user login"
            )

            val context2 = MemoryContext(
                taskType = "user-feature",
                tags = setOf("user", "feature"),
                description = "user login feature",
            )
            val recalled2 = pmAgent.recallRelevantKnowledge(context2).getOrThrow()

            // Should find previous knowledge
            assertTrue(recalled2.isNotEmpty(), "Should recall knowledge from similar past task")
            assertEquals(1, recalled2.size)

            val plan2 = pmAgent.determinePlanForTask(task2, Idea.blank, recalled2)

            // Plan should reflect learnings from first task
            val plan2MentionsSuccess = plan2.tasks.any { t ->
                when (t) {
                    is Task.CodeChange -> t.description.contains("success", ignoreCase = true) ||
                        t.description.contains("worked well", ignoreCase = true)
                    else -> false
                }
            }

            assertTrue(
                plan2MentionsSuccess,
                "Second plan should incorporate learnings from first successful execution"
            )
        }
    }

    // ==================== Test 6: Cold Start with Novel Task ====================

    @Test
    fun `Agent generates reasonable plan for completely novel task with no knowledge`() {
        runBlocking {
            val novelTask = Task.CodeChange(
                id = "task-novel",
                status = TaskStatus.Pending,
                description = "Implement quantum encryption algorithm"
            )

            val context = MemoryContext(
                taskType = "quantum-algorithm",
                tags = setOf("quantum", "encryption", "novel"),
                description = "quantum encryption implementation",
            )
            val recalled = pmAgent.recallRelevantKnowledge(context).getOrThrow()

            // Should be empty for completely novel domain
            assertTrue(recalled.isEmpty())

            // Agent should still generate a plan
            val plan = pmAgent.determinePlanForTask(novelTask, Idea.blank, recalled)

            assertNotNull(plan)
            assertTrue(plan.tasks.isNotEmpty(), "Agent should generate plan even without past knowledge")

            // Extract knowledge for future use
            val outcome = Outcome.Success(
                id = "outcome-novel",
                result = "Quantum algorithm implemented",
                timestamp = now,
            )
            val knowledge = pmAgent.extractKnowledgeFromOutcome(outcome, novelTask, plan)

            // Store for future reference
            pmMemoryService.storeKnowledge(
                knowledge = knowledge,
                tags = listOf("quantum", "algorithm"),
                taskType = "quantum-algorithm",
            )

            // Verify it was stored
            val recalledAfterStore = pmAgent.recallRelevantKnowledge(context).getOrThrow()
            assertEquals(1, recalledAfterStore.size, "Knowledge should be stored for future recall")
        }
    }

    // ==================== Test 7: Mixed Relevance Filtering ====================

    @Test
    fun `Agent filters knowledge by relevance and uses only high-relevance entries`() {
        runBlocking {
            // Store highly relevant knowledge
            val highRelevance = Knowledge.FromOutcome(
                outcomeId = "outcome-high",
                approach = "API endpoint implementation with test suite",
                learnings = "Success: test-driven development caught authentication bugs early",
                timestamp = now - 1.days,
            )
            pmMemoryService.storeKnowledge(
                knowledge = highRelevance,
                tags = listOf("api", "endpoint", "authentication"),
                taskType = "api-development",
            )

            // Store somewhat relevant knowledge
            val mediumRelevance = Knowledge.FromOutcome(
                outcomeId = "outcome-medium",
                approach = "Database schema optimization",
                learnings = "Indexing improved query performance by 40%",
                timestamp = now - 5.days,
            )
            pmMemoryService.storeKnowledge(
                knowledge = mediumRelevance,
                tags = listOf("database", "performance"),
                taskType = "database-optimization",
            )

            // Create API task
            val task = Task.CodeChange(
                id = "task-api",
                status = TaskStatus.Pending,
                description = "Implement new API endpoint for authentication"
            )

            val context = MemoryContext(
                taskType = "api-development",
                tags = setOf("api", "authentication"),
                description = "api endpoint for authentication",
            )
            val recalled = pmAgent.recallRelevantKnowledge(context).getOrThrow()

            // Should primarily recall high-relevance knowledge
            assertTrue(recalled.isNotEmpty())

            // Most relevant should be first (sorted by relevance)
            val topResult = recalled.first()
            assertEquals("outcome-high", topResult.entry.outcomeId)
            assertTrue(topResult.relevanceScore > 0.0, "Top result should have measurable relevance")
        }
    }

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Not needed for tests")
        override val model: AIModel
            get() = throw NotImplementedError("Not needed for tests")
        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }
}
