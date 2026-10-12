package link.socket.ampere.propel

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.event.PersistedStore
import link.socket.ampere.agents.domain.event.StoreRowUndecodableEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.data.createJvmDriver
import link.socket.ampere.db.Database

/**
 * [HeldRunStoreContractTest] against [SqlDelightHeldRunStore], over a database *file* opened the
 * way the JVM opens one in production (WAL, a busy timeout, migrated), plus what only a store that
 * keeps a payload can show: a record outliving its process, and a row this build cannot read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldRunStoreSqlDelightTest : HeldRunStoreContractTest() {

    private val tempDir: Path = createTempDirectory(prefix = "held-run-store")
    private val dbPath: Path = tempDir / "ampere.db"
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val doorScope = TestScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        tempDir.toFile().deleteRecursively()
    }

    /** One process's connection to the shared file. Opening another is a restart. */
    private fun openDriver(): JdbcSqliteDriver = createJvmDriver(dbPath.toString()).also { drivers += it }

    override fun newStore(): HeldRunStore = SqlDelightHeldRunStore(Database(openDriver()))

    override fun corruptPayload(runId: String) = skewPayload(drivers.last(), runId)

    @Test
    fun `a held run outlives the process that held it and is claimed by the next`() = runBlocking<Unit> {
        val run = heldRun()
        val first = openDriver()
        SqlDelightHeldRunStore(Database(first)).hold(run).getOrThrow()
        first.close()
        drivers -= first

        val next = SqlDelightHeldRunStore(Database(openDriver()))
        assertEquals(run, next.get(run.runId).getOrThrow())
        assertEquals(HeldRunClaim.Claimed(run), next.claim(run.runId, claimedAt).getOrThrow())
    }

    @Test
    fun `an undecodable row is not claimed and get fails typed and one event is published`() = runBlocking<Unit> {
        InMemoryEventApi.open(agentId = "held-run-store", scope = doorScope).use { handle ->
            val store = SqlDelightHeldRunStore(handle.database, handle.api)
            val run = heldRun()
            store.hold(run).getOrThrow()
            skewPayload(handle.driver, run.runId)

            val getFailure = assertIs<UndecodableHeldRunException>(store.get(run.runId).exceptionOrNull())
            assertEquals(run.runId, getFailure.runId)
            val claimFailure = assertIs<UndecodableHeldRunException>(
                store.claim(run.runId, claimedAt).exceptionOrNull(),
            )
            assertEquals(run.runId, claimFailure.runId)

            val summary = store.list().getOrThrow().single()
            assertNull(summary.claimedAt, "a row this build cannot read is left for a build that can")

            val announced = handle.repository
                .getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                .getOrThrow()
            val seen = assertIs<StoreRowUndecodableEvent>(announced.single(), "once per row, not per read")
            assertEquals(PersistedStore.HELD_RUN_STORE, seen.store)
            assertEquals(run.runId, seen.rowId)

            store.delete(run.runId).getOrThrow()
            assertEquals(emptyList<HeldRunSummary>(), store.list().getOrThrow())
        }
    }

    /**
     * Rewrite the stored payload as a newer build would have written it: valid JSON naming a
     * [link.socket.ampere.agents.domain.outcome.StepOutcome] variant this build does not have. The
     * [assertNotEquals] is a tripwire — if `Held`'s wire name moves, the row stops being skewed and
     * every test using this would pass vacuously.
     */
    private fun skewPayload(driver: SqlDriver, runId: String) {
        val stored = driver.executeQuery(
            identifier = null,
            sql = "SELECT held_run_json FROM HeldRunStore WHERE run_id = ?",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(checkNotNull(cursor.getString(0)) { "no held run '$runId'" })
            },
            parameters = 1,
        ) { bindString(0, runId) }.value
        val skewed = stored.replace(
            "link.socket.ampere.agents.domain.outcome.StepOutcome.Held",
            "link.socket.ampere.agents.domain.outcome.StepOutcome.Deferred",
        )
        assertNotEquals(stored, skewed, "the seeded row is no longer skewed")

        driver.execute(
            identifier = null,
            sql = "UPDATE HeldRunStore SET held_run_json = ? WHERE run_id = ?",
            parameters = 2,
        ) {
            bindString(0, skewed)
            bindString(1, runId)
        }
    }
}
