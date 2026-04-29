package link.socket.ampere.knowledge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [KnowledgeStore] used by AMPERE-side tests and by downstream
 * platform-store conformance harnesses.
 *
 * Designed as a behaviour fixture, not a production engine: scoring is
 * deterministic and easy to reason about, so tests can express "for this
 * fixture corpus and this query the ranker should beat pure semantic /
 * pure keyword" without depending on a real ANN index.
 *
 * Seeding paths:
 *
 * 1. [addDocument] + [chunkAndEmbed] mirror the production wiring. The
 *    [embedder] parameter is invoked once per chunk to produce its
 *    [EmbeddingVector].
 * 2. [seedChunks] lets a test pin chunks **and** their embeddings explicitly
 *    so the test author has full control over similarity scores.
 *
 * Scoring details:
 *
 * - **Semantic** scores are cosine similarity between the query embedding
 *   (produced by [embedder]) and each stored chunk embedding.
 * - **Keyword** scores are case-insensitive token-overlap fractions:
 *   `(# unique query tokens present in the chunk) / (# unique query tokens)`.
 * - **Hybrid** scores blend the two via [HybridQueryRanker].
 *
 * Concurrency: state is guarded by a single [Mutex]. Concurrent reads serialise
 * through it, which is fine for fixture sizes.
 */
class InMemoryKnowledgeStore(
    private val embedder: (String) -> EmbeddingVector = ::tokenBagEmbedder,
    private val ranker: HybridQueryRanker = HybridQueryRanker(),
    private val defaultModelId: String = DEFAULT_MODEL_ID,
    private val chunker: (KnowledgeDocument) -> List<KnowledgeChunk> = ::singleChunkChunker,
) : KnowledgeStore {

    private val mutex = Mutex()
    private val documentsById = mutableMapOf<String, KnowledgeDocument>()
    private val chunksByDocument = mutableMapOf<String, MutableList<KnowledgeChunk>>()
    private val embeddingsByChunkAndModel = mutableMapOf<Pair<String, String>, EmbeddingVector>()
    private val scopesByDocument = mutableMapOf<String, MutableSet<KnowledgeScope>>()

    override suspend fun addDocument(document: KnowledgeDocument): Result<KnowledgeDocument> =
        mutex.withLock {
            runCatching {
                val existingByHash = documentsById.values.firstOrNull {
                    it.contentHash == document.contentHash
                }
                if (existingByHash != null && existingByHash.id != document.id) {
                    // Same content hash but a different id — treat as duplicate import,
                    // surface the previously-stored document.
                    return@runCatching existingByHash
                }
                documentsById[document.id] = document
                chunksByDocument.getOrPut(document.id) { mutableListOf() }
                document
            }
        }

    override suspend fun chunkAndEmbed(
        documentId: String,
        modelId: String,
    ): Result<List<KnowledgeChunk>> = mutex.withLock {
        runCatching {
            val document = documentsById[documentId]
                ?: throw NoSuchElementException("Document $documentId not found")

            val chunks = chunker(document)
            val perDocument = chunksByDocument.getOrPut(documentId) { mutableListOf() }
            perDocument.clear()
            perDocument += chunks

            chunks.forEach { chunk ->
                embeddingsByChunkAndModel[chunk.id to modelId] = embedder(chunk.text)
            }

            chunks.toList()
        }
    }

    override suspend fun query(
        text: String,
        limit: Int,
        mode: QueryMode,
        scopes: Set<KnowledgeScope>,
    ): Result<List<KnowledgeQueryResult>> = mutex.withLock {
        runCatching {
            require(limit > 0) { "limit must be positive, got $limit" }

            val allowedDocumentIds = if (scopes.isEmpty()) {
                null
            } else {
                scopesByDocument.entries
                    .filter { (_, docScopes) -> docScopes.any { it in scopes } }
                    .map { it.key }
                    .toSet()
            }

            val candidateChunks = chunksByDocument
                .filter { (docId, _) -> allowedDocumentIds == null || docId in allowedDocumentIds }
                .flatMap { (_, chunks) -> chunks }

            val semanticHits = scoreSemantic(text, candidateChunks)
            val keywordHits = scoreKeyword(text, candidateChunks)

            val ranked = when (mode) {
                QueryMode.SEMANTIC -> semanticHits.sortedByDescending { it.score }
                QueryMode.KEYWORD -> keywordHits.sortedByDescending { it.score }
                QueryMode.HYBRID -> ranker.rank(semanticHits, keywordHits, limit = limit)
            }

            ranked
                .take(limit)
                .map { hit -> enrich(hit) }
        }
    }

    override suspend fun getDocument(id: String): Result<KnowledgeDocument?> = mutex.withLock {
        runCatching { documentsById[id] }
    }

    override suspend fun setDocumentScopes(
        documentId: String,
        scopes: Set<KnowledgeScope>,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            if (documentId !in documentsById) {
                throw NoSuchElementException("Document $documentId not found")
            }
            if (scopes.isEmpty()) {
                scopesByDocument.remove(documentId)
            } else {
                scopesByDocument[documentId] = scopes.toMutableSet()
            }
            Unit
        }
    }

    override suspend fun getDocumentScopes(documentId: String): Result<Set<KnowledgeScope>> =
        mutex.withLock {
            runCatching {
                scopesByDocument[documentId]?.toSet() ?: emptySet()
            }
        }

    /**
     * Pin chunks **and** their embeddings for [documentId] explicitly.
     *
     * Use when a test wants full control over similarity scores rather than
     * relying on the default token-bag [embedder].
     *
     * Replaces any existing chunks for the document. The document must have
     * been added via [addDocument] beforehand.
     */
    suspend fun seedChunks(
        documentId: String,
        chunks: List<Pair<KnowledgeChunk, EmbeddingVector>>,
        modelId: String = defaultModelId,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            if (documentId !in documentsById) {
                throw NoSuchElementException("Document $documentId not found")
            }
            val perDocument = chunksByDocument.getOrPut(documentId) { mutableListOf() }
            perDocument.clear()
            perDocument += chunks.map { it.first }
            chunks.forEach { (chunk, vector) ->
                embeddingsByChunkAndModel[chunk.id to modelId] = vector
            }
        }
    }

    private fun scoreSemantic(
        text: String,
        candidateChunks: List<KnowledgeChunk>,
    ): List<KnowledgeQueryResult> {
        val queryVector = embedder(text)
        return candidateChunks.mapNotNull { chunk ->
            val vector = embeddingsByChunkAndModel[chunk.id to defaultModelId]
                ?: return@mapNotNull null
            if (vector.dimension != queryVector.dimension) return@mapNotNull null
            KnowledgeQueryResult(
                chunk = chunk,
                score = vector.cosineSimilarity(queryVector),
            )
        }
    }

    private fun scoreKeyword(
        text: String,
        candidateChunks: List<KnowledgeChunk>,
    ): List<KnowledgeQueryResult> {
        val queryTokens = tokenize(text)
        if (queryTokens.isEmpty()) return emptyList()
        return candidateChunks.mapNotNull { chunk ->
            val chunkTokens = tokenize(chunk.text)
            if (chunkTokens.isEmpty()) return@mapNotNull null
            val matches = queryTokens.count { it in chunkTokens }
            if (matches == 0) return@mapNotNull null
            KnowledgeQueryResult(
                chunk = chunk,
                score = matches.toFloat() / queryTokens.size.toFloat(),
            )
        }
    }

    private fun enrich(hit: KnowledgeQueryResult): KnowledgeQueryResult {
        val document = documentsById[hit.chunk.documentId]
        val scopes = scopesByDocument[hit.chunk.documentId]?.toSet().orEmpty()
        return hit.copy(
            sourceUri = document?.sourceUri,
            scopes = scopes,
        )
    }

    companion object {
        const val DEFAULT_MODEL_ID: String = "in-memory-test-embedder"
    }
}

