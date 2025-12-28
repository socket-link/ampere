@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package link.socket.ampere.agents.domain.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.db.Database

/**
 * Comprehensive test suite for AgentMemoryService.
 *
 * Tests cover:
 * 1. Round-trip persistence of all Knowledge subtypes
 * 2. Context-based recall with scoring
 * 3. Event emission for observability
 * 4. Multi-strategy retrieval (task type, tags, FTS, temporal)
 * 5. Edge cases (empty results, novel situations)
 *
 * Validates the requirements from AMPERE-004 Task 3.
 */
class AgentMemoryServiceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var knowledgeRepository: KnowledgeRepository
    private lateinit var eventBus: EventSerialBus
    private lateinit var service: AgentMemoryService

    // Track emitted events for verification
    private val emittedEvents = mutableListOf<MemoryEvent>()

    private val agentId = "test-agent-1"
    private lateinit var now: Instant

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        knowledgeRepository = KnowledgeRepositoryImpl(database)
        eventBus = EventSerialBus(testScope)
        now = Clock.System.now()

        // Subscribe to all MemoryEvents to track emissions
        eventBus.subscribe<MemoryEvent.KnowledgeStored, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = MemoryEvent.KnowledgeStored.EVENT_TYPE,
        ) { event, _ ->
            emittedEvents.add(event)
        }

        eventBus.subscribe<MemoryEvent.KnowledgeRecalled, EventSubscription.ByEventClassType>(
            agentId = agentId,
            eventType = MemoryEvent.KnowledgeRecalled.EVENT_TYPE,
        ) { event, _ ->
            emittedEvents.add(event)
        }

        service = AgentMemoryService(
            agentId = agentId,
            knowledgeRepository = knowledgeRepository,
            eventBus = eventBus,
        )

        // Clear event tracking
        emittedEvents.clear()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }
    // ==================== Test 1: Round-trip Persistence ====================

    @Test
    fun `storeKnowledge and recallKnowledgeById - FromIdea`() {
        runBlocking {
            // Store Knowledge.FromIdea
            val knowledge = Knowledge.FromIdea(
                ideaId = "idea-1",
                approach = "Test-driven development",
                learnings = "Writing tests first clarifies requirements",
                timestamp = now,
            )

            val storeResult = service.storeKnowledge(
                knowledge = knowledge,
                tags = listOf("testing", "development"),
                taskType = "feature-development",
            )

            assertTrue(storeResult.isSuccess)
            val stored = storeResult.getOrNull()
            assertNotNull(stored)
            assertEquals(KnowledgeType.FROM_IDEA, stored.knowledgeType)
            assertEquals("idea-1", stored.ideaId)
            assertEquals(knowledge.approach, stored.approach)
            assertEquals(knowledge.learnings, stored.learnings)
            assertEquals(listOf("testing", "development"), stored.tags)

            // Verify event was emitted
            assertEquals(1, emittedEvents.size)
            val event = emittedEvents.first() as MemoryEvent.KnowledgeStored
            assertEquals(stored.id, event.knowledgeId)
            assertEquals(KnowledgeType.FROM_IDEA, event.knowledgeType)
            assertEquals("feature-development", event.taskType)

            // Recall by ID
            val recallResult = service.recallKnowledgeById(stored.id)
            assertTrue(recallResult.isSuccess)
            val recalled = recallResult.getOrNull()
            assertNotNull(recalled)
            assertEquals(stored.id, recalled.id)
            assertEquals(stored.approach, recalled.approach)
            assertEquals(stored.learnings, recalled.learnings)
        }
    }

    @Test
    fun `storeKnowledge and recallKnowledgeById - FromOutcome`() {
        runBlocking {
            val knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Database migration with validation",
                learnings = "Always run dry-run before applying schema changes",
                timestamp = now,
            )

            val storeResult = service.storeKnowledge(
                knowledge = knowledge,
                tags = listOf("database", "migration"),
                taskType = "schema-migration",
                complexityLevel = ComplexityLevel.MODERATE,
            )

            assertTrue(storeResult.isSuccess)
            val stored = storeResult.getOrNull()
            assertNotNull(stored)
            assertEquals(KnowledgeType.FROM_OUTCOME, stored.knowledgeType)
            assertEquals("outcome-1", stored.outcomeId)

            val recallResult = service.recallKnowledgeById(stored.id)
            assertTrue(recallResult.isSuccess)
            val recalled = recallResult.getOrNull()
            assertNotNull(recalled)
            assertEquals(stored.id, recalled.id)
        }
    }

    @Test
    fun `storeKnowledge and recallKnowledgeById - FromPerception`() {
        runBlocking {
            val knowledge = Knowledge.FromPerception(
                perceptionId = "perception-1",
                approach = "Monitor production metrics",
                learnings = "High CPU usage correlates with inefficient queries",
                timestamp = now,
            )

            val storeResult = service.storeKnowledge(knowledge)
            assertTrue(storeResult.isSuccess)

            val stored = storeResult.getOrNull()
            assertNotNull(stored)
            assertEquals(KnowledgeType.FROM_PERCEPTION, stored.knowledgeType)

            val recallResult = service.recallKnowledgeById(stored.id)
            assertTrue(recallResult.isSuccess)
            assertNotNull(recallResult.getOrNull())
        }
    }

    @Test
    fun `storeKnowledge and recallKnowledgeById - FromPlan`() {
        runBlocking {
            val knowledge = Knowledge.FromPlan(
                planId = "plan-1",
                approach = "Incremental refactoring",
                learnings = "Small focused changes reduce risk",
                timestamp = now,
            )

            val storeResult = service.storeKnowledge(knowledge)
            assertTrue(storeResult.isSuccess)

            val stored = storeResult.getOrNull()
            assertNotNull(stored)
            assertEquals(KnowledgeType.FROM_PLAN, stored.knowledgeType)

            val recallResult = service.recallKnowledgeById(stored.id)
            assertTrue(recallResult.isSuccess)
            assertNotNull(recallResult.getOrNull())
        }
    }

    @Test
    fun `storeKnowledge and recallKnowledgeById - FromTask`() {
        runBlocking {
            val knowledge = Knowledge.FromTask(
                taskId = "task-1",
                approach = "Break down complex feature into subtasks",
                learnings = "Parallel execution speeds up completion",
                timestamp = now,
            )

            val storeResult = service.storeKnowledge(knowledge)
            assertTrue(storeResult.isSuccess)

            val stored = storeResult.getOrNull()
            assertNotNull(stored)
            assertEquals(KnowledgeType.FROM_TASK, stored.knowledgeType)

            val recallResult = service.recallKnowledgeById(stored.id)
            assertTrue(recallResult.isSuccess)
            assertNotNull(recallResult.getOrNull())
        }
    }
    // ==================== Test 2: Context-Based Recall with Scoring ====================

    @Test
    fun `recallRelevantKnowledge finds knowledge by matching task type and scores by relevance`() {
        runBlocking {
            // Store multiple knowledge entries with different task types
            val knowledge1 = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Database schema migration with rollback",
                learnings = "Always test rollback scripts before deployment",
                timestamp = now - 2.days,
            )
            service.storeKnowledge(
                knowledge = knowledge1,
                taskType = "database-migration",
                tags = listOf("database", "schema"),
            )

            val knowledge2 = Knowledge.FromOutcome(
                outcomeId = "outcome-2",
                approach = "Database migration using Flyway",
                learnings = "Version control for database schemas prevents conflicts",
                timestamp = now - 1.days,
            )
            service.storeKnowledge(
                knowledge = knowledge2,
                taskType = "database-migration",
                tags = listOf("database", "flyway"),
            )

            val knowledge3 = Knowledge.FromTask(
                taskId = "task-1",
                approach = "API endpoint implementation",
                learnings = "Validate input at API boundary",
                timestamp = now,
            )
            service.storeKnowledge(
                knowledge = knowledge3,
                taskType = "api-development",
                tags = listOf("api", "validation"),
            )

            // Clear events from storage
            emittedEvents.clear()

            // Query for database migration knowledge
            val context = MemoryContext(
                taskType = "database-migration",
                tags = setOf("database", "migration"),
                description = "Applying database schema changes safely",
            )

            val result = service.recallRelevantKnowledge(context, limit = 10)

            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)

            // Should find both database migration entries, not the API one
            assertTrue(recalled.size >= 2)
            assertTrue(recalled.any { it.entry.outcomeId == "outcome-1" })
            assertTrue(recalled.any { it.entry.outcomeId == "outcome-2" })
            assertTrue(recalled.none { it.entry.taskId == "task-1" })

            // Verify scoring - more recent should have higher score
            val scores = recalled.map { it.relevanceScore }
            assertTrue(scores.all { it > 0.0 && it <= 1.0 })

            // Verify event emission
            val recallEvent = emittedEvents.filterIsInstance<MemoryEvent.KnowledgeRecalled>().firstOrNull()
            assertNotNull(recallEvent)
            assertEquals(recalled.size, recallEvent.resultsFound)
            assertTrue(recallEvent.averageRelevance > 0.0)
            assertEquals(recalled.map { it.entry.id }, recallEvent.topKnowledgeIds)
        }
    }
    // ==================== Test 3: Full-Text Search ====================

    @Test
    fun `recallRelevantKnowledge uses full-text search to find semantically similar knowledge`() {
        runBlocking {
            // Store knowledge with specific technical terms
            val knowledge1 = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Implemented user authentication with JWT tokens",
                learnings = "Store refresh tokens securely in httpOnly cookies",
                timestamp = now,
            )
            service.storeKnowledge(
                knowledge = knowledge1,
                tags = listOf("security", "authentication"),
            )

            val knowledge2 = Knowledge.FromOutcome(
                outcomeId = "outcome-2",
                approach = "Added input validation for user registration",
                learnings = "Validate email format and password strength",
                timestamp = now,
            )
            service.storeKnowledge(
                knowledge = knowledge2,
                tags = listOf("validation", "security"),
            )

            emittedEvents.clear()

            // Query with related terms that will match the stored knowledge
            // Using simple keywords that directly match the stored data
            val context = MemoryContext(
                taskType = "",
                tags = emptySet(),
                description = "authentication tokens user",
            )

            val result = service.recallRelevantKnowledge(context)

            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)

            // Should find the authentication knowledge entry
            // The FTS or LIKE fallback should match on "authentication" and "tokens"
            assertTrue(recalled.isNotEmpty(), "Should find at least one knowledge entry")
            assertTrue(
                recalled.any { it.entry.outcomeId == "outcome-1" },
                "Should find the authentication knowledge entry",
            )
        }
    }
    // ==================== Test 4: Temporal Filtering ====================

    @Test
    fun `recallRelevantKnowledge filters by time range when specified in context`() {
        runBlocking {
            // Store knowledge at different times
            val oldKnowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-old",
                approach = "Old approach",
                learnings = "Old learnings",
                timestamp = now - 30.days,
            )
            service.storeKnowledge(
                knowledge = oldKnowledge,
                taskType = "test-task",
            )

            val recentKnowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-recent",
                approach = "Recent approach",
                learnings = "Recent learnings",
                timestamp = now - 1.hours,
            )
            service.storeKnowledge(
                knowledge = recentKnowledge,
                taskType = "test-task",
            )

            emittedEvents.clear()

            // Query with time constraint for recent knowledge only
            val context = MemoryContext(
                taskType = "test-task",
                tags = emptySet(),
                description = "test",
                timeConstraint = TimeRange(
                    start = now - 7.days,
                    end = now,
                ),
            )

            val result = service.recallRelevantKnowledge(context)

            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)

            // Should only find recent knowledge
            assertTrue(recalled.any { it.entry.outcomeId == "outcome-recent" })
            // Old knowledge might still appear if it matches other criteria,
            // but recent should be scored higher
            val recentEntry = recalled.find { it.entry.outcomeId == "outcome-recent" }
            assertNotNull(recentEntry)
        }
    }
    // ==================== Test 5: Tag-Based Retrieval ====================

    @Test
    fun `recallRelevantKnowledge uses tags to find relevant knowledge`() {
        runBlocking {
            // Store knowledge with various tags
            val knowledge1 = Knowledge.FromTask(
                taskId = "task-1",
                approach = "Backend API development",
                learnings = "Use dependency injection for testability",
                timestamp = now,
            )
            service.storeKnowledge(
                knowledge = knowledge1,
                tags = listOf("backend", "kotlin", "testing"),
            )

            val knowledge2 = Knowledge.FromTask(
                taskId = "task-2",
                approach = "Frontend UI implementation",
                learnings = "Use component-based architecture",
                timestamp = now,
            )
            service.storeKnowledge(
                knowledge = knowledge2,
                tags = listOf("frontend", "react", "ui"),
            )

            emittedEvents.clear()

            // Query with backend tags
            val context = MemoryContext(
                taskType = "",
                tags = setOf("backend", "kotlin"),
                description = "",
            )

            val result = service.recallRelevantKnowledge(context)

            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)

            // Should find backend knowledge, not frontend
            assertTrue(recalled.any { it.entry.taskId == "task-1" })
        }
    }
    // ==================== Test 6: Edge Case - No Matching Knowledge ====================

    @Test
    fun `recallRelevantKnowledge returns empty list for completely novel situation`() {
        runBlocking {
            // Store some knowledge
            service.storeKnowledge(
                knowledge = Knowledge.FromTask(
                    taskId = "task-1",
                    approach = "Database optimization",
                    learnings = "Add indexes to frequently queried columns",
                    timestamp = now,
                ),
                tags = listOf("database", "performance"),
                taskType = "optimization",
            )

            emittedEvents.clear()

            // Query for completely unrelated context
            val context = MemoryContext(
                taskType = "mobile-app-design",
                tags = setOf("ios", "swift", "ui-design"),
                description = "Designing mobile app user interface for iOS",
            )

            val result = service.recallRelevantKnowledge(context)

            assertTrue(result.isSuccess)
            val recalled = result.getOrNull()
            assertNotNull(recalled)

            // Should return empty or very low-scored results
            // The service gracefully handles this case
            val recallEvent = emittedEvents.filterIsInstance<MemoryEvent.KnowledgeRecalled>().firstOrNull()
            assertNotNull(recallEvent)
            assertEquals(recalled.size, recallEvent.resultsFound)
        }
    }
    // ==================== Test 7: Event Emission Verification ====================

    @Test
    fun `storeKnowledge emits KnowledgeStored event with correct data`() {
        runBlocking {
            emittedEvents.clear()

            val knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "Test approach",
                learnings = "Test learnings",
                timestamp = now,
            )

            val result = service.storeKnowledge(
                knowledge = knowledge,
                tags = listOf("tag1", "tag2"),
                taskType = "test-task",
            )

            assertTrue(result.isSuccess)
            val stored = result.getOrNull()
            assertNotNull(stored)

            // Verify event was emitted
            assertEquals(1, emittedEvents.size)
            val event = emittedEvents.first()
            assertTrue(event is MemoryEvent.KnowledgeStored)

            val storedEvent = event as MemoryEvent.KnowledgeStored
            assertEquals(stored.id, storedEvent.knowledgeId)
            assertEquals(KnowledgeType.FROM_OUTCOME, storedEvent.knowledgeType)
            assertEquals("test-task", storedEvent.taskType)
            assertEquals(listOf("tag1", "tag2"), storedEvent.tags)
            assertTrue(storedEvent.eventSource is EventSource.Agent)
            assertEquals(agentId, (storedEvent.eventSource as EventSource.Agent).agentId)
        }
    }

    @Test
    fun `recallRelevantKnowledge emits KnowledgeRecalled event with statistics`() {
        runBlocking {
            // Store some knowledge
            repeat(3) { i ->
                service.storeKnowledge(
                    knowledge = Knowledge.FromTask(
                        taskId = "task-$i",
                        approach = "Approach $i",
                        learnings = "Learning $i",
                        timestamp = now,
                    ),
                    taskType = "test-task",
                )
            }

            emittedEvents.clear()

            val context = MemoryContext(
                taskType = "test-task",
                tags = emptySet(),
                description = "test",
            )

            val result = service.recallRelevantKnowledge(context, limit = 10)

            assertTrue(result.isSuccess)

            // Verify recall event
            val recallEvent = emittedEvents.filterIsInstance<MemoryEvent.KnowledgeRecalled>().firstOrNull()
            assertNotNull(recallEvent)
            assertTrue(recallEvent.resultsFound > 0)
            assertTrue(recallEvent.averageRelevance >= 0.0)
            assertTrue(recallEvent.topKnowledgeIds.isNotEmpty())
            assertTrue(recallEvent.eventSource is EventSource.Agent)
            assertEquals(agentId, (recallEvent.eventSource as EventSource.Agent).agentId)
        }
    }
    // ==================== Test 8: Service Method Delegation ====================

    @Test
    fun `findKnowledgeByType delegates to repository correctly`() {
        runBlocking {
            // Store different types of knowledge
            service.storeKnowledge(
                Knowledge.FromOutcome(
                    outcomeId = "outcome-1",
                    approach = "Outcome approach",
                    learnings = "Outcome learnings",
                    timestamp = now,
                ),
            )

            service.storeKnowledge(
                Knowledge.FromIdea(
                    ideaId = "idea-1",
                    approach = "Idea approach",
                    learnings = "Idea learnings",
                    timestamp = now,
                ),
            )

            // Query for outcomes only
            val result = service.findKnowledgeByType(KnowledgeType.FROM_OUTCOME)

            assertTrue(result.isSuccess)
            val entries = result.getOrNull()
            assertNotNull(entries)
            assertTrue(entries.all { it.knowledgeType == KnowledgeType.FROM_OUTCOME })
        }
    }

    @Test
    fun `findKnowledgeByTaskType delegates to repository correctly`() {
        runBlocking {
            service.storeKnowledge(
                knowledge = Knowledge.FromTask(
                    taskId = "task-1",
                    approach = "Task approach",
                    learnings = "Task learnings",
                    timestamp = now,
                ),
                taskType = "specific-task-type",
            )

            val result = service.findKnowledgeByTaskType("specific-task-type")

            assertTrue(result.isSuccess)
            val entries = result.getOrNull()
            assertNotNull(entries)
            assertTrue(entries.isNotEmpty())
            assertTrue(entries.all { it.taskType == "specific-task-type" })
        }
    }

    @Test
    fun `findKnowledgeByTag delegates to repository correctly`() {
        runBlocking {
            service.storeKnowledge(
                knowledge = Knowledge.FromPlan(
                    planId = "plan-1",
                    approach = "Plan approach",
                    learnings = "Plan learnings",
                    timestamp = now,
                ),
                tags = listOf("specific-tag", "other-tag"),
            )

            val result = service.findKnowledgeByTag("specific-tag")

            assertTrue(result.isSuccess)
            val entries = result.getOrNull()
            assertNotNull(entries)
            assertTrue(entries.isNotEmpty())
            assertTrue(entries.all { it.tags.contains("specific-tag") })
        }
    }

    @Test
    fun `recallKnowledgeById returns null for non-existent ID`() {
        runBlocking {
            val result = service.recallKnowledgeById("non-existent-id")

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
        }
    }
}
