package link.socket.ampere.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock

class InMemoryKnowledgeStoreTest {

    @Test
    fun `addDocument is idempotent on content hash`() = runTest {
        val store = InMemoryKnowledgeStore()
        val doc = makeDocument("doc-1", "hash-1", "Hello world")
        val first = store.addDocument(doc).getOrThrow()
        val second = store.addDocument(makeDocument("doc-2", "hash-1", "Hello world")).getOrThrow()
        assertEquals(first.id, second.id)
    }

    @Test
    fun `getDocument returns null for unknown id`() = runTest {
        val store = InMemoryKnowledgeStore()
        assertNull(store.getDocument("missing").getOrThrow())
    }

    @Test
    fun `chunkAndEmbed indexes the document then query returns its chunks`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(makeDocument("doc-1", "hash-1", "Lighthouses guide ships."))
        store.chunkAndEmbed("doc-1", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()

        val results = store.query(
            text = "lighthouses",
            limit = 5,
            mode = QueryMode.KEYWORD,
        ).getOrThrow()

        assertEquals(1, results.size)
        assertEquals("doc-1", results.first().chunk.documentId)
        assertTrue(
            results.first().chunk.text.contains("Lighthouses"),
            "Returned chunk should contain the indexed text",
        )
    }

    @Test
    fun `setDocumentScopes round-trips`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(makeDocument("doc-1", "hash-1", "Sample text."))

        store.setDocumentScopes(
            documentId = "doc-1",
            scopes = setOf(KnowledgeScope.Work, KnowledgeScope("custom")),
        ).getOrThrow()