/**
 * Bag-of-tokens embedder used by [InMemoryKnowledgeStore] when callers do
 * not supply their own. Each unique token contributes 1.0 to a fixed-size
 * hashed dimension; the resulting vector is roughly proportional to token
 * presence and gives non-zero similarity between chunks that share tokens.
 *
 * Deterministic across runs and platforms — relies only on [String.hashCode]
 * folded into the configured dimension.
 */
internal fun tokenBagEmbedder(text: String, dimension: Int = 64): EmbeddingVector {
    val values = FloatArray(dimension)
    tokenize(text).forEach { token ->
        // Stable fold from String.hashCode into [0, dimension).
        val bucket = ((token.hashCode() % dimension) + dimension) % dimension
        values[bucket] += 1f
    }
    if (values.all { it == 0f }) {
        // Avoid zero vectors so cosine-similarity is well defined; pick a
        // single canary dimension so empty/whitespace text is orthogonal to
        // any real text.
        values[0] = 1f
    }
    return EmbeddingVector(values)
}

private fun tokenize(text: String): Set<String> =
    text.lowercase()
        .split(Regex("[^\\p{L}\\p{Nd}]+"))
        .filter { it.isNotBlank() }
        .toSet()

internal fun singleChunkChunker(document: KnowledgeDocument): List<KnowledgeChunk> {
    val content = document.content
    return listOf(
        KnowledgeChunk(
            id = "${document.id}:0",
            documentId = document.id,
            chunkIndex = 0,
            text = content,
            charStart = 0,
            charEnd = content.length,
        ),
    )
}
