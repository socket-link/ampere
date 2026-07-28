package link.socket.ampere.link

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonType
import link.socket.ampere.db.Database

class SqlDelightLinkStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: LinkStore

    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val later = Instant.fromEpochMilliseconds(1_700_000_500_000)

    private val googleLink = Link(
        id = LinkId("google-oauth"),
        transport = Transport.OAUTH_REST,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.ThirdParty("google"),
        scope = setOf(CanonType.CALENDAR_EVENT, CanonType.EMAIL_MESSAGE),
        credentialRef = CredentialRef("keychain://google"),
    )

    private val vaultLink = Link(
        id = LinkId("obsidian-vault"),
        transport = Transport.FOLDER_MOUNT,
        direction = LinkDirection.READ_WRITE,
        egress = EgressClass.OnDevice,
        scope = setOf(CanonType.NOTE),
    )

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        store = SqlDelightLinkStore(Database(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a Link survives a persistence round-trip unchanged`() = runTest {
        store.upsert(googleLink, at).getOrThrow()

        assertEquals(googleLink, store.get(googleLink.id).getOrThrow())
    }

    @Test
    fun `an absent Link reads back as null rather than failing`() = runTest {
        assertNull(store.get(LinkId("nope")).getOrThrow())
    }

    @Test
    fun `upsert replaces rather than duplicating`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.upsert(googleLink.copy(direction = LinkDirection.READ), later).getOrThrow()

        val all = store.list().getOrThrow()
        assertEquals(1, all.size)
        assertEquals(LinkDirection.READ, all.single().direction)
    }

    @Test
    fun `listByTransport uses the denormalized index column`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.upsert(vaultLink, at).getOrThrow()

        assertEquals(
            listOf(vaultLink),
            store.listByTransport(Transport.FOLDER_MOUNT).getOrThrow(),
        )
        assertEquals(2, store.list().getOrThrow().size)
    }

    @Test
    fun `grants round-trip per Plug`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.grant("calendar-plug", googleLink.id, at).getOrThrow()

        val grants = store.grantsForPlug("calendar-plug").getOrThrow()

        assertTrue(grants.isGranted(googleLink.id))
        assertFalse(grants.isRevoked(googleLink.id))
        assertEquals(at, grants.grantFor(googleLink.id)?.grantedAt)
    }

    @Test
    fun `revoking a grant preserves the original grant timestamp`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.grant("calendar-plug", googleLink.id, at).getOrThrow()
        store.revokeGrant("calendar-plug", googleLink.id, later).getOrThrow()

        val grant = store.grantsForPlug("calendar-plug").getOrThrow().grantFor(googleLink.id)

        assertEquals(at, grant?.grantedAt)
        assertEquals(later, grant?.revokedAt)
    }

    @Test
    fun `revoking a Link cascades across Plugs and persists on the Link itself`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.grant("calendar-plug", googleLink.id, at).getOrThrow()
        store.grant("gmail-plug", googleLink.id, at).getOrThrow()

        val affected = store.revokeLink(googleLink.id, later).getOrThrow()

        assertEquals(setOf("calendar-plug", "gmail-plug"), affected.toSet())
        assertTrue(store.get(googleLink.id).getOrThrow()!!.isRevoked)
        assertTrue(store.grantsForPlug("calendar-plug").getOrThrow().isRevoked(googleLink.id))
        assertTrue(store.grantsForPlug("gmail-plug").getOrThrow().isRevoked(googleLink.id))
    }

    @Test
    fun `an already-revoked grant is not re-reported by a second cascade`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.grant("calendar-plug", googleLink.id, at).getOrThrow()

        store.revokeLink(googleLink.id, later).getOrThrow()
        val second = store.revokeLink(googleLink.id, later).getOrThrow()

        assertTrue(second.isEmpty())
    }

    @Test
    fun `deleting a Link removes its grants too`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.grant("calendar-plug", googleLink.id, at).getOrThrow()

        store.delete(googleLink.id).getOrThrow()

        assertNull(store.get(googleLink.id).getOrThrow())
        assertTrue(store.grantsForLink(googleLink.id).getOrThrow().isEmpty())
    }

    @Test
    fun `a Link persisted through the store resolves through the service`() = runTest {
        store.upsert(googleLink, at).getOrThrow()
        store.grant("calendar-plug", googleLink.id, at).getOrThrow()

        val service = LinkResolutionService(
            linkStore = store,
            platform = PlatformTarget.ANDROID,
        )

        val resolution = service.resolveOne(
            plugId = "calendar-plug",
            requirement = LinkRequirement(
                name = "calendar",
                transport = Transport.OAUTH_REST,
                direction = LinkDirection.READ_WRITE,
                minimumScope = setOf(CanonType.CALENDAR_EVENT),
            ),
        ).getOrThrow()

        assertEquals(
            googleLink,
            (resolution as LinkResolution.Resolved).link,
        )
    }
}
