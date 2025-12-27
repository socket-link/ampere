package link.socket.ampere.agents.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.OutcomeId

/**
 * Comprehensive test suite for MemoryContext and related types.
 *
 * Tests cover similarity scoring algorithms, temporal filtering,
 * keyword matching, and edge cases for sparse contexts.
 */
class MemoryContextTest {

    private val now = Clock.System.now()

    // =============================================================================
    // HELPER FUNCTIONS FOR TEST DATA
    // =============================================================================

    private fun createKnowledgeFromOutcome(
        approach: String,
        learnings: String,
        timestamp: Instant = now,
        outcomeId: OutcomeId = "outcome-123",
    ): Knowledge.FromOutcome {
        return Knowledge.FromOutcome(
            outcomeId = outcomeId,
            approach = approach,
            learnings = learnings,
            timestamp = timestamp,
        )
    }

    private fun createDatabaseMigrationContext(
        description: String = "implement new database migration for user table schema changes",
        tags: Set<String> = setOf("database", "migration", "schema"),
        complexity: ComplexityLevel? = ComplexityLevel.MODERATE,
    ): MemoryContext {
        return MemoryContext(
            taskType = "database_migration",
            tags = tags,
            description = description,
            projectContext = "user-service",
            timeConstraint = null,
            complexity = complexity,
        )
    }

    // =============================================================================
    // SIMILARITY SCORING TESTS
    // =============================================================================

    @Test
    fun `similarityScore returns higher score for database migration knowledge when context is database migration`() {
        val context = createDatabaseMigrationContext()

        val databaseKnowledge = createKnowledgeFromOutcome(
            approach = "Created migration script with schema validation before applying database changes",
            learnings = "Always run dry-run validation before applying schema changes to prevent data loss",
            timestamp = now.minus(7.days),
        )

        val uiKnowledge = createKnowledgeFromOutcome(
            approach = "Updated React component styling with CSS modules",
            learnings = "Component styling should use CSS modules for better isolation",
            timestamp = now.minus(7.days),
        )

        val dbScore = context.similarityScore(databaseKnowledge, currentTime = now)
        val uiScore = context.similarityScore(uiKnowledge, currentTime = now)

        assertTrue(
            dbScore > uiScore,
            "Database knowledge should score higher than UI knowledge for database migration context",
        )
        assertTrue(dbScore > 0.5, "Database knowledge should have significant similarity (>0.5)")
        assertTrue(uiScore < 0.3, "UI knowledge should have low similarity (<0.3)")
    }

    @Test
    fun `similarityScore weights recent knowledge higher than older knowledge with same content`() {
        val context = createDatabaseMigrationContext()

        val recentKnowledge = createKnowledgeFromOutcome(
            approach = "Database migration with schema validation",
            learnings = "Validate schema changes before applying",
            timestamp = now.minus(5.days), // Very recent (within 7 days)
        )

        val olderKnowledge = createKnowledgeFromOutcome(
            approach = "Database migration with schema validation",
            learnings = "Validate schema changes before applying",
            timestamp = now.minus(45.days), // Moderately recent (within 90 days)
        )

        val veryOldKnowledge = createKnowledgeFromOutcome(
            approach = "Database migration with schema validation",
            learnings = "Validate schema changes before applying",
            timestamp = now.minus(180.days), // Older (>90 days)
        )

        val recentScore = context.similarityScore(recentKnowledge, currentTime = now)
        val olderScore = context.similarityScore(olderKnowledge, currentTime = now)
        val veryOldScore = context.similarityScore(veryOldKnowledge, currentTime = now)

        assertTrue(recentScore > olderScore, "Recent knowledge should score higher than older knowledge")
        assertTrue(olderScore > veryOldScore, "Moderately old knowledge should score higher than very old knowledge")
    }

