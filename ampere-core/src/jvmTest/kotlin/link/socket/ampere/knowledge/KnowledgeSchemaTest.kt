package link.socket.ampere.knowledge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.db.Database

class KnowledgeSchemaTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `documents support insert lookup and listing`() {
        database.knowledgeQueries.insertDocument(
            id = "doc-1",
            title = "Lighthouse manual",
            source_uri = "file:///docs/lighthouse.txt",
            imported_at = 1_000L,
            content_hash = "hash-1",
        )
        database.knowledgeQueries.insertDocument(
            id = "doc-2",
            title = "Pier inventory",
            source_uri = null,
            imported_at = 2_000L,
            content_hash = "hash-2",
        )

        val byId = database.knowledgeQueries.getDocumentById("doc-1").executeAsOne()
        assertEquals("Lighthouse manual", byId.title)
        assertEquals("file:///docs/lighthouse.txt", byId.source_uri)
        assertEquals("hash-1", byId.content_hash)

        val byHash = database.knowledgeQueries.getDocumentByContentHash("hash-2").executeAsOne()
        assertEquals("doc-2", byHash.id)

        val newest = database.knowledgeQueries.listDocuments(limit = 10).executeAsList()
        assertEquals(listOf("doc-2", "doc-1"), newest.map { it.id })

        assertEquals(2L, database.knowledgeQueries.countDocuments().executeAsOne())
    }

    @Test
    fun `chunks list and bulk-delete by document`() {
        seedDocument("doc-1")
        database.knowledgeQueries.insertChunk(
            id = "chunk-1",
            document_id = "doc-1",
            chunk_index = 0,
            text = "Lighthouses warn ships of dangerous coastlines.",
            char_start = 0,
            char_end = 47,
        )
        database.knowledgeQueries.insertChunk(
            id = "chunk-2",
            document_id = "doc-1",
            chunk_index = 1,
            text = "Modern lighthouses are usually automated.",
            char_start = 48,
            char_end = 90,
        )

        val chunks = database.knowledgeQueries.getChunksByDocument("doc-1").executeAsList()
        assertEquals(listOf(0L, 1L), chunks.map { it.chunk_index })

        database.knowledgeQueries.deleteChunksForDocument("doc-1")

        assertEquals(
            0,
            database.knowledgeQueries.getChunksByDocument("doc-1").executeAsList().size,
        )
    }

    @Test
    fun `embeddings round-trip blob payload preserving float values`() {
        seedDocument("doc-1")
        database.knowledgeQueries.insertChunk(
            id = "chunk-1",
            document_id = "doc-1",
            chunk_index = 0,
            text = "An ocean wave hides a sandbar.",
            char_start = 0,
            char_end = 30,
        )

        val original = EmbeddingVector(floatArrayOf(0.25f, -0.5f, 0.75f, 1.125f))
        database.knowledgeQueries.insertEmbedding(
            chunk_id = "chunk-1",
            model_id = "all-MiniLM-L6-v2",
            vector_blob = original.toBlob(),
            created_at = 5_000L,
        )

        val row = database.knowledgeQueries
            .getEmbedding("chunk-1", "all-MiniLM-L6-v2")
            .executeAsOne()

        val restored = EmbeddingVector.fromBlob(row.vector_blob)
        assertEquals(original, restored)
        assertContentEquals(original.values, restored.values)
        assertEquals(5_000L, row.created_at)
    }

    @Test
    fun `getChunkEmbeddingsForModel joins chunks with their vectors`() {
        seedDocument("doc-1")
        listOf(
            Triple("chunk-1", 0, "First chunk"),
            Triple("chunk-2", 1, "Second chunk"),
        ).forEach { (id, index, text) ->
            database.knowledgeQueries.insertChunk(
                id = id,
                document_id = "doc-1",
                chunk_index = index.toLong(),
                text = text,
                char_start = 0,
                char_end = text.length.toLong(),
            )
            database.knowledgeQueries.insertEmbedding(
                chunk_id = id,
                model_id = "model-A",
                vector_blob = EmbeddingVector(floatArrayOf(index.toFloat(), 0f)).toBlob(),
                created_at = 1_000L + index,
            )
        }

        val joined = database.knowledgeQueries
            .getChunkEmbeddingsForModel("model-A")
            .executeAsList()
        assertEquals(2, joined.size)
        assertEquals(setOf("chunk-1", "chunk-2"), joined.map { it.chunk_id }.toSet())
        joined.forEach { row ->
            val vector = EmbeddingVector.fromBlob(row.vector_blob)
            assertEquals(2, vector.dimension)
        }

        val missing = database.knowledgeQueries
            .getChunkEmbeddingsForModel("missing-model")
            .executeAsList()
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `upsertEmbedding replaces existing row`() {
        seedDocument("doc-1")
        database.knowledgeQueries.insertChunk(
            id = "chunk-1",
            document_id = "doc-1",
            chunk_index = 0,
            text = "Original text.",
            char_start = 0,
            char_end = 14,
        )

        val first = EmbeddingVector(floatArrayOf(1f, 0f))
        database.knowledgeQueries.upsertEmbedding(
            chunk_id = "chunk-1",
            model_id = "model-A",
            vector_blob = first.toBlob(),
            created_at = 1_000L,
        )

        val second = EmbeddingVector(floatArrayOf(0f, 1f))
        database.knowledgeQueries.upsertEmbedding(
            chunk_id = "chunk-1",
            model_id = "model-A",
            vector_blob = second.toBlob(),
            created_at = 2_000L,
        )

        val row = assertNotNull(
            database.knowledgeQueries.getEmbedding("chunk-1", "model-A").executeAsOneOrNull(),
        )
        assertEquals(second, EmbeddingVector.fromBlob(row.vector_blob))
        assertEquals(2_000L, row.created_at)
        assertEquals(1L, database.knowledgeQueries.countEmbeddings().executeAsOne())
    }

    @Test
    fun `deleting a document removes the row`() {
        seedDocument("doc-1")
        database.knowledgeQueries.deleteDocument("doc-1")
        assertNull(database.knowledgeQueries.getDocumentById("doc-1").executeAsOneOrNull())
    }

    private fun seedDocument(id: String) {
        database.knowledgeQueries.insertDocument(
            id = id,
            title = "Doc $id",
            source_uri = null,
            imported_at = 1_000L,
            content_hash = "hash-$id",
        )
    }
}
