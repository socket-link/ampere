package link.socket.ampere.agents.definition.product

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
 * extractor. `testFirstSuccessRate` is the mean score of the admitted
 * test-related entries, so moving the floor is observable in the result rather
 * than only in which entries were read.
 */
class PlanningInsightsTest {

    /** A rate is a mean of scores; compare it as arithmetic rather than bit-for-bit. */
    private val tolerance = 1e-9

    private fun scored(relevanceScore: Double, learnings: String = "test-first worked"): KnowledgeWithScore {
        val knowledge = Knowledge.FromIdea(
            ideaId = "idea-$relevanceScore",
            approach = "test-first",
            learnings = learnings,
            timestamp = Instant.fromEpochSeconds(0),
        )
        return KnowledgeWithScore(
            entry = KnowledgeEntry(
                id = "entry-$relevanceScore",
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
        val insights = PlanningInsights.fromKnowledge(listOf(scored(0.9)))

        assertEquals(0.9, insights.testFirstSuccessRate, tolerance)
        assertTrue(insights.hasData())
    }

    @Test
    fun `the default floor excludes an entry below it`() {
        val insights = PlanningInsights.fromKnowledge(listOf(scored(0.3)))

        assertEquals(0.0, insights.testFirstSuccessRate, tolerance)
        assertFalse(insights.hasData())
    }

    @Test
    fun `lowering the floor admits the weaker entry and moves the rate`() {
        val mixed = listOf(scored(0.9), scored(0.3))

        assertEquals(0.9, PlanningInsights.fromKnowledge(mixed).testFirstSuccessRate, tolerance)
        assertEquals(
            0.6,
            PlanningInsights.fromKnowledge(mixed, relevanceFloor = 0.0).testFirstSuccessRate,
            tolerance,
        )
    }

    @Test
    fun `raising the floor excludes what the default admits`() {
        val relevant = listOf(scored(0.9))

        assertEquals(0.9, PlanningInsights.fromKnowledge(relevant).testFirstSuccessRate, tolerance)
        assertFalse(PlanningInsights.fromKnowledge(relevant, relevanceFloor = 0.95).hasData())
    }

    @Test
    fun `the floor is compared strictly so an entry scoring exactly the floor is excluded`() {
        val atTheFloor = listOf(scored(DEFAULT_RELEVANCE_FLOOR))

        assertFalse(PlanningInsights.fromKnowledge(atTheFloor).hasData())
        assertEquals(
            DEFAULT_RELEVANCE_FLOOR,
            PlanningInsights.fromKnowledge(
                atTheFloor,
                relevanceFloor = DEFAULT_RELEVANCE_FLOOR - 0.01,
            ).testFirstSuccessRate,
            tolerance,
        )
    }

    @Test
    fun `an empty recall returns empty insights whatever the floor`() {
        assertFalse(PlanningInsights.fromKnowledge(emptyList()).hasData())
        assertFalse(PlanningInsights.fromKnowledge(emptyList(), relevanceFloor = 0.0).hasData())
    }
}
