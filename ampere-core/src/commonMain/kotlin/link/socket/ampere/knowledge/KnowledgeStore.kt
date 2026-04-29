package link.socket.ampere.knowledge

import kotlinx.datetime.Instant

/**
 * On-device knowledge primitive: stores documents, chunks them, embeds the chunks,
 * and answers retrieval queries over the local index.
 *
 * Implementations are platform-specific (W1.8 iOS, W1.9 Android) — this commonMain
 * surface is the contract those pipelines target.
 *
 * All work runs on-device. No cloud embedding APIs are invoked from this layer.
 */
interface KnowledgeStore {

    /**
     * Persist a new document or update an existing one keyed by content hash.
     *
     * Implementations should be idempotent: importing a document whose
     * [KnowledgeDocument.contentHash] already exists must not create duplicate
     * chunks or embeddings.
     */
    suspend fun addDocument(document: KnowledgeDocument): Result<KnowledgeDocument>

    /**
     * Split [documentId]'s content into chunks and produce embeddings for each chunk
     * using the on-device embedding model identified by [modelId].
     *
     * @param documentId Document previously persisted via [addDocument].
     * @param modelId Identifier of the embedding model (e.g. `"all-MiniLM-L6-v2"`).
     * @return The chunks that were embedded, in order.
     */
    suspend fun chunkAndEmbed(
        documentId: String,
        modelId: String,
    ): Result<List<KnowledgeChunk>>

    /**
     * Retrieve chunks relevant to [text] using the supplied [mode].
     *
     * Results may be restricted to documents tagged with at least one of
     * [scopes]. An empty [scopes] (the default) disables scope filtering and
     * preserves the W0.5 behaviour. Documents with no scope rows are returned
     * only when [scopes] is empty.
     *
     * @param text Free-form query.
     * @param limit Maximum number of results to return.
     * @param mode Retrieval strategy. Defaults to [QueryMode.HYBRID].
     * @param scopes Optional scope set. Empty = no scope filter (W0.5 behaviour).
     */
    suspend fun query(
        text: String,
        limit: Int = DEFAULT_QUERY_LIMIT,
        mode: QueryMode = QueryMode.HYBRID,
        scopes: Set<KnowledgeScope> = emptySet(),
    ): Result<List<KnowledgeQueryResult>>

    /**
     * Look up a previously-imported document by [id], or return `null` if no
     * such document exists.
     *
     * Surfaces the [KnowledgeDocument.sourceUri] that retrieval consumers
     * need to render a citation alongside each chunk.
     */
    suspend fun getDocument(id: String): Result<KnowledgeDocument?>

    /**
     * Replace the scope set associated with [documentId].
     *
     * The previous scope set is cleared atomically and replaced by [scopes].
     * Calling with an empty [scopes] removes all scope tags (the document
     * becomes scope-less and is excluded from any scope-filtered query).
     *
     * Implementations must be idempotent: calling twice with the same
     * arguments leaves the store in the same state.
     */
    suspend fun setDocumentScopes(
        documentId: String,
        scopes: Set<KnowledgeScope>,
    ): Result<Unit>

    /**
     * Return the scope set associated with [documentId], or an empty set
     * when the document has no scope tags.
     */
    suspend fun getDocumentScopes(documentId: String): Result<Set<KnowledgeScope>>

    companion object {
        const val DEFAULT_QUERY_LIMIT: Int = 10
    }
}

/**
 * Strategy for resolving a [KnowledgeStore.query] call.
 */
enum class QueryMode {
    /** Cosine-similarity search over [EmbeddingVector]s. */
    SEMANTIC,

    /** FTS5 keyword search over chunk text. */
    KEYWORD,

    /** Combine [SEMANTIC] and [KEYWORD] results, blending scores. */
    HYBRID,
}

/**
 * A document imported into the on-device knowledge store.
 *
 * @param id Stable identifier.
 * @param title Human-readable title.
 * @param sourceUri Optional pointer to the original source (file path, URL, etc.).
 * @param importedAt When the document was imported.
 * @param contentHash Hash of the source content; used for idempotent re-import.
 * @param content Raw textual content. May be empty if the implementation lazily
 *        loads content from [sourceUri].
 */
data class KnowledgeDocument(
    val id: String,
    val title: String,
    val sourceUri: String?,
    val importedAt: Instant,
    val contentHash: String,
    val content: String = "",
)

/**
 * A chunk of a [KnowledgeDocument]'s text — the unit at which embeddings are produced
 * and retrieval results are returned.
 */
data class KnowledgeChunk(
    val id: String,
    val documentId: String,
    val chunkIndex: Int,
    val text: String,
    val charStart: Int,
    val charEnd: Int,
)

/**
 * A single retrieval hit returned by [KnowledgeStore.query].
 *
 * @param chunk The matching chunk.
 * @param score Mode-dependent score (cosine similarity, FTS rank, or hybrid blend).
 *        Higher scores indicate better matches.
 * @param sourceUri Source URI from the parent [KnowledgeDocument], populated
 *        when the store knows it. May be `null` when the document has no
 *        source URI or when the store cannot resolve it without an extra read.
 * @param scopes Scope tags associated with the parent document. Empty when
 *        the document has no scope tags or when scope data is not loaded.
 */
data class KnowledgeQueryResult(
    val chunk: KnowledgeChunk,
    val score: Float,
    val sourceUri: String? = null,
    val scopes: Set<KnowledgeScope> = emptySet(),
)