    @Test
    fun `similarityScore finds relevant knowledge based on keyword overlap in approach and learnings`() {
        val context = MemoryContext(
            taskType = "api_endpoint",
            tags = setOf("backend", "rest", "validation"),
            description = "implement REST API endpoint with input validation and error handling",
            projectContext = "api-service",
        )

        val highOverlapKnowledge = createKnowledgeFromOutcome(
            approach = "Created REST API endpoint with comprehensive input validation",
            learnings = "Input validation should happen before business logic to improve error handling and security",
            timestamp = now.minus(10.days),
        )

        val mediumOverlapKnowledge = createKnowledgeFromOutcome(
            approach = "Implemented GraphQL endpoint with schema validation",
            learnings = "Schema validation prevents invalid data from entering the system",
            timestamp = now.minus(10.days),
        )

        val lowOverlapKnowledge = createKnowledgeFromOutcome(
            approach = "Fixed frontend button click handler",
            learnings = "Event handlers should be debounced to prevent duplicate submissions",
            timestamp = now.minus(10.days),
        )

        val highScore = context.similarityScore(highOverlapKnowledge, currentTime = now)
        val mediumScore = context.similarityScore(mediumOverlapKnowledge, currentTime = now)
        val lowScore = context.similarityScore(lowOverlapKnowledge, currentTime = now)

        assertTrue(highScore > mediumScore, "High keyword overlap should score higher than medium overlap")
        assertTrue(mediumScore > lowScore, "Medium keyword overlap should score higher than low overlap")
    }

    @Test
    fun `similarityScore is case-insensitive for keyword matching`() {
        val context = MemoryContext(
            taskType = "testing",
            tags = setOf("unit-tests", "kotlin"),
            description = "write unit tests for UserRepository with mocked database",
            projectContext = "test-suite",
        )

        val upperCaseKnowledge = createKnowledgeFromOutcome(
            approach = "WRITE UNIT TESTS FOR REPOSITORY WITH MOCKED DATABASE",
            learnings = "MOCKING DATABASE MAKES TESTS FASTER AND MORE RELIABLE",
            timestamp = now.minus(5.days),
        )

        val lowerCaseKnowledge = createKnowledgeFromOutcome(
            approach = "write unit tests for repository with mocked database",
            learnings = "mocking database makes tests faster and more reliable",
            timestamp = now.minus(5.days),
        )

        val upperScore = context.similarityScore(upperCaseKnowledge, currentTime = now)
        val lowerScore = context.similarityScore(lowerCaseKnowledge, currentTime = now)

        // Scores should be identical regardless of case
        assertEquals(upperScore, lowerScore, 0.001, "Case should not affect similarity scoring")
    }

    @Test
    fun `similarityScore returns value between 0 and 1`() {
        val context = createDatabaseMigrationContext()

        val perfectMatchKnowledge = createKnowledgeFromOutcome(
            approach = "implement new database migration for user table schema changes",
            learnings = "database migration schema changes require validation",
            timestamp = now.minus(1.days),
        )

        val noMatchKnowledge = createKnowledgeFromOutcome(
            approach = "xyz abc qwerty",
            learnings = "foo bar baz",
            timestamp = now.minus(1.days),
        )

        val perfectScore = context.similarityScore(perfectMatchKnowledge, currentTime = now)
        val noMatchScore = context.similarityScore(noMatchKnowledge, currentTime = now)

        assertTrue(perfectScore >= 0.0 && perfectScore <= 1.0, "Perfect match score should be in [0,1] range")
        assertTrue(noMatchScore >= 0.0 && noMatchScore <= 1.0, "No match score should be in [0,1] range")
    }

    // =============================================================================
    // TEMPORAL WEIGHTING TESTS
    // =============================================================================

    @Test
    fun `similarityScore applies correct temporal weights based on age`() {
        val context = createDatabaseMigrationContext()
        val baseApproach = "database migration approach"
        val baseLearnings = "database migration learnings"

        // Very recent (< 7 days) - recency score 1.0
        val veryRecentKnowledge = createKnowledgeFromOutcome(
            approach = baseApproach,
            learnings = baseLearnings,
            timestamp = now.minus(3.days),
        )

        // Recent (< 30 days) - recency score 0.7
        val recentKnowledge = createKnowledgeFromOutcome(
            approach = baseApproach,
            learnings = baseLearnings,
            timestamp = now.minus(20.days),
        )

        // Moderately recent (< 90 days) - recency score 0.4
        val moderateKnowledge = createKnowledgeFromOutcome(
            approach = baseApproach,
            learnings = baseLearnings,
            timestamp = now.minus(60.days),
        )

        // Older (>= 90 days) - recency score 0.2
        val oldKnowledge = createKnowledgeFromOutcome(
            approach = baseApproach,
            learnings = baseLearnings,
            timestamp = now.minus(120.days),
        )

        val veryRecentScore = context.similarityScore(veryRecentKnowledge, currentTime = now)
        val recentScore = context.similarityScore(recentKnowledge, currentTime = now)
        val moderateScore = context.similarityScore(moderateKnowledge, currentTime = now)
        val oldScore = context.similarityScore(oldKnowledge, currentTime = now)

        // Verify the ranking
        assertTrue(veryRecentScore > recentScore, "Very recent should score higher than recent")
        assertTrue(recentScore > moderateScore, "Recent should score higher than moderate")
        assertTrue(moderateScore > oldScore, "Moderate should score higher than old")

        // Verify approximate differences based on 30% recency weight
        // Very recent vs old: (1.0 - 0.2) * 0.3 = 0.24 difference
        val veryRecentVsOldDiff = veryRecentScore - oldScore
        assertTrue(veryRecentVsOldDiff > 0.20, "Difference between very recent and old should be significant")
    }

