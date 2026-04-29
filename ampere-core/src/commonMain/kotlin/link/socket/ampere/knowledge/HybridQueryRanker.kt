package link.socket.ampere.knowledge

/**
 * Blends a semantic ([QueryMode.SEMANTIC]) and a keyword ([QueryMode.KEYWORD])
 * result list into a single [QueryMode.HYBRID] ranking.
 *
 * Each input list is independently max-normalised so its top hit becomes
 * `1.0`. The two normalised scores are then combined via a weighted sum:
 *
 * ```
 * hybrid = semanticWeight * semantic_norm + keywordWeight * keyword_norm
 * ```
 *
 * Chunks present in only one list have score `0` for the other dimension,
 * so they still surface — they just rank below chunks that appear in both.
 *
 * The weights default to `0.5 / 0.5`. They are configurable but intentionally
 * not auto-tuned in this ticket; downstream platform pipelines may revisit
 * once real corpora exist.
 *
 * Stateless and pure: no I/O, no allocation beyond the result list. Safe to
 * share across coroutines.
 */
class HybridQueryRanker(
    val semanticWeight: Float = DEFAULT_SEMANTIC_WEIGHT,
    val keywordWeight: Float = DEFAULT_KEYWORD_WEIGHT,
) {

    init {
        require(semanticWeight >= 0f) { "semanticWeight must be >= 0, got $semanticWeight" }
        require(keywordWeight >= 0f) { "keywordWeight must be >= 0, got $keywordWeight" }
        require(semanticWeight + keywordWeight > 0f) {
            "At least one weight must be positive (semantic=$semanticWeight, keyword=$keywordWeight)"
        }
    }

    /**
     * Combine [semanticResults] and [keywordResults] into a single ranked list.
     *
     * Each input list is normalised independently. The output is ordered by
     * descending blended score and truncated to [limit].
     *
     * Inputs may carry duplicate chunk IDs (from earlier merge passes) — the
     * highest score per chunk in each list is used.
     */
    fun rank(
        semanticResults: List<KnowledgeQueryResult>,
        keywordResults: List<KnowledgeQueryResult>,
        limit: Int = KnowledgeStore.DEFAULT_QUERY_LIMIT,
    ): List<KnowledgeQueryResult> {
        require(limit > 0) { "limit must be positive, got $limit" }

        val semanticById = bestScoresById(semanticResults)
        val keywordById = bestScoresById(keywordResults)

        val semanticMax = semanticById.values.maxOrNull() ?: 0f
        val keywordMax = keywordById.values.maxOrNull() ?: 0f

        // Pick a canonical KnowledgeQueryResult per chunk id so we keep the
        // underlying chunk + sourceUri + scopes when blending. Prefer the
        // semantic hit so its enrichment wins ties (semantic queries usually
        // walk the document table; keyword search may not).
        val canonicalById = mutableMapOf<String, KnowledgeQueryResult>()
        keywordResults.forEach { canonicalById.putIfAbsent(it.chunk.id, it) }
        semanticResults.forEach { canonicalById[it.chunk.id] = it }

        return canonicalById.values
            .map { hit ->
                val semanticNorm = normalise(semanticById[hit.chunk.id] ?: 0f, semanticMax)
                val keywordNorm = normalise(keywordById[hit.chunk.id] ?: 0f, keywordMax)
                val blended = semanticWeight * semanticNorm + keywordWeight * keywordNorm
                hit.copy(score = blended)
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun bestScoresById(results: List<KnowledgeQueryResult>): Map<String, Float> {
        val byId = mutableMapOf<String, Float>()
        results.forEach { hit ->
            val current = byId[hit.chunk.id]
            if (current == null || hit.score > current) {
                byId[hit.chunk.id] = hit.score
            }
        }
        return byId
    }

    private fun normalise(score: Float, max: Float): Float =
        if (max <= 0f) 0f else (score / max).coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_SEMANTIC_WEIGHT: Float = 0.5f
        const val DEFAULT_KEYWORD_WEIGHT: Float = 0.5f
    }
}
