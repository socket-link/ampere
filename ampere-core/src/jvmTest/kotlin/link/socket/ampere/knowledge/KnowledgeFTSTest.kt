package link.socket.ampere.knowledge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.ampere.db.Database

class KnowledgeFTSTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        database.knowledgeQueries.insertDocument(
            id = "doc-1",
            title = "Manual",
            source_uri = null,
            imported_at = 1_000L,
            content_hash = "hash-1",
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `FTS query returns chunks matching keyword after insert`() {
        insertChunk("chunk-1", 0, "Lighthouses guide ships safely along the rocky coast.")
        insertChunk("chunk-2", 1, "Modern beacons replaced manual lamp keepers.")

        val matches = database.knowledgeFTSQueries
            .searchChunksByText("lighthouses", limit = 5)
            .executeAsList()

        assertEquals(listOf("chunk-1"), matches.map { it.id })
    }

    @Test
    fun `FTS index reflects updates to chunk text`() {
        insertChunk("chunk-1", 0, "First version mentions docking.")

        database.knowledgeQueries.updateChunkText(
            text = "Second version mentions sailing.",
            char_start = 0,
            char_end = 32,
            id = "chunk-1",
        )

        val sailing = database.knowledgeFTSQueries
            .searchChunksByText("sailing", limit = 5)
            .executeAsList()
        assertEquals(listOf("chunk-1"), sailing.map { it.id })

        val docking = database.knowledgeFTSQueries
            .searchChunksByText("docking", limit = 5)
            .executeAsList()
        assertTrue(docking.isEmpty())
    }

    @Test
    fun `FTS index removes entries when chunks are deleted`() {
        insertChunk("chunk-1", 0, "Schooners crossed the harbor at dawn.")
        insertChunk("chunk-2", 1, "Ferries kept the schedule despite fog.")

        database.knowledgeQueries.deleteChunksForDocument("doc-1")

        val matches = database.knowledgeFTSQueries
            .searchChunksByText("schooners", limit = 5)
            .executeAsList()
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `LIKE fallback returns chunks containing substring`() {
        insertChunk("chunk-1", 0, "Tide tables are critical for navigation.")
        insertChunk("chunk-2", 1, "Currents shift seasonally.")

        val matches = database.knowledgeFTSQueries
            .searchChunksByTextLike("Tide", limit = 5)
            .executeAsList()
        assertEquals(listOf("chunk-1"), matches.map { it.id })
    }

    private fun insertChunk(id: String, index: Int, text: String) {
        database.knowledgeQueries.insertChunk(
            id = id,
            document_id = "doc-1",
            chunk_index = index.toLong(),
            text = text,
            char_start = 0,
            char_end = text.length.toLong(),
        )
    }
}