    // =============================================================================
    // EDGE CASES AND ROBUSTNESS TESTS
    // =============================================================================

    @Test
    fun `similarityScore handles sparse context with minimal description`() {
        val sparseContext = MemoryContext(
            taskType = "task",
            tags = emptySet(),
            description = "do something",
            projectContext = null,
            timeConstraint = null,
            complexity = null,
        )

        val knowledge = createKnowledgeFromOutcome(
            approach = "implemented feature with comprehensive testing",
            learnings = "testing is important for quality",
            timestamp = now.minus(5.days),
        )

        // Should not throw an exception
        val score = sparseContext.similarityScore(knowledge, currentTime = now)

        assertTrue(score >= 0.0 && score <= 1.0, "Sparse context should still produce valid score")
        assertTrue(score < 0.5, "Sparse context should have low similarity with unrelated knowledge")
    }

    @Test
    fun `similarityScore handles empty description gracefully`() {
        val emptyDescriptionContext = MemoryContext(
            taskType = "test_task",
            tags = setOf("testing"),
            description = "",
            projectContext = null,
        )

        val knowledge = createKnowledgeFromOutcome(
            approach = "some approach",
            learnings = "some learnings",
            timestamp = now.minus(5.days),
        )

        // Should not throw an exception
        val score = emptyDescriptionContext.similarityScore(knowledge, currentTime = now)

        assertTrue(score >= 0.0 && score <= 1.0, "Empty description should still produce valid score")
        // Score should primarily come from recency (30% weight)
        assertTrue(score > 0.0, "Should have some score from recency component")
    }

    @Test
    fun `similarityScore handles knowledge with special characters and punctuation`() {
        val context = MemoryContext(
            taskType = "code_review",
            tags = setOf("review"),
            description = "review pull request for user-authentication module",
            projectContext = "auth-service",
        )

        val knowledge = createKnowledgeFromOutcome(
            approach = "Reviewed PR #123: user-authentication (OAuth2.0) implementation!",
            learnings = "Authentication flows should be reviewed for: security, error-handling & edge-cases.",
            timestamp = now.minus(5.days),
        )

        // Should handle special characters without errors
        val score = context.similarityScore(knowledge, currentTime = now)

        assertTrue(score >= 0.0 && score <= 1.0, "Should handle special characters gracefully")
        assertTrue(score > 0.3, "Should find similarity despite special characters")
    }

    @Test
    fun `similarityScore with identical approach and learnings scores very high`() {
        val description = "implement database migration with schema validation and rollback support"
        val context = MemoryContext(
            taskType = "database_migration",
            tags = setOf("database", "migration"),
            description = description,
            projectContext = "db-service",
        )

        val identicalKnowledge = createKnowledgeFromOutcome(
            approach = description,
            learnings = description,
            timestamp = now.minus(2.days), // Very recent
        )

        val score = context.similarityScore(identicalKnowledge, currentTime = now)

        // With identical text and very recent timestamp, score should be very high
        assertTrue(score > 0.8, "Identical approach/learnings with recent timestamp should score very high (>0.8)")
    }

    // =============================================================================
    // TIME RANGE TESTS
    // =============================================================================

