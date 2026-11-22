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
 * Version skew at the `Links` boundary in both directions, for the `EgressClass` member added
 * in AMPR-517.
 *
 * Forwards: every row a build without `ExternallyGoverned` wrote still decodes. The fixtures
 * are `link_json` text exactly as `v0.26.0`'s `SqlDelightLinkStore` encoded it (class
 * discriminator `type`, defaults written), one per member that build had.
 *
 * Backwards: a reader that predates a member cannot be run from this tree, but its code path
 * can. `link/` did not change between `v0.26.0` and the commit that added the member, so a
 * discriminator this build does not know takes the same road here that
 * `egress.externally_governed` takes there: the sealed serializer refuses it, `list` skips the
 * row and names it on the bus, `get` fails typed. The row is never read as another member.
 *
 * Bodies use `runBlocking`: the door persists on a real IO dispatcher, and `runTest`'s virtual
 * clock would run ahead of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkStoreEgressSkewTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())

    /** The same configuration [SqlDelightLinkStore] encodes with, so a seeded row is realistic. */
    private val storeJson = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val orchestratorLink = Link(
        id = LinkId("mcp:orchestrator"),
        transport = Transport.MCP,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.ExternallyGoverned(app = "orchestrator"),
        scope = setOf(CanonType.WORK_ITEM),
        credentialRef = CredentialRef.forLink(LinkId("mcp:orchestrator")),
    )

    private fun door(): InMemoryEventApi.Handle =
        InMemoryEventApi.open(agentId = "link-store", scope = doorScope)

    private fun seedRow(database: Database, linkId: String, transport: Transport, linkJson: String) {
        database.linksQueries.upsertLink(
            link_id = linkId,
            transport = transport.name,
            link_json = linkJson,
            updated_at = at.toEpochMilliseconds(),
        )
    }

    @Test
    fun `rows written before the member existed decode to the Links they were`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database, door.api)
            seedRow(door.database, "mcp:work-source", Transport.MCP, V0_26_0_THIRD_PARTY_ROW)
            seedRow(door.database, "obsidian-vault", Transport.FOLDER_MOUNT, V0_26_0_ON_DEVICE_ROW)
            seedRow(door.database, "socket-sync", Transport.OAUTH_REST, V0_26_0_FIRST_PARTY_ROW)

            val stored = store.list().getOrThrow().associateBy { it.id.value }

            assertEquals(
                Link(
                    id = LinkId("mcp:work-source"),
                    transport = Transport.MCP,
                    direction = LinkDirection.READ_WRITE,
                    egress = EgressClass.ThirdParty("work-source"),
                    scope = setOf(CanonType.WORK_ITEM),
                    credentialRef = CredentialRef("ampere.link:mcp:work-source"),
                ),
                stored["mcp:work-source"],
            )
            assertEquals(
                Link(
                    id = LinkId("obsidian-vault"),
                    transport = Transport.FOLDER_MOUNT,
                    direction = LinkDirection.READ_WRITE,
                    egress = EgressClass.OnDevice,
                    scope = setOf(CanonType.NOTE),
                    folderRef = FolderRef("mount-1"),
                ),
                stored["obsidian-vault"],
            )
            assertEquals(
                Link(
                    id = LinkId("socket-sync"),
                    transport = Transport.OAUTH_REST,
                    direction = LinkDirection.READ,
                    egress = EgressClass.FirstParty,
                    scope = setOf(CanonType.CALENDAR_EVENT),
                    revokedAt = at,
                ),
                stored["socket-sync"],
            )
            assertEquals(3, stored.size)
            assertTrue(
                door.repository.getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                    .getOrThrow()
                    .isEmpty(),
                "a row an older build wrote was skipped",
            )
        }
    }

    @Test
    fun `an externally governed Link is stored and read back as itself`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database, door.api)

            store.upsert(orchestratorLink, at).getOrThrow()

            assertEquals(orchestratorLink, store.get(orchestratorLink.id).getOrThrow())
            assertEquals(listOf(orchestratorLink), store.listByTransport(Transport.MCP).getOrThrow())
        }
    }

    @Test
    fun `a reader that does not know an egress member skips the row and says so`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database, door.api)
            seedRow(door.database, "mcp:work-source", Transport.MCP, V0_26_0_THIRD_PARTY_ROW)
            seedUnknownEgressRow(door.database, "mcp:orchestrator")

            val readable = store.list().getOrThrow()

            // Skipped, not downgraded: no Link in the result claims an egress the row never had.
            assertEquals(listOf(LinkId("mcp:work-source")), readable.map { it.id })

            val announced = door.repository
                .getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                .getOrThrow()
            val seen = assertIs<StoreRowUndecodableEvent>(announced.single())
            assertEquals(PersistedStore.LINK_STORE, seen.store)
            assertEquals("mcp:orchestrator", seen.rowId)
            assertTrue(seen.reason.isNotBlank(), "a skip with no reason is not diagnosable")
        }
    }

    @Test
    fun `get on a row with an unknown egress member is a typed failure`() = runBlocking {
        door().use { door ->
            val store = SqlDelightLinkStore(door.database, door.api)
            seedUnknownEgressRow(door.database, "mcp:orchestrator")

            val failure = store.get(LinkId("mcp:orchestrator")).exceptionOrNull()

            val typed = assertIs<UndecodableLinkException>(failure)
            assertEquals(LinkId("mcp:orchestrator"), typed.linkId)
            assertTrue(typed.reason.isNotBlank())
        }
    }

    /**
     * Writes a row exactly as a newer build would have: valid `Link` JSON whose egress
     * discriminator this build cannot name. The [assertNotEquals] is a tripwire: if the member's
     * wire name ever moves, the seeded row stops being skewed and the assertions above would
     * pass vacuously.
     */
    private fun seedUnknownEgressRow(database: Database, linkId: String) {
        val valid = storeJson.encodeToString(
            Link.serializer(),
            orchestratorLink.copy(id = LinkId(linkId)),
        )
        val skewed = valid.replace("egress.externally_governed", "egress.from_a_newer_build")
        assertNotEquals(valid, skewed, "the seeded row is no longer skewed")

        seedRow(database, linkId, Transport.MCP, skewed)
    }

    private companion object {

        const val V0_26_0_THIRD_PARTY_ROW =
            """{"id":"mcp:work-source","transport":"mcp","direction":"read_write",""" +
                """"egress":{"type":"egress.third_party","provider":"work-source"},""" +
                """"scope":["work_item"],""" +
                """"credentialRef":{"keychainAlias":"ampere.link:mcp:work-source","revokedAt":null},""" +
                """"folderRef":null,"revokedAt":null}"""

        const val V0_26_0_ON_DEVICE_ROW =
            """{"id":"obsidian-vault","transport":"folder_mount","direction":"read_write",""" +
                """"egress":{"type":"egress.on_device"},"scope":["note"],"credentialRef":null,""" +
                """"folderRef":{"mountId":"mount-1","revokedAt":null},"revokedAt":null}"""

        const val V0_26_0_FIRST_PARTY_ROW =
            """{"id":"socket-sync","transport":"oauth_rest","direction":"read",""" +
                """"egress":{"type":"egress.first_party"},"scope":["calendar_event"],""" +
                """"credentialRef":null,"folderRef":null,"revokedAt":"2023-11-14T22:13:20Z"}"""
    }
}
