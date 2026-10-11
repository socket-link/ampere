package link.socket.ampere.subject

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Relationship
import link.socket.ampere.field.RelationshipKind
import link.socket.ampere.field.Subject

/**
 * The durability contract of [SqlDelightSubjectStore] and [SqlDelightRelationshipStore]
 * (AMPR-416, F30). Every test runs against a database *file* and [restart] closes the connection
 * and opens a new one over it, because a registry that only lives in one process is the C44
 * situation this store replaces.
 *
 * `runBlocking`, not `runTest`: both stores commit on a real IO dispatcher.
 */
class SubjectStoreTest {

    private val tempDir: Path = createTempDirectory(prefix = "subject-store")
    private val dbPath: Path = tempDir / "ampere.db"

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private val registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val later = Instant.fromEpochMilliseconds(1_700_000_060_000)
    private val provenance = Provenance(createdBy = "evt-register", createdAt = registeredAt)
    private val laterProvenance = Provenance(createdBy = "evt-retry", createdAt = later)

    private val engineer = Subject.Agent(Ref("engineer"), "Engineer", provenance)
    private val team = Subject.Group(Ref("team-1"), "team", provenance)

    private suspend fun <T> restart(block: suspend (Database) -> T): T =
        JdbcSqliteDriver("jdbc:sqlite:$dbPath").use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            block(Database(driver))
        }

    private fun Database.subjects(): SubjectStore = SqlDelightSubjectStore(this)

    private fun Database.relationships(): RelationshipStore = SqlDelightRelationshipStore(this)

    // ==================== SubjectStore ====================

    @Test
    fun `a registered subject is found by ref after a restart`() = runBlocking<Unit> {
        restart { db -> db.subjects().register(engineer).getOrThrow() }

        val found = restart { db -> db.subjects().get(Ref("engineer")).getOrThrow() }

        assertEquals(engineer, found)
    }

    @Test
    fun `registering the same id under the same kind returns the first row`() = runBlocking<Unit> {
        restart { db -> db.subjects().register(engineer).getOrThrow() }

        val again = restart { db ->
            db.subjects().register(engineer.copy(name = "Renamed", provenance = laterProvenance)).getOrThrow()
        }

        assertEquals(engineer, again, "the first registration keeps its name and provenance")
    }

    @Test
    fun `registering the same id under another kind is refused`() = runBlocking<Unit> {
        restart { db -> db.subjects().register(engineer).getOrThrow() }

        val failure = restart { db ->
            db.subjects().register(Subject.Group(Ref("engineer"), "Engineer", provenance)).exceptionOrNull()
        }

        val inUse = assertIs<SubjectIdInUseException>(failure)
        assertEquals("engineer", inUse.id)
        assertEquals(SubjectKind.AGENT, inUse.existing)
        assertEquals(SubjectKind.GROUP, inUse.requested)
        val still = restart { db -> db.subjects().get(Ref("engineer")).getOrThrow() }
        assertIs<Subject.Agent>(still, "the row was not re-typed")
    }

    @Test
    fun `list filters by kind in registration order and includes the seeded rows`() = runBlocking<Unit> {
        restart { db ->
            val store = db.subjects()
            store.register(Subject.Agent(Ref("zed"), "Zed", provenance)).getOrThrow()
            store.register(Subject.Agent(Ref("amy"), "Amy", laterProvenance)).getOrThrow()
            store.register(team).getOrThrow()
            store.register(Subject.Human(Ref("alice"), "Alice", provenance)).getOrThrow()
        }

        restart { db ->
            val store = db.subjects()
            val agents = store.list(SubjectKind.AGENT).getOrThrow().map { it.id.value }
            // The seeds carry a wall-clock created_at, so they sort after these 2023 fixtures.
            assertEquals(listOf("zed", "amy", SubjectRegistry.SYSTEM_ID), agents)
            assertEquals(
                listOf("alice", SubjectRegistry.PRINCIPAL_ID),
                store.list(SubjectKind.HUMAN).getOrThrow().map { it.id.value },
            )
            assertEquals(listOf("team-1"), store.list(SubjectKind.GROUP).getOrThrow().map { it.id.value })
            assertEquals(6, store.list().getOrThrow().size)
        }
    }

    @Test
    fun `delete forgets the subject and leaves its edges alone`() = runBlocking<Unit> {
        restart { db ->
            db.subjects().register(engineer).getOrThrow()
            db.subjects().register(team).getOrThrow()
            db.relationships().record(memberOf(engineer.id, team.id, provenance)).getOrThrow()
        }

        restart { db -> db.subjects().delete(Ref("engineer")).getOrThrow() }

        restart { db ->
            assertNull(db.subjects().get(Ref("engineer")).getOrThrow())
            assertEquals(
                1,
                db.relationships().to(team.id).getOrThrow().size,
                "the fold resolves refs, not the database",
            )
        }
    }

    // ==================== RelationshipStore ====================

    @Test
    fun `a recorded edge is read from either end after a restart`() = runBlocking<Unit> {
        val edge = memberOf(engineer.id, team.id, provenance)
        restart { db -> db.relationships().record(edge).getOrThrow() }

        restart { db ->
            val store = db.relationships()
            assertEquals(edge, store.get(engineer.id, team.id, RelationshipKind.MemberOf).getOrThrow())
            assertEquals(listOf(edge), store.from(engineer.id).getOrThrow())
            assertEquals(listOf(edge), store.to(team.id).getOrThrow())
            assertEquals(listOf(edge), store.to(team.id, RelationshipKind.MemberOf).getOrThrow())
            assertTrue(store.to(team.id, RelationshipKind.Mirrors).getOrThrow().isEmpty())
        }
    }

    @Test
    fun `recording the same edge twice keeps the first provenance`() = runBlocking<Unit> {
        val first = memberOf(engineer.id, team.id, provenance)
        restart { db -> db.relationships().record(first).getOrThrow() }

        val second = restart { db ->
            db.relationships().record(memberOf(engineer.id, team.id, laterProvenance)).getOrThrow()
        }

        assertEquals(first, second)
        assertEquals(1, restart { db -> db.relationships().to(team.id).getOrThrow().size })
    }

    @Test
    fun `an edge of a kind this build does not know is skipped by the list reads`() = runBlocking<Unit> {
        restart { db -> db.relationships().record(memberOf(engineer.id, team.id, provenance)).getOrThrow() }
        JdbcSqliteDriver("jdbc:sqlite:$dbPath").use { driver ->
            driver.execute(
                null,
                "INSERT INTO RelationshipStore VALUES ('engineer', 'team-1', 'Supervises', 'evt-future', 1)",
                0,
            )
        }

        restart { db ->
            val store = db.relationships()
            assertEquals(
                listOf(RelationshipKind.MemberOf),
                store.from(engineer.id).getOrThrow().map { it.kind },
                "the known edge survives the unknown one",
            )
            assertEquals(1, store.to(team.id).getOrThrow().size)
        }
    }

    @Test
    fun `delete removes exactly the named edge`() = runBlocking<Unit> {
        restart { db ->
            val store = db.relationships()
            store.record(memberOf(engineer.id, team.id, provenance)).getOrThrow()
            store.record(memberOf(Ref<Subject>("qa"), team.id, provenance)).getOrThrow()
        }

        restart { db -> db.relationships().delete(engineer.id, team.id, RelationshipKind.MemberOf).getOrThrow() }

        restart { db ->
            assertEquals(listOf("qa"), db.relationships().to(team.id).getOrThrow().map { it.from.value })
        }
    }

    @Test
    fun `the kind codec round-trips every kind and refuses an unknown name`() {
        assertEquals(RelationshipKind.MemberOf, RelationshipKindCodec.decode(RelationshipKindCodec.MEMBER_OF))
        assertEquals(RelationshipKind.Mirrors, RelationshipKindCodec.decode(RelationshipKindCodec.MIRRORS))
        assertEquals("MemberOf", RelationshipKindCodec.encode(RelationshipKind.MemberOf))
        assertEquals("Mirrors", RelationshipKindCodec.encode(RelationshipKind.Mirrors))
        assertNull(RelationshipKindCodec.decode("Supervises"))
    }

    private fun memberOf(
        from: Ref<Subject>,
        to: Ref<Subject.Group>,
        provenance: Provenance,
    ): Relationship<Primitive, Primitive> = Relationship(from, to, RelationshipKind.MemberOf, provenance)
}