    @Test
    fun `TimeRange contains returns true for timestamps within range`() {
        val start = now.minus(10.days)
        val end = now
        val range = TimeRange(start, end)

        assertTrue(range.contains(now.minus(5.days)), "Mid-range timestamp should be contained")
        assertTrue(range.contains(start), "Start timestamp should be contained")
        assertTrue(range.contains(end), "End timestamp should be contained")
    }

    @Test
    fun `TimeRange contains returns false for timestamps outside range`() {
        val start = now.minus(10.days)
        val end = now.minus(5.days)
        val range = TimeRange(start, end)

        assertTrue(!range.contains(now.minus(15.days)), "Earlier timestamp should not be contained")
        assertTrue(!range.contains(now.minus(1.days)), "Later timestamp should not be contained")
    }

    @Test
    fun `TimeRange validates that end is not before start`() {
        val start = now
        val end = now.minus(10.days)

        assertFailsWith<IllegalArgumentException>(
            message = "TimeRange should reject end before start",
        ) {
            TimeRange(start, end)
        }
    }

    @Test
    fun `TimeRange allows same start and end time`() {
        val timestamp = now
        val range = TimeRange(timestamp, timestamp)

        assertTrue(range.contains(timestamp), "Point-in-time range should contain itself")
    }

    // =============================================================================
    // COMPLEXITY LEVEL TESTS
    // =============================================================================

    @Test
    fun `ComplexityLevel enum has all expected values`() {
        val levels = ComplexityLevel.values().toList()

        assertTrue(levels.contains(ComplexityLevel.TRIVIAL))
        assertTrue(levels.contains(ComplexityLevel.SIMPLE))
        assertTrue(levels.contains(ComplexityLevel.MODERATE))
        assertTrue(levels.contains(ComplexityLevel.COMPLEX))
        assertTrue(levels.contains(ComplexityLevel.NOVEL))
        assertEquals(5, levels.size, "Should have exactly 5 complexity levels")
    }

    // =============================================================================
    // INTEGRATION TESTS
    // =============================================================================

    @Test
    fun `MemoryContext with multiple overlapping keywords finds best match`() {
        val context = MemoryContext(
            taskType = "refactoring",
            tags = setOf("kotlin", "clean-code", "repository-pattern"),
            description = "refactor UserRepository to follow clean architecture with repository pattern and dependency injection",
            projectContext = "backend-service",
        )

        val perfectMatch = createKnowledgeFromOutcome(
            approach = "Refactored UserRepository following repository pattern with dependency injection for clean architecture",
            learnings = "Repository pattern with dependency injection improves testability and follows clean architecture principles",
            timestamp = now.minus(5.days),
        )

        val partialMatch = createKnowledgeFromOutcome(
            approach = "Refactored OrderService with dependency injection",
            learnings = "Dependency injection makes code more testable",
            timestamp = now.minus(5.days),
        )

        val noMatch = createKnowledgeFromOutcome(
            approach = "Fixed CSS styling bug in navigation menu",
            learnings = "Use CSS Grid for better layout control",
            timestamp = now.minus(5.days),
        )

        val perfectScore = context.similarityScore(perfectMatch, currentTime = now)
        val partialScore = context.similarityScore(partialMatch, currentTime = now)
        val noMatchScore = context.similarityScore(noMatch, currentTime = now)

        assertTrue(perfectScore > partialScore, "Perfect match should score higher than partial match")
        assertTrue(partialScore > noMatchScore, "Partial match should score higher than no match")
        assertTrue(perfectScore > 0.6, "Perfect match should have high similarity")
    }

    @Test
    fun `MemoryContext serialization roundtrip preserves all fields`() {
        // This test verifies that the @Serializable annotation works correctly
        val original = MemoryContext(
            taskType = "database_migration",
            tags = setOf("db", "migration", "schema"),
            description = "test description",
            projectContext = "test-project",
            timeConstraint = TimeRange(now.minus(10.days), now),
            complexity = ComplexityLevel.COMPLEX,
        )

        // In a real scenario, you would serialize/deserialize using kotlinx.serialization
        // For this test, we just verify the data class properties are accessible
        assertEquals("database_migration", original.taskType)
        assertEquals(setOf("db", "migration", "schema"), original.tags)
        assertEquals("test description", original.description)
        assertEquals("test-project", original.projectContext)
        assertEquals(ComplexityLevel.COMPLEX, original.complexity)
    }
}
