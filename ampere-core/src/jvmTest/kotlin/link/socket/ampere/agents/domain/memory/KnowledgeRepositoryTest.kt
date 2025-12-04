package link.socket.ampere.agents.domain.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeType
import link.socket.ampere.db.Database
import kotlin.time.Duration.Companion.days

/**
 * Comprehensive test suite for KnowledgeRepository.
 *
 * Tests cover the complete lifecycle of knowledge storage and retrieval,
 * including all five Knowledge subtypes, semantic search, tag filtering,
 * temporal queries, and multi-dimensional context searching.
 *
 * Validates the requirements from AMPERE-004 Task 2.
 */
class KnowledgeRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: KnowledgeRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        repo = KnowledgeRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Helper Methods ====================

    private val now = Clock.System.now()

    private fun createKnowledgeFromIdea(
        ideaId: String = "idea-1",
        approach: String = "Test-driven development approach",
        learnings: String = "Writing tests first clarifies requirements and design",
        timestamp: Instant = now,
    ): Knowledge.FromIdea {
        return Knowledge.FromIdea(
            ideaId = ideaId,
            approach = approach,
            learnings = learnings,
            timestamp = timestamp,
        )
    }

    private fun createKnowledgeFromOutcome(
        outcomeId: String = "outcome-1",
        approach: String = "Database migration with schema validation",
        learnings: String = "Always run dry-run validation before applying schema changes",
        timestamp: Instant = now,
    ): Knowledge.FromOutcome {
        return Knowledge.FromOutcome(
            outcomeId = outcomeId,
            approach = approach,
            learnings = learnings,
            timestamp = timestamp,
        )
    }

    private fun createKnowledgeFromPerception(
        perceptionId: String = "perception-1",
        approach: String = "Monitoring production metrics",
        learnings: String = "High CPU usage correlates with inefficient database queries",
        timestamp: Instant = now,
    ): Knowledge.FromPerception {
        return Knowledge.FromPerception(
            perceptionId = perceptionId,
            approach = approach,
            learnings = learnings,
            timestamp = timestamp,
        )
    }

    private fun createKnowledgeFromPlan(
        planId: String = "plan-1",
        approach: String = "Incremental refactoring strategy",
        learnings: String = "Small, focused changes reduce risk and ease review",
        timestamp: Instant = now,
    ): Knowledge.FromPlan {
        return Knowledge.FromPlan(
            planId = planId,
            approach = approach,
            learnings = learnings,
            timestamp = timestamp,
        )
    }

    private fun createKnowledgeFromTask(
        taskId: String = "task-1",
        approach: String = "Implementing input validation",
        learnings: String = "Validate at boundaries, trust internal code",
        timestamp: Instant = now,
    ): Knowledge.FromTask {
        return Knowledge.FromTask(
            taskId = taskId,
            approach = approach,
            learnings = learnings,
            timestamp = timestamp,
        )
    }

    // =============================================================================
    // REQUIREMENT 1: Insert and retrieve all five Knowledge subtypes by ID
    // =============================================================================

    @Test
    fun `storeKnowledge successfully stores and retrieves FromIdea knowledge`() = runBlocking {
        val knowledge = createKnowledgeFromIdea()

        val result = repo.storeKnowledge(
            knowledge = knowledge,
            tags = listOf("testing", "development"),
            taskType = "code-quality",
            complexityLevel = "SIMPLE",
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(KnowledgeType.FROM_IDEA, stored.knowledgeType)
        assertEquals(knowledge.approach, stored.approach)
        assertEquals(knowledge.learnings, stored.learnings)
        assertEquals(knowledge.ideaId, stored.ideaId)
        assertNull(stored.outcomeId)
        assertNull(stored.perceptionId)
        assertNull(stored.planId)
        assertNull(stored.taskId)

        // Verify retrieval by ID
        val retrieved = repo.getKnowledgeById(stored.id).getOrNull()
        assertNotNull(retrieved)
        assertEquals(stored.id, retrieved.id)
        assertEquals(KnowledgeType.FROM_IDEA, retrieved.knowledgeType)
    }

    @Test
    fun `storeKnowledge successfully stores and retrieves FromOutcome knowledge`() = runBlocking {
        val knowledge = createKnowledgeFromOutcome()

        val result = repo.storeKnowledge(
            knowledge = knowledge,
            tags = listOf("database", "migration"),
            taskType = "database-migration",
            complexityLevel = "COMPLEX",
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(KnowledgeType.FROM_OUTCOME, stored.knowledgeType)
        assertEquals(knowledge.outcomeId, stored.outcomeId)
        assertNull(stored.ideaId)

        // Verify retrieval by ID
        val retrieved = repo.getKnowledgeById(stored.id).getOrNull()
        assertNotNull(retrieved)
        assertEquals(KnowledgeType.FROM_OUTCOME, retrieved.knowledgeType)
    }

    @Test
    fun `storeKnowledge successfully stores and retrieves FromPerception knowledge`() = runBlocking {
        val knowledge = createKnowledgeFromPerception()

        val result = repo.storeKnowledge(
            knowledge = knowledge,
            tags = listOf("monitoring", "performance"),
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(KnowledgeType.FROM_PERCEPTION, stored.knowledgeType)
        assertEquals(knowledge.perceptionId, stored.perceptionId)
        assertNull(stored.ideaId)
        assertNull(stored.outcomeId)

        // Verify retrieval by ID
        val retrieved = repo.getKnowledgeById(stored.id).getOrNull()
        assertNotNull(retrieved)
        assertEquals(KnowledgeType.FROM_PERCEPTION, retrieved.knowledgeType)
    }

    @Test
    fun `storeKnowledge successfully stores and retrieves FromPlan knowledge`() = runBlocking {
        val knowledge = createKnowledgeFromPlan()

        val result = repo.storeKnowledge(
            knowledge = knowledge,
            tags = listOf("refactoring", "planning"),
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(KnowledgeType.FROM_PLAN, stored.knowledgeType)
        assertEquals(knowledge.planId, stored.planId)
        assertNull(stored.ideaId)
        assertNull(stored.outcomeId)
        assertNull(stored.perceptionId)

        // Verify retrieval by ID
        val retrieved = repo.getKnowledgeById(stored.id).getOrNull()
        assertNotNull(retrieved)
        assertEquals(KnowledgeType.FROM_PLAN, retrieved.knowledgeType)
    }

    @Test
    fun `storeKnowledge successfully stores and retrieves FromTask knowledge`() = runBlocking {
        val knowledge = createKnowledgeFromTask()

        val result = repo.storeKnowledge(
            knowledge = knowledge,
            tags = listOf("validation", "security"),
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(KnowledgeType.FROM_TASK, stored.knowledgeType)
        assertEquals(knowledge.taskId, stored.taskId)
        assertNull(stored.ideaId)
        assertNull(stored.outcomeId)
        assertNull(stored.perceptionId)
        assertNull(stored.planId)

        // Verify retrieval by ID
        val retrieved = repo.getKnowledgeById(stored.id).getOrNull()
        assertNotNull(retrieved)
        assertEquals(KnowledgeType.FROM_TASK, retrieved.knowledgeType)
    }

    @Test
    fun `getKnowledgeById returns null for non-existent ID`() = runBlocking {
        val result = repo.getKnowledgeById("non-existent-id")

        assertTrue(result.isSuccess)
        val knowledge = result.getOrNull()
        assertNull(knowledge)
    }

    // =============================================================================
    // REQUIREMENT 2: Query knowledge with tags and verify tag relationships
    // =============================================================================

    @Test
    fun `storeKnowledge with tags and retrieve by tag`() = runBlocking {
        val knowledge = createKnowledgeFromOutcome()
        val tags = listOf("database", "migration", "validation")

        val storeResult = repo.storeKnowledge(
            knowledge = knowledge,
            tags = tags,
        )

        assertTrue(storeResult.isSuccess)
        val stored = storeResult.getOrNull()
        assertNotNull(stored)

        // Retrieve by tag
        val byTagResult = repo.findKnowledgeByTag("migration", limit = 10)
        assertTrue(byTagResult.isSuccess)
        val byTag = byTagResult.getOrNull()
        assertNotNull(byTag)
        assertTrue(byTag.isNotEmpty())
        assertTrue(byTag.any { it.id == stored.id })

        // Verify all tags are stored
        val tagsResult = repo.getTagsForKnowledge(stored.id)
        assertTrue(tagsResult.isSuccess)
        val retrievedTags = tagsResult.getOrNull()
        assertNotNull(retrievedTags)
        assertEquals(tags.size, retrievedTags.size)
        assertTrue(retrievedTags.containsAll(tags))
    }

    @Test
    fun `findKnowledgeByTags returns entries matching any tag (OR matching)`() = runBlocking {
        val knowledge1 = createKnowledgeFromOutcome(outcomeId = "outcome-1", approach = "Database migration")
        val knowledge2 = createKnowledgeFromTask(taskId = "task-1", approach = "Input validation")
        val knowledge3 = createKnowledgeFromIdea(ideaId = "idea-1", approach = "Performance optimization")

        repo.storeKnowledge(knowledge1, tags = listOf("database", "migration"))
        repo.storeKnowledge(knowledge2, tags = listOf("validation", "security"))
        repo.storeKnowledge(knowledge3, tags = listOf("performance", "optimization"))

        // Search for entries with either "validation" or "performance"
        val result = repo.findKnowledgeByTags(listOf("validation", "performance"), limit = 10)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertTrue(entries.size >= 2)

        // Should find both knowledge2 and knowledge3
        val approaches = entries.map { it.approach }
        assertTrue(approaches.any { it.contains("validation") })
        assertTrue(approaches.any { it.contains("Performance") })
    }

    // =============================================================================
    // REQUIREMENT 3: Temporal queries with timestamp ranges
    // =============================================================================

    @Test
    fun `findKnowledgeByTimeRange returns only entries within range`() = runBlocking {
        val baseTime = now
        val knowledge1 = createKnowledgeFromOutcome(outcomeId = "old-1", timestamp = baseTime.minus(10.days))
        val knowledge2 = createKnowledgeFromOutcome(outcomeId = "recent-1", timestamp = baseTime.minus(2.days))
        val knowledge3 = createKnowledgeFromOutcome(outcomeId = "recent-2", timestamp = baseTime.minus(1.days))
        val knowledge4 = createKnowledgeFromOutcome(outcomeId = "future-1", timestamp = baseTime.plus(1.days))

        repo.storeKnowledge(knowledge1)
        repo.storeKnowledge(knowledge2)
        repo.storeKnowledge(knowledge3)
        repo.storeKnowledge(knowledge4)

        // Query for entries in the last 3 days
        val fromTime = baseTime.minus(3.days)
        val toTime = baseTime
        val result = repo.findKnowledgeByTimeRange(fromTime, toTime)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertEquals(2, entries.size) // Should find knowledge2 and knowledge3

        // Verify they're in reverse chronological order (most recent first)
        assertTrue(entries[0].timestamp > entries[1].timestamp)
        assertEquals(knowledge3.outcomeId, entries[0].outcomeId)
        assertEquals(knowledge2.outcomeId, entries[1].outcomeId)
    }

    @Test
    fun `findKnowledgeByTimeRange returns empty for range with no entries`() = runBlocking {
        val knowledge = createKnowledgeFromOutcome(timestamp = now)
        repo.storeKnowledge(knowledge)

        // Query a range far in the past
        val fromTime = now.minus(30.days)
        val toTime = now.minus(20.days)
        val result = repo.findKnowledgeByTimeRange(fromTime, toTime)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertTrue(entries.isEmpty())
    }

    // =============================================================================
    // REQUIREMENT 4: Full-text search for specific keywords
    // =============================================================================

    @Test
    fun `findSimilarKnowledge finds entries with keywords in approach or learnings`() = runBlocking {
        val knowledge1 = createKnowledgeFromOutcome(
            approach = "Database schema migration with foreign key constraints",
            learnings = "Always validate foreign key references before migration",
        )
        val knowledge2 = createKnowledgeFromOutcome(
            approach = "Implementing input validation for user forms",
            learnings = "Schema validation prevents invalid data entry",
        )
        val knowledge3 = createKnowledgeFromOutcome(
            approach = "Performance optimization for API endpoints",
            learnings = "Caching frequently accessed data reduces load times",
        )

        repo.storeKnowledge(knowledge1)
        repo.storeKnowledge(knowledge2)
        repo.storeKnowledge(knowledge3)

        // Search for "schema validation"
        val result = repo.findSimilarKnowledge("schema validation", limit = 10)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertTrue(entries.size >= 2)

        // Should find knowledge1 and knowledge2 (both mention schema/validation)
        val texts = entries.flatMap { listOf(it.approach, it.learnings) }
        assertTrue(texts.any { it.contains("schema", ignoreCase = true) })
        assertTrue(texts.any { it.contains("validation", ignoreCase = true) })
    }

    @Test
    fun `findSimilarKnowledge handles multiple keywords`() = runBlocking {
        val knowledge1 = createKnowledgeFromOutcome(
            approach = "Concurrent access handling with locks",
            learnings = "Race conditions occur when multiple threads modify shared state",
        )
        val knowledge2 = createKnowledgeFromOutcome(
            approach = "Thread-safe data structures",
            learnings = "Use synchronized collections for concurrent modifications",
        )

        repo.storeKnowledge(knowledge1)
        repo.storeKnowledge(knowledge2)

        // Search for "race condition concurrent"
        val result = repo.findSimilarKnowledge("race condition concurrent", limit = 10)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun `findSimilarKnowledge returns empty for non-matching keywords`() = runBlocking {
        val knowledge = createKnowledgeFromOutcome(
            approach = "Database migration",
            learnings = "Schema validation is important",
        )
        repo.storeKnowledge(knowledge)

        val result = repo.findSimilarKnowledge("nonexistent keyword xyz", limit = 10)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertTrue(entries.isEmpty())
    }

    // =============================================================================
    // REQUIREMENT 5: Combined context search with multiple filters
    // =============================================================================

    @Test
    fun `searchKnowledgeByContext combines type, task type, tag, and time filters`() = runBlocking {
        val baseTime = now

        // Create diverse knowledge entries
        val knowledge1 = createKnowledgeFromOutcome(
            outcomeId = "outcome-db-1",
            approach = "Database migration",
            timestamp = baseTime.minus(1.days),
        )
        repo.storeKnowledge(
            knowledge1,
            tags = listOf("database", "migration"),
            taskType = "database-task",
            complexityLevel = "COMPLEX",
        )

        val knowledge2 = createKnowledgeFromOutcome(
            outcomeId = "outcome-db-2",
            approach = "Database optimization",
            timestamp = baseTime.minus(2.days),
        )
        repo.storeKnowledge(
            knowledge2,
            tags = listOf("database", "performance"),
            taskType = "database-task",
            complexityLevel = "MODERATE",
        )

        val knowledge3 = createKnowledgeFromIdea(
            ideaId = "idea-1",
            approach = "API design principles",
            timestamp = baseTime.minus(1.days),
        )
        repo.storeKnowledge(
            knowledge3,
            tags = listOf("api", "design"),
            taskType = "api-task",
            complexityLevel = "SIMPLE",
        )

        val knowledge4 = createKnowledgeFromTask(
            taskId = "task-1",
            approach = "Database refactoring",
            timestamp = baseTime.minus(10.days), // Outside time range
        )
        repo.storeKnowledge(
            knowledge4,
            tags = listOf("database", "refactoring"),
            taskType = "database-task",
            complexityLevel = "COMPLEX",
        )

        // Search: FROM_OUTCOME type, database-task type, with "database" or "migration" tags, in last 3 days
        val result = repo.searchKnowledgeByContext(
            knowledgeType = KnowledgeType.FROM_OUTCOME,
            taskType = "database-task",
            tags = listOf("database", "migration"),
            fromTimestamp = baseTime.minus(3.days),
            toTimestamp = baseTime,
            limit = 10,
        )

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)

        // Should find knowledge1 and knowledge2 (both FROM_OUTCOME, database-task, within time range)
        // Should NOT find knowledge3 (wrong type and task type) or knowledge4 (outside time range)
        assertTrue(entries.size >= 1)
        assertTrue(entries.all { it.knowledgeType == KnowledgeType.FROM_OUTCOME })
        assertTrue(entries.all { it.taskType == "database-task" })
        assertTrue(entries.all { it.timestamp >= baseTime.minus(3.days) })
    }

    @Test
    fun `searchKnowledgeByContext with null filters returns all entries`() = runBlocking {
        val knowledge1 = createKnowledgeFromOutcome()
        val knowledge2 = createKnowledgeFromIdea()
        val knowledge3 = createKnowledgeFromTask()

        repo.storeKnowledge(knowledge1)
        repo.storeKnowledge(knowledge2)
        repo.storeKnowledge(knowledge3)

        // Search with all null filters
        val result = repo.searchKnowledgeByContext(
            knowledgeType = null,
            taskType = null,
            tags = null,
            complexityLevel = null,
            fromTimestamp = null,
            toTimestamp = null,
            limit = 100,
        )

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertTrue(entries.size >= 3)
    }

    // =============================================================================
    // REQUIREMENT 6: Knowledge entry with no tags
    // =============================================================================

    @Test
    fun `storeKnowledge with no tags succeeds and returns empty tag list`() = runBlocking {
        val knowledge = createKnowledgeFromOutcome()

        val storeResult = repo.storeKnowledge(
            knowledge = knowledge,
            tags = emptyList(),
        )

        assertTrue(storeResult.isSuccess)
        val stored = storeResult.getOrNull()
        assertNotNull(stored)

        // Verify entry can be retrieved by ID
        val retrievedResult = repo.getKnowledgeById(stored.id)
        assertTrue(retrievedResult.isSuccess)
        val retrieved = retrievedResult.getOrNull()
        assertNotNull(retrieved)

        // Verify tags are empty
        val tagsResult = repo.getTagsForKnowledge(stored.id)
        assertTrue(tagsResult.isSuccess)
        val tags = tagsResult.getOrNull()
        assertNotNull(tags)
        assertTrue(tags.isEmpty())
    }

    // =============================================================================
    // ADDITIONAL TESTS: Query by type, task type, complexity
    // =============================================================================

    @Test
    fun `findKnowledgeByType returns only entries of specified type`() = runBlocking {
        val knowledge1 = createKnowledgeFromOutcome(outcomeId = "outcome-1")
        val knowledge2 = createKnowledgeFromOutcome(outcomeId = "outcome-2")
        val knowledge3 = createKnowledgeFromIdea(ideaId = "idea-1")
        val knowledge4 = createKnowledgeFromTask(taskId = "task-1")

        repo.storeKnowledge(knowledge1)
        repo.storeKnowledge(knowledge2)
        repo.storeKnowledge(knowledge3)
        repo.storeKnowledge(knowledge4)

        // Query for FROM_OUTCOME only
        val result = repo.findKnowledgeByType(KnowledgeType.FROM_OUTCOME, limit = 10)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.knowledgeType == KnowledgeType.FROM_OUTCOME })
    }

    @Test
    fun `findKnowledgeByTaskType returns only entries with matching task type`() = runBlocking {
        val knowledge1 = createKnowledgeFromOutcome()
        val knowledge2 = createKnowledgeFromOutcome()
        val knowledge3 = createKnowledgeFromOutcome()

        repo.storeKnowledge(knowledge1, taskType = "database-migration")
        repo.storeKnowledge(knowledge2, taskType = "database-migration")
        repo.storeKnowledge(knowledge3, taskType = "api-development")

        val result = repo.findKnowledgeByTaskType("database-migration", limit = 10)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.taskType == "database-migration" })
    }

    @Test
    fun `searchKnowledgeByContext filters by complexity level`() = runBlocking {
        val knowledge1 = createKnowledgeFromOutcome()
        val knowledge2 = createKnowledgeFromOutcome()
        val knowledge3 = createKnowledgeFromOutcome()

        repo.storeKnowledge(knowledge1, complexityLevel = "COMPLEX")
        repo.storeKnowledge(knowledge2, complexityLevel = "COMPLEX")
        repo.storeKnowledge(knowledge3, complexityLevel = "SIMPLE")

        val result = repo.searchKnowledgeByContext(
            complexityLevel = "COMPLEX",
            limit = 10,
        )

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.complexityLevel == "COMPLEX" })
    }

    // =============================================================================
    // INTEGRATION TESTS: Complete workflows
    // =============================================================================

    @Test
    fun `complete workflow - store diverse knowledge and query by different criteria`() = runBlocking {
        val baseTime = now

        // Store knowledge from different sources with different characteristics
        val knowledge1 = createKnowledgeFromOutcome(
            outcomeId = "migration-success",
            approach = "Database schema migration with validation",
            learnings = "Always validate schema before applying changes",
            timestamp = baseTime.minus(1.days),
        )
        repo.storeKnowledge(
            knowledge1,
            tags = listOf("database", "migration", "success"),
            taskType = "database-migration",
            complexityLevel = "COMPLEX",
        )

        val knowledge2 = createKnowledgeFromOutcome(
            outcomeId = "migration-failure",
            approach = "Database migration without dry-run",
            learnings = "Skipping dry-run caused data loss",
            timestamp = baseTime.minus(2.days),
        )
        repo.storeKnowledge(
            knowledge2,
            tags = listOf("database", "migration", "failure"),
            taskType = "database-migration",
            complexityLevel = "COMPLEX",
        )

        val knowledge3 = createKnowledgeFromIdea(
            ideaId = "test-first",
            approach = "Test-driven development",
            learnings = "Writing tests first improves design",
            timestamp = baseTime.minus(1.days),
        )
        repo.storeKnowledge(
            knowledge3,
            tags = listOf("testing", "development"),
            taskType = "development-practice",
            complexityLevel = "MODERATE",
        )

        // Query 1: All migration-related knowledge
        val migrationResult = repo.findKnowledgeByTag("migration", limit = 10)
        assertTrue(migrationResult.isSuccess)
        val migrationEntries = migrationResult.getOrNull()
        assertNotNull(migrationEntries)
        assertEquals(2, migrationEntries.size)

        // Query 2: Successful outcomes only
        val successResult = repo.findKnowledgeByTag("success", limit = 10)
        assertTrue(successResult.isSuccess)
        val successEntries = successResult.getOrNull()
        assertNotNull(successEntries)
        assertEquals(1, successEntries.size)

        // Query 3: Search for "schema validation"
        val searchResult = repo.findSimilarKnowledge("schema validation", limit = 10)
        assertTrue(searchResult.isSuccess)
        val searchEntries = searchResult.getOrNull()
        assertNotNull(searchEntries)
        assertTrue(searchEntries.isNotEmpty())

        // Query 4: Complex database tasks in last 3 days
        val contextResult = repo.searchKnowledgeByContext(
            knowledgeType = KnowledgeType.FROM_OUTCOME,
            taskType = "database-migration",
            complexityLevel = "COMPLEX",
            fromTimestamp = baseTime.minus(3.days),
            toTimestamp = baseTime,
            limit = 10,
        )
        assertTrue(contextResult.isSuccess)
        val contextEntries = contextResult.getOrNull()
        assertNotNull(contextEntries)
        assertEquals(2, contextEntries.size)
    }

    @Test
    fun `knowledge entries are ordered by timestamp descending (most recent first)`() = runBlocking {
        val baseTime = now
        val knowledge1 = createKnowledgeFromOutcome(outcomeId = "old", timestamp = baseTime.minus(3.days))
        val knowledge2 = createKnowledgeFromOutcome(outcomeId = "middle", timestamp = baseTime.minus(2.days))
        val knowledge3 = createKnowledgeFromOutcome(outcomeId = "recent", timestamp = baseTime.minus(1.days))

        repo.storeKnowledge(knowledge1)
        repo.storeKnowledge(knowledge2)
        repo.storeKnowledge(knowledge3)

        val result = repo.findKnowledgeByType(KnowledgeType.FROM_OUTCOME, limit = 10)

        assertTrue(result.isSuccess)
        val entries = result.getOrNull()
        assertNotNull(entries)
        assertEquals(3, entries.size)

        // Verify descending order (most recent first)
        assertTrue(entries[0].timestamp > entries[1].timestamp)
        assertTrue(entries[1].timestamp > entries[2].timestamp)
        assertEquals("recent", entries[0].outcomeId)
        assertEquals("middle", entries[1].outcomeId)
        assertEquals("old", entries[2].outcomeId)
    }
}
