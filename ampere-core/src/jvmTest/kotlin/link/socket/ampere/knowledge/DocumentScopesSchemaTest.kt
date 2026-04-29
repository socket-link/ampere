package link.socket.ampere.knowledge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.ampere.db.Database

class DocumentScopesSchemaTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        // Foreign keys must be enabled per connection on SQLite.
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        seedDocument("doc-1")
        seedDocument("doc-2")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `scopes can be inserted and listed for a document`() {
        database.knowledgeQueries.insertDocumentScope("doc-1", "work")
        database.knowledgeQueries.insertDocumentScope("doc-1", "personal")

        val scopes = database.knowledgeQueries.getScopesForDocument("doc-1").executeAsList()
        assertEquals(listOf("personal", "work"), scopes)
    }

    @Test
    fun `clearDocumentScopes removes all scope rows for the document`() {
        database.knowledgeQueries.insertDocumentScope("doc-1", "work")
        database.knowledgeQueries.insertDocumentScope("doc-1", "personal")
        database.knowledgeQueries.clearDocumentScopes("doc-1")

        val remaining = database.knowledgeQueries.getScopesForDocument("doc-1").executeAsList()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `listDocumentsInScopes returns each matching document at most once`() {
        database.knowledgeQueries.insertDocumentScope("doc-1", "work")
        database.knowledgeQueries.insertDocumentScope("doc-1", "reading")
        database.knowledgeQueries.insertDocumentScope("doc-2", "personal")

        val workOnly = database.knowledgeQueries
            .listDocumentsInScopes(listOf("work"))
            .executeAsList()
        assertEquals(listOf("doc-1"), workOnly)

        val multi = database.knowledgeQueries
            .listDocumentsInScopes(listOf("work", "personal", "reading"))
            .executeAsList()
            .toSet()
        assertEquals(setOf("doc-1", "doc-2"), multi)
    }

    @Test
    fun `inserting a duplicate scope is a no-op`() {
        database.knowledgeQueries.insertDocumentScope("doc-1", "work")
        database.knowledgeQueries.insertDocumentScope("doc-1", "work")

        val scopes = database.knowledgeQueries.getScopesForDocument("doc-1").executeAsList()
        assertEquals(listOf("work"), scopes)
    }

    @Test
    fun `deleting a document cascades to its scopes`() {
        database.knowledgeQueries.insertDocumentScope("doc-1", "work")
        database.knowledgeQueries.deleteDocument("doc-1")

        val scopes = database.knowledgeQueries.getScopesForDocument("doc-1").executeAsList()
        assertTrue(scopes.isEmpty())
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
