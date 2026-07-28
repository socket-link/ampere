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
            plugId = "plug-1",
            permission = permission,
            grantedAt = Instant.fromEpochMilliseconds(1_000),
        ).getOrThrow()

        val grants = store.listGrants("plug-1").getOrThrow()

        assertEquals(listOf(permission), grants.granted)
        assertTrue(store.hasGrant("plug-1", permission).getOrThrow())
    }

    @Test
    fun `revoke removes persisted grant`() = runTest {
        val permission = PlugPermission.NativeAction("open-url")

        store.grant(
            plugId = "plug-1",
            permission = permission,
            grantedAt = Instant.fromEpochMilliseconds(1_000),
        ).getOrThrow()
        store.revoke("plug-1", permission).getOrThrow()

        val grants = store.listGrants("plug-1").getOrThrow()

        assertEquals(emptyList(), grants.granted)
        assertFalse(store.hasGrant("plug-1", permission).getOrThrow())
    }
}
