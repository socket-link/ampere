package link.socket.ampere.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.PersistedStore
import link.socket.ampere.agents.domain.event.StoreRowUndecodableEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.canon.CanonType
import link.socket.ampere.db.Database

/**
 * Version skew at the `Links` boundary (AMPR-364): a stored `Link` whose `scope` names a canon
 * member this build does not have.
 *
 * `Link.scope` is a `Set<CanonType>` persisted by member name, and an unknown enum member is a
 * hard `SerializationException` — `ignoreUnknownKeys` covers keys, never enum values. Before
 * this ticket one such row turned `list()` into a `Result.failure`, which
 * [LinkResolutionService] reads as "no Links", so one unreadable row silently unplugged every
 * Plug. Now it is one skipped row, named on the bus.
 *
 * Bodies use `runBlocking`: the door persists on a real IO dispatcher, and `runTest`'s virtual
 * clock would run ahead of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkStoreUndecodableRowTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())

    /** The same configuration [SqlDelightLinkStore] encodes with, so a seeded row is realistic. */
    private val storeJson = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val vaultLink = Link(
        id = LinkId("obsidian-vault"),
        transport = Transport.FOLDER_MOUNT,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.OnDevice,
        scope = setOf(CanonType.NOTE),
    )

    private fun door(): InMemoryEventApi.Handle =
        InMemoryEventApi.open(agentId = "link-store", scope = doorScope)

    /**
     * Writes a row exactly as a newer build would have: valid `Link` JSON with one scope member
     * this build cannot name. The [assertNotEquals] is a tripwire — if `note`'s wire name ever
     * moves, the seeded row stops being skewed and every assertion below would pass vacuously.
     */
    private fun seedSkewedRow(database: Database, linkId: String) {
        val valid = storeJson.encodeToString(
            Link.serializer(),
            vaultLink.copy(id = LinkId(linkId)),
        )
        val skewed = valid.replace("[\"note\"]", "[\"note_from_a_newer_build\"]")
        assertNotEquals(valid, skewed, "the seeded row is no longer skewed")

        database.linksQueries.upsertLink(
            link_id = linkId,
            transport = Transport.FOLDER_MOUNT.name,
            link_json = skewed,
            updated_at = at.toEpochMilliseconds(),
        )
    }

    @Test
    fun `list returns the rows it could read and announces the one it skipped`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database, door.api)
            store.upsert(vaultLink, at).getOrThrow()
            seedSkewedRow(door.database, "skewed-link")

            assertEquals(listOf(vaultLink), store.list().getOrThrow())

            val announced = door.repository
                .getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                .getOrThrow()
            val seen = assertIs<StoreRowUndecodableEvent>(announced.single())
            assertEquals(PersistedStore.LINK_STORE, seen.store)
            assertEquals("skewed-link", seen.rowId)
            assertTrue(seen.reason.isNotBlank(), "a skip with no reason is not diagnosable")
        }
    }

    @Test
    fun `listByTransport skips the same row rather than failing`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database, door.api)
            store.upsert(vaultLink, at).getOrThrow()
            seedSkewedRow(door.database, "skewed-link")

            assertEquals(
                listOf(vaultLink),
                store.listByTransport(Transport.FOLDER_MOUNT).getOrThrow(),
            )
        }
    }

    @Test
    fun `a row already announced is not announced again on the next read`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database, door.api)
            seedSkewedRow(door.database, "skewed-link")

            store.list().getOrThrow()
            store.list().getOrThrow()
            store.listByTransport(Transport.FOLDER_MOUNT).getOrThrow()

            val announced = door.repository
                .getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                .getOrThrow()
            assertEquals(1, announced.size, "the row is unreadable once, not once per read")
        }
    }

    @Test
    fun `get on an undecodable row is a typed failure rather than a bare SerializationException`() =
        runBlocking {
            door().use { door ->
                val store = SqlDelightLinkStore(door.database, door.api)
                seedSkewedRow(door.database, "skewed-link")

                val failure = store.get(LinkId("skewed-link")).exceptionOrNull()

                val typed = assertIs<UndecodableLinkException>(failure)
                assertEquals(LinkId("skewed-link"), typed.linkId)
                assertTrue(typed.reason.isNotBlank())
            }
        }

    @Test
    fun `a store with no door still returns the rows it could read`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database)
            store.upsert(vaultLink, at).getOrThrow()
            seedSkewedRow(door.database, "skewed-link")

            assertEquals(listOf(vaultLink), store.list().getOrThrow())
            assertTrue(
                door.repository.getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                    .getOrThrow()
                    .isEmpty(),
                "without a door the skip is unobserved rather than announced by someone else",
            )
        }
    }
}