        val scopes = store.getDocumentScopes("doc-1").getOrThrow()
        assertEquals(setOf(KnowledgeScope.Work, KnowledgeScope("custom")), scopes)
    }

    @Test
    fun `setDocumentScopes with empty set clears scope tags`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(makeDocument("doc-1", "hash-1", "Sample text."))
        store.setDocumentScopes("doc-1", setOf(KnowledgeScope.Work)).getOrThrow()
        store.setDocumentScopes("doc-1", emptySet()).getOrThrow()
        assertEquals(emptySet(), store.getDocumentScopes("doc-1").getOrThrow())
    }

    @Test
    fun `setDocumentScopes for unknown document fails`() = runTest {
        val store = InMemoryKnowledgeStore()
        val result = store.setDocumentScopes("missing", setOf(KnowledgeScope.Work))
        assertTrue(result.isFailure)
    }

    @Test
    fun `scope filter excludes unscoped documents and other scopes`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(makeDocument("work-doc", "hash-w", "Lighthouse maintenance work log."))
        store.addDocument(makeDocument("personal-doc", "hash-p", "Lighthouse vacation diary."))
        store.addDocument(makeDocument("untagged-doc", "hash-u", "Lighthouse trivia."))

        store.chunkAndEmbed("work-doc", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()
        store.chunkAndEmbed("personal-doc", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()
        store.chunkAndEmbed("untagged-doc", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()

        store.setDocumentScopes("work-doc", setOf(KnowledgeScope.Work)).getOrThrow()
        store.setDocumentScopes("personal-doc", setOf(KnowledgeScope.Personal)).getOrThrow()

        val workResults = store.query(
            text = "lighthouse",
            limit = 10,
            mode = QueryMode.KEYWORD,
            scopes = setOf(KnowledgeScope.Work),
        ).getOrThrow()
        assertEquals(listOf("work-doc"), workResults.map { it.chunk.documentId })
        assertEquals(setOf(KnowledgeScope.Work), workResults.first().scopes)

        val unfiltered = store.query(
            text = "lighthouse",
            limit = 10,
            mode = QueryMode.KEYWORD,
        ).getOrThrow()
        assertEquals(
            setOf("work-doc", "personal-doc", "untagged-doc"),
            unfiltered.map { it.chunk.documentId }.toSet(),
        )
    }

    @Test
    fun `query enriches results with the document source uri`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(
            makeDocument(
                id = "doc-1",
                contentHash = "hash-1",
                content = "Lighthouse history.",
                sourceUri = "file:///docs/lighthouse.md",
            ),
        )
        store.chunkAndEmbed("doc-1", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()

        val hit = store.query(text = "lighthouse", mode = QueryMode.KEYWORD)
            .getOrThrow()
            .single()
        assertEquals("file:///docs/lighthouse.md", hit.sourceUri)
    }

    @Test
    fun `seedChunks lets a test pin embeddings explicitly`() = runTest {
        // Override the embedder to a 3-dim vector so it lines up with the seeded
        // chunk dimension.
        val store = InMemoryKnowledgeStore(
            embedder = { _ -> EmbeddingVector(floatArrayOf(1f, 0f, 0f)) },
        )
        store.addDocument(makeDocument("doc-1", "hash-1", "ignored")).getOrThrow()

        val chunk = KnowledgeChunk(
            id = "c1",
            documentId = "doc-1",
            chunkIndex = 0,
            text = "explicit",
            charStart = 0,
            charEnd = 8,
        )
        val embedding = EmbeddingVector(floatArrayOf(1f, 0f, 0f))
        store.seedChunks("doc-1", listOf(chunk to embedding)).getOrThrow()

        val results = store.query(
            text = "anything",
            limit = 5,
            mode = QueryMode.SEMANTIC,
        ).getOrThrow()

        // Single seeded chunk; semantic mode returns it with a defined score.
        assertEquals(listOf("c1"), results.map { it.chunk.id })
    }

    @Test
    fun `hybrid mode beats pure semantic and pure keyword on a controlled fixture`() = runTest {
        // Fixture
        // - target              : keyword match for "lighthouse" + moderate semantic vector
        // - semantic-distractor : top semantic match, no keyword overlap with the query
        // - keyword-distractor  : keyword match on both query tokens but semantically orthogonal
        //
        // Pure semantic ranks `semantic-distractor` first — wrong.
        // Pure keyword ranks `keyword-distractor` first — wrong.
        // Hybrid uses both signals and ranks `target` first.
        //
        // The embedder is stubbed: the query "lighthouse navigate" embeds to
        // [1, 0, 0]; chunk embeddings are pinned via seedChunks so semantic
        // ranking is fully determined and decoupled from the keyword text.
        val queryText = "lighthouse navigate"
        val store = InMemoryKnowledgeStore(
            embedder = { text ->
                if (text == queryText) {
                    EmbeddingVector(floatArrayOf(1f, 0f, 0f))
                } else {
                    EmbeddingVector(floatArrayOf(0f, 0f, 1f))
                }
            },
        )
        store.addDocument(makeDocument("doc-target", "h-t", "lighthouse seaside")).getOrThrow()
        store.addDocument(makeDocument("doc-sd", "h-sd", "harbor lights at dusk")).getOrThrow()
        store.addDocument(makeDocument("doc-kd", "h-kd", "lighthouse navigate trip overview")).getOrThrow()

        store.seedChunks(
            documentId = "doc-target",
            chunks = listOf(
                KnowledgeChunk(
                    id = "target",
                    documentId = "doc-target",
                    chunkIndex = 0,
                    text = "lighthouse seaside",
                    charStart = 0,
                    charEnd = 18,
                ) to EmbeddingVector(floatArrayOf(0.7f, 0.7f, 0f)),
            ),
        ).getOrThrow()
        store.seedChunks(
            documentId = "doc-sd",
            chunks = listOf(
                KnowledgeChunk(
                    id = "semantic-distractor",
                    documentId = "doc-sd",
                    chunkIndex = 0,
                    text = "harbor lights at dusk",
                    charStart = 0,
                    charEnd = 21,
                ) to EmbeddingVector(floatArrayOf(1f, 0f, 0f)),
            ),
        ).getOrThrow()
        store.seedChunks(
            documentId = "doc-kd",
            chunks = listOf(
                KnowledgeChunk(
                    id = "keyword-distractor",
                    documentId = "doc-kd",
                    chunkIndex = 0,
                    text = "lighthouse navigate trip overview",
                    charStart = 0,
                    charEnd = 33,
                ) to EmbeddingVector(floatArrayOf(0f, 0f, 1f)),
            ),
        ).getOrThrow()

        val pureSemantic = store.query(queryText, mode = QueryMode.SEMANTIC, limit = 5).getOrThrow()
        val pureKeyword = store.query(queryText, mode = QueryMode.KEYWORD, limit = 5).getOrThrow()
        val hybrid = store.query(queryText, mode = QueryMode.HYBRID, limit = 5).getOrThrow()

        assertEquals("semantic-distractor", pureSemantic.first().chunk.id)
        assertEquals("keyword-distractor", pureKeyword.first().chunk.id)
        assertEquals("target", hybrid.first().chunk.id)
        assertNotEquals(hybrid.first().chunk.id, pureSemantic.first().chunk.id)
        assertNotEquals(hybrid.first().chunk.id, pureKeyword.first().chunk.id)
    }

    private fun makeDocument(
        id: String,
        contentHash: String,
        content: String,
        sourceUri: String? = null,
    ): KnowledgeDocument {
        return KnowledgeDocument(
            id = id,
            title = "Document $id",
            sourceUri = sourceUri,
            importedAt = Clock.System.now(),
            contentHash = contentHash,
            content = content,
        )
    }
}
