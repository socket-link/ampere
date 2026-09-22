package link.socket.ampere.plug.permission

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.db.Database
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest

class UserGrantStoreTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: UserGrantStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        store = SqlDelightUserGrantStore(Database(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `grant persists and lists active permissions`() = runTest {
        val permission = PlugPermission.KnowledgeQuery("workspace")

        store.grant(
            plugId = PlugId("plug-1"),
            permission = permission,
            grantedAt = Instant.fromEpochMilliseconds(1_000),
        ).getOrThrow()

        val grants = store.listGrants(PlugId("plug-1")).getOrThrow()

        assertEquals(listOf(permission), grants.granted)
        assertTrue(store.hasGrant(PlugId("plug-1"), permission).getOrThrow())
    }

    @Test
    fun `revoke leaves a tombstone instead of deleting the grant`() = runTest {
        val plugId = PlugId("plug-1")
        val permission = PlugPermission.NativeAction("open-url")

        store.grant(
            plugId = plugId,
            permission = permission,
            grantedAt = Instant.fromEpochMilliseconds(1_000),
        ).getOrThrow()
        store.revoke(
            plugId = plugId,
            permission = permission,
            revokedAt = Instant.fromEpochMilliseconds(2_000),
        ).getOrThrow()

        val grants = store.listGrants(plugId).getOrThrow()

        assertEquals(emptyList(), grants.granted)
        assertEquals(listOf(permission), grants.revoked)
        assertFalse(store.hasGrant(plugId, permission).getOrThrow())
    }

    @Test
    fun `gate denies a revoked permission as revoked, not missing`() = runTest {
        val plugId = PlugId("plug-1")
        val permission = PlugPermission.NativeAction("open-url")
        val manifest = PlugManifest(
            id = plugId,
            name = "Plug",
            version = "1.0.0",
            requiredPermissions = listOf(permission),
        )
        val toolCall = PlugToolCall(plugId = plugId, toolId = "open-url")

        store.grant(plugId, permission, Instant.fromEpochMilliseconds(1_000)).getOrThrow()
        store.revoke(plugId, permission, Instant.fromEpochMilliseconds(2_000)).getOrThrow()

        val grants = store.listGrants(plugId).getOrThrow()
        val result = PlugPermissionGate.check(toolCall, manifest, grants)

        assertEquals(GateResult.DenyRevoked(permission), result)
    }

    @Test
    fun `re-granting a revoked permission clears the tombstone`() = runTest {
        val plugId = PlugId("plug-1")
        val permission = PlugPermission.NativeAction("open-url")
        val manifest = PlugManifest(
            id = plugId,
            name = "Plug",
            version = "1.0.0",
            requiredPermissions = listOf(permission),
        )
        val toolCall = PlugToolCall(plugId = plugId, toolId = "open-url")

        store.grant(plugId, permission, Instant.fromEpochMilliseconds(1_000)).getOrThrow()
        store.revoke(plugId, permission, Instant.fromEpochMilliseconds(2_000)).getOrThrow()
        store.grant(plugId, permission, Instant.fromEpochMilliseconds(3_000)).getOrThrow()

        val grants = store.listGrants(plugId).getOrThrow()

        assertEquals(listOf(permission), grants.granted)
        assertEquals(emptyList(), grants.revoked)
        assertTrue(store.hasGrant(plugId, permission).getOrThrow())
        assertEquals(GateResult.Allow, PlugPermissionGate.check(toolCall, manifest, grants))
    }
}
