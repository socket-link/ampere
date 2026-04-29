package link.socket.ampere.knowledge

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HybridQueryRankerTest {

    @Test
    fun `weights default to balanced`() {
        val ranker = HybridQueryRanker()
        assertEquals(HybridQueryRanker.DEFAULT_SEMANTIC_WEIGHT, ranker.semanticWeight)
        assertEquals(HybridQueryRanker.DEFAULT_KEYWORD_WEIGHT, ranker.keywordWeight)
    }

    @Test
    fun `negative weights are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            HybridQueryRanker(semanticWeight = -0.1f, keywordWeight = 0.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            HybridQueryRanker(semanticWeight = 0.5f, keywordWeight = -0.1f)
        }
    }

    @Test
    fun `both-zero weights are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            HybridQueryRanker(semanticWeight = 0f, keywordWeight = 0f)
        }
    }

    @Test
    fun `non-positive limit is rejected`() {
        val ranker = HybridQueryRanker()
        assertFailsWith<IllegalArgumentException> {
            ranker.rank(emptyList(), emptyList(), limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ranker.rank(emptyList(), emptyList(), limit = -1)
        }
    }

    @Test
    fun `empty inputs return an empty list`() {
        val ranker = HybridQueryRanker()
        assertTrue(ranker.rank(emptyList(), emptyList(), limit = 5).isEmpty())
    }

    @Test
    fun `chunk only in semantic list still surfaces`() {
        val ranker = HybridQueryRanker(semanticWeight = 0.5f, keywordWeight = 0.5f)
        val results = ranker.rank(
            semanticResults = listOf(hit("only-semantic", 0.9f)),
            keywordResults = emptyList(),
            limit = 5,
        )
        assertEquals(listOf("only-semantic"), results.map { it.chunk.id })
        // Score is 0.5 * 1.0 (max-normalised) + 0.5 * 0 = 0.5
        assertApproximately(0.5f, results.first().score)
    }

    @Test
    fun `chunk only in keyword list still surfaces`() {
        val ranker = HybridQueryRanker(semanticWeight = 0.5f, keywordWeight = 0.5f)
        val results = ranker.rank(
            semanticResults = emptyList(),
            keywordResults = listOf(hit("only-keyword", 0.7f)),
            limit = 5,
        )
        assertEquals(listOf("only-keyword"), results.map { it.chunk.id })
        assertApproximately(0.5f, results.first().score)
    }

    @Test
    fun `weights bias the ranking when only one signal fires`() {
        val semanticHeavy = HybridQueryRanker(semanticWeight = 0.9f, keywordWeight = 0.1f)
        val results = semanticHeavy.rank(
            semanticResults = listOf(hit("a", 1f), hit("b", 0.1f)),
            keywordResults = listOf(hit("a", 0.1f), hit("b", 1f)),
            limit = 5,
        )
        // a: 0.9*1 + 0.1*0.1 = 0.91; b: 0.9*0.1 + 0.1*1 = 0.19
        assertEquals(listOf("a", "b"), results.map { it.chunk.id })
    }

    @Test
    fun `result list is truncated to the limit`() {
        val ranker = HybridQueryRanker()
        val semantic = (1..10).map { hit("c$it", it.toFloat() / 10f) }
        val keyword = (1..10).map { hit("c$it", (11 - it).toFloat() / 10f) }
        val results = ranker.rank(semantic, keyword, limit = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `hybrid ranking beats pure semantic when keyword signal is decisive`() {
        // Fixture:
        // - target  : strong on both signals
        // - semantic-distractor : top semantic match, no keyword match
        // - keyword-distractor : strong keyword match, weak semantic match
        // Pure semantic alone ranks the semantic-distractor on top — wrong.
        // Pure keyword alone ranks the keyword-distractor on top — also wrong.
        // Hybrid catches both signals and ranks `target` first.
        val semanticResults = listOf(
            hit(id = "semantic-distractor", score = 1.0f),
            hit(id = "target", score = 0.7f),
            hit(id = "keyword-distractor", score = 0.0f),
        )
        val keywordResults = listOf(
            hit(id = "keyword-distractor", score = 1.0f),
            hit(id = "target", score = 0.5f),
        )

        val ranker = HybridQueryRanker(semanticWeight = 0.5f, keywordWeight = 0.5f)

        val pureSemantic = semanticResults.sortedByDescending { it.score }
        val pureKeyword = keywordResults.sortedByDescending { it.score }
        val hybrid = ranker.rank(semanticResults, keywordResults, limit = 3)

        assertEquals("semantic-distractor", pureSemantic.first().chunk.id)
        assertEquals("keyword-distractor", pureKeyword.first().chunk.id)
        assertEquals(
            "target",
            hybrid.first().chunk.id,
            "Hybrid should put `target` first because both signals fire on it",
        )

        // Hybrid `target` outscores either sibling.
        val targetScore = hybrid.first { it.chunk.id == "target" }.score
        val distractorScores = hybrid
            .filter { it.chunk.id != "target" }
            .map { it.score }
        distractorScores.forEach { distractor ->
            assertTrue(
                targetScore > distractor,
                "Hybrid score for target ($targetScore) should beat distractor ($distractor)",
            )
        }
    }

    @Test
    fun `duplicate chunk ids in one input collapse to the best score`() {
        val ranker = HybridQueryRanker(semanticWeight = 1f, keywordWeight = 0f)
        val results = ranker.rank(
            semanticResults = listOf(hit("dup", 0.3f), hit("dup", 0.9f)),
            keywordResults = emptyList(),
            limit = 5,
        )
        assertEquals(1, results.size)
        // semantic max = 0.9 (best), so normalised score is 1.0; weighted by 1.0 = 1.0
        assertApproximately(1.0f, results.first().score)
    }

    @Test
    fun `enrichment fields from semantic hit are preserved over keyword hit`() {
        val ranker = HybridQueryRanker()
        val semanticHit = hit(
            id = "shared",
            score = 0.5f,
            sourceUri = "from-semantic",
            scopes = setOf(KnowledgeScope.Work),
        )
        val keywordHit = hit(
            id = "shared",
            score = 0.5f,
            sourceUri = "from-keyword",
            scopes = setOf(KnowledgeScope.Personal),
        )
        val results = ranker.rank(listOf(semanticHit), listOf(keywordHit), limit = 1)
        val merged = results.single()
        // Semantic hit wins enrichment because it has the more authoritative
        // document join.
        assertEquals("from-semantic", merged.sourceUri)
        assertEquals(setOf(KnowledgeScope.Work), merged.scopes)
        assertNotEquals("from-keyword", merged.sourceUri)
    }

    private fun hit(
        id: String,
        score: Float,
        sourceUri: String? = null,
        scopes: Set<KnowledgeScope> = emptySet(),
    ): KnowledgeQueryResult = KnowledgeQueryResult(
        chunk = KnowledgeChunk(
            id = id,
            documentId = "doc-$id",
            chunkIndex = 0,
            text = "text-$id",
            charStart = 0,
            charEnd = 10,
        ),
        score = score,
        sourceUri = sourceUri,
        scopes = scopes,
    )

    private fun assertApproximately(expected: Float, actual: Float, epsilon: Float = 1e-5f) {
        assertTrue(
            abs(expected - actual) < epsilon,
            "Expected ~$expected, got $actual",
        )
    }
}
