package link.socket.ampere.agents.definition.qa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.DEFAULT_RELEVANCE_FLOOR
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore

/**
 * The relevance floor is the caller's policy, not a constant baked into the
 * extractor. These cover the default, a floor the caller chooses, and the
 * boundary, so a future change to [DEFAULT_RELEVANCE_FLOOR] cannot silently
 * change which entries shape a prompt.
 */
class ValidationInsightsTest {

    private fun scored(approach: String, relevanceScore: Double): KnowledgeWithScore {
        val knowledge = Knowledge.FromIdea(
            ideaId = "idea-$approach-$relevanceScore",
            approach = approach,
            learnings = "learned something about $approach",
            timestamp = Instant.fromEpochSeconds(0),
        )
        return KnowledgeWithScore(
            entry = KnowledgeEntry(
                id = "entry-$approach-$relevanceScore",
                knowledgeType = KnowledgeType.FROM_IDEA,
                approach = knowledge.approach,
                learnings = knowledge.learnings,
                timestamp = knowledge.timestamp,
            ),
            knowledge = knowledge,
            relevanceScore = relevanceScore,
        )
    }

    @Test
    fun `the default floor admits a high-scoring entry`() {
        val insights = ValidationInsights.fromKnowledge(listOf(scored("security", 0.9)))

        assertEquals(setOf("security"), insights.effectiveChecks.keys)
        assertTrue(insights.hasData())
    }

    @Test
    fun `the default floor excludes an entry below it`() {
        val insights = ValidationInsights.fromKnowledge(listOf(scored("security", 0.3)))

        assertTrue(insights.effectiveChecks.isEmpty())
        assertFalse(insights.hasData())
    }

    @Test
    fun `a caller-supplied floor admits what the default excludes`() {
        val barelyRelevant = listOf(scored("security", 0.3))

        assertTrue(ValidationInsights.fromKnowledge(barelyRelevant).effectiveChecks.isEmpty())
        assertEquals(
            setOf("security"),
            ValidationInsights.fromKnowledge(barelyRelevant, relevanceFloor = 0.0).effectiveChecks.keys,
        )
    }

    @Test
    fun `a caller-supplied floor excludes what the default admits`() {
        val relevant = listOf(scored("security", 0.9))

        assertEquals(setOf("security"), ValidationInsights.fromKnowledge(relevant).effectiveChecks.keys)
        assertTrue(
            ValidationInsights.fromKnowledge(relevant, relevanceFloor = 0.95).effectiveChecks.isEmpty(),
        )
    }

    @Test
    fun `the floor is compared strictly so an entry scoring exactly the floor is excluded`() {
        val atTheFloor = listOf(scored("security", DEFAULT_RELEVANCE_FLOOR))

        assertTrue(ValidationInsights.fromKnowledge(atTheFloor).effectiveChecks.isEmpty())
        assertEquals(
            setOf("security"),
            ValidationInsights.fromKnowledge(
                atTheFloor,
                relevanceFloor = DEFAULT_RELEVANCE_FLOOR - 0.01,
            ).effectiveChecks.keys,
        )
    }

    @Test
    fun `an empty recall returns empty insights whatever the floor`() {
        assertFalse(ValidationInsights.fromKnowledge(emptyList()).hasData())
        assertFalse(ValidationInsights.fromKnowledge(emptyList(), relevanceFloor = 0.0).hasData())
    }
}
