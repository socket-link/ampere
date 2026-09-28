package link.socket.ampere.pause

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database

/**
 * The durability contract of [SqlDelightPauseStore] (AMPR-370).
 *
 * Every test runs against a database *file*, and [restart] closes the connection and opens a new
 * one over the same file. That is the point of the store: before it existed a pending human
 * decision lived only in the process that raised it, so a test that never closes a connection
 * would pass against an in-memory registry too.
 *
 * Bodies use `runBlocking`: the store commits on a real IO dispatcher, and `runTest`'s virtual
 * clock would run ahead of it. Every read passes `now` explicitly — the defaults read the wall
 * clock, which is years past these fixtures and would expire everything.
 */
class PauseStoreTest {

    private val tempDir: Path = createTempDirectory(prefix = "pause-store")
    private val dbPath: Path = tempDir / "ampere.db"

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private val raisedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val timeout = 60.seconds
    private val beforeDeadline = raisedAt + 30.seconds
    private val afterDeadline = raisedAt + 61.seconds

    private val pause = AgentPause(
        correlationId = "deploy-prod",
        reason = "Approve production deploy of v0.5.0",
        urgency = PauseUrgency.Critical,
        suggestedChannels = listOf(
            EscalationChannel.Voice(prompt = "Approve the v0.5.0 production deploy?"),
            EscalationChannel.Push(
                notificationCategory = "deploy",
                title = "Approve production deploy",
                body = "v0.5.0 is ready to ship.",
            ),
        ),
        timeoutMillis = timeout.inWholeMilliseconds,
        fallbackUrl = "https://ampere.example/pause/deploy-prod",
    )

    /**
     * One process's lifetime over the shared database file: open, bring the schema current, run,
     * close. Calling it twice is a restart, and nothing in-process survives between calls.
     */
    private suspend fun <T> restart(block: suspend (Database) -> T): T =
        JdbcSqliteDriver("jdbc:sqlite:$dbPath").use { driver ->
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            block(Database(driver))
        }

    private fun Database.store(): PauseStore = SqlDelightPauseStore(this)

    @Test
    fun `a raised pause is found and answered by correlation id after a restart`() = runBlocking<Unit> {
        val raised = restart { db -> db.store().raise(pause, raisedAt).getOrThrow() }

        assertEquals(raisedAt, raised.raisedAt)
        assertEquals(raisedAt + timeout, raised.expiresAt)
        assertTrue(raised.isOpen)

        // A second process, holding nothing but the correlation id — the whole address.
        val resolution = restart { db ->
            val store = db.store()
            val found = store.get(pause.correlationId, beforeDeadline).getOrThrow()
            assertEquals(pause, found?.pause, "the pause did not survive the restart intact")
            assertTrue(found!!.isOpen)

            store.resolve(
                AgentPauseResponse.Approved(pause.correlationId, payload = "ship-it"),
                resolvedAt = beforeDeadline,
            ).getOrThrow()
        }

        assertTrue(resolution.settledByThisCall)
        assertEquals(beforeDeadline, resolution.settledAt)

        // A third process: the decision is still made, and still says who made it.
        restart { db ->
            val settled = db.store().get(pause.correlationId, afterDeadline).getOrThrow()
            val approved = assertIs<AgentPauseResponse.Approved>(settled?.response)
            assertEquals("ship-it", approved.payload)
            assertEquals(beforeDeadline, settled?.settledAt)
            assertFalse(settled!!.isOpen)
            assertFalse(settled.isTimedOut, "an answered pause must not read as expired")
        }
    }

    @Test
    fun `an expired pause settles durably to TimedOut rather than staying open`() = runBlocking<Unit> {
        restart { db -> db.store().raise(pause, raisedAt).getOrThrow() }

        // Nobody swept; the first read after the deadline settles it.
        restart { db ->
            val record = db.store().get(pause.correlationId, afterDeadline).getOrThrow()
            assertIs<AgentPauseResponse.TimedOut>(record?.response)
            assertEquals(afterDeadline, record?.settledAt)
        }

        // The settlement was written, not derived: a reader with an earlier clock sees it too.
        restart { db ->
            val record = db.store().get(pause.correlationId, beforeDeadline).getOrThrow()
            assertTrue(record!!.isTimedOut)
            assertFalse(record.isOpen)
            assertEquals(afterDeadline, record.settledAt)
        }
    }

    @Test
    fun `the expiry sweep settles with nobody reading and the settlement survives a restart`() =
        runBlocking<Unit> {
            restart { db -> db.store().raise(pause, raisedAt).getOrThrow() }

            val swept = restart { db -> db.store().expire(afterDeadline).getOrThrow() }
            assertEquals(listOf(pause.correlationId), swept)

            restart { db ->
                assertTrue(db.store().get(pause.correlationId, afterDeadline).getOrThrow()!!.isTimedOut)
            }
        }

    @Test
    fun `the sweep leaves a pause that is not yet due alone`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()

            assertEquals(emptyList<PauseCorrelationId>(), store.expire(beforeDeadline).getOrThrow())
            assertTrue(store.get(pause.correlationId, beforeDeadline).getOrThrow()!!.isOpen)
        }
    }

    @Test
    fun `a second sweep does not claim a pause the first already expired`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()

            assertEquals(listOf(pause.correlationId), store.expire(afterDeadline).getOrThrow())
            assertEquals(emptyList<PauseCorrelationId>(), store.expire(afterDeadline + 1.seconds).getOrThrow())
        }
    }

    @Test
    fun `a response arriving after expiry reports the answer that actually won`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()
            store.expire(afterDeadline).getOrThrow()

            val late = store.resolve(
                AgentPauseResponse.Approved(pause.correlationId),
                resolvedAt = afterDeadline + 10.seconds,
            ).getOrThrow()

            assertFalse(late.settledByThisCall, "expiry settled first — this call must say so")
            assertIs<AgentPauseResponse.TimedOut>(late.response)
            assertEquals(afterDeadline, late.settledAt)
        }
    }

    @Test
    fun `a second response does not overwrite the first`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()

            val first = store.resolve(
                AgentPauseResponse.Approved(pause.correlationId, payload = "yes"),
                resolvedAt = beforeDeadline,
            ).getOrThrow()
            assertTrue(first.settledByThisCall)

            val second = store.resolve(
                AgentPauseResponse.Rejected(pause.correlationId, reason = "changed my mind"),
                resolvedAt = beforeDeadline + 1.seconds,
            ).getOrThrow()

            assertFalse(second.settledByThisCall)
            val approved = assertIs<AgentPauseResponse.Approved>(second.response)
            assertEquals("yes", approved.payload)
        }
    }

    @Test
    fun `resolving a correlation id the store never saw is a typed failure`() = runBlocking<Unit> {
        restart { db ->
            val failure = db.store()
                .resolve(AgentPauseResponse.Approved("never-raised"), beforeDeadline)
                .exceptionOrNull()

            val typed = assertIs<UnknownPauseException>(failure)
            assertEquals("never-raised", typed.correlationId)
        }
    }

    @Test
    fun `get on a correlation id the store never saw is a null rather than a failure`() = runBlocking<Unit> {
        restart { db ->
            assertNull(db.store().get("never-raised", beforeDeadline).getOrThrow())
        }
    }

    @Test
    fun `re-raising an identical open pause is the same raise`() = runBlocking<Unit> {
        val first = restart { db -> db.store().raise(pause, raisedAt).getOrThrow() }

        // The raiser died without learning whether its write landed and retried with a later clock.
        val second = restart { db -> db.store().raise(pause, raisedAt + 5.seconds).getOrThrow() }

        assertEquals(first, second, "a retried raise must not move the deadline")
        assertEquals(raisedAt + timeout, second.expiresAt)
    }

    @Test
    fun `raising over a settled decision is refused`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()
            store.resolve(AgentPauseResponse.Rejected(pause.correlationId), beforeDeadline).getOrThrow()

            val failure = store.raise(pause, afterDeadline).exceptionOrNull()

            val typed = assertIs<PauseCorrelationIdInUseException>(failure)
            assertTrue(typed.settled)
            assertIs<AgentPauseResponse.Rejected>(
                store.get(pause.correlationId, afterDeadline).getOrThrow()?.response,
                "the settled decision must still be the stored one",
            )
        }
    }

    @Test
    fun `raising a different pause under a taken id is refused`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()

            val failure = store.raise(pause.copy(reason = "something else"), raisedAt).exceptionOrNull()

            val typed = assertIs<PauseCorrelationIdInUseException>(failure)
            assertFalse(typed.settled)
            assertEquals(
                pause.reason,
                store.get(pause.correlationId, beforeDeadline).getOrThrow()?.pause?.reason,
            )
        }
    }

    @Test
    fun `listOpen excludes settled pauses and orders by deadline`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            val soon = pause.copy(correlationId = "soon", timeoutMillis = 10.seconds.inWholeMilliseconds)
            val later = pause.copy(correlationId = "later", timeoutMillis = 40.seconds.inWholeMilliseconds)
            val answered = pause.copy(correlationId = "answered")

            store.raise(later, raisedAt).getOrThrow()
            store.raise(soon, raisedAt).getOrThrow()
            store.raise(answered, raisedAt).getOrThrow()
            store.resolve(AgentPauseResponse.Approved("answered"), raisedAt + 1.seconds).getOrThrow()

            val open = store.listOpen(raisedAt + 5.seconds).getOrThrow()

            assertEquals(listOf("soon", "later"), open.map { it.correlationId })
        }
    }

    @Test
    fun `listOpen settles what it finds expired instead of returning it`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()

            assertEquals(emptyList<PauseRecord>(), store.listOpen(afterDeadline).getOrThrow())
            assertTrue(store.get(pause.correlationId, afterDeadline).getOrThrow()!!.isTimedOut)
        }
    }

    @Test
    fun `delete forgets the row`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()
            store.delete(pause.correlationId).getOrThrow()
        }

        restart { db ->
            assertNull(db.store().get(pause.correlationId, beforeDeadline).getOrThrow())
            // Deleting what is not there is not an error, and the id is free again.
            db.store().delete(pause.correlationId).getOrThrow()
            db.store().raise(pause, raisedAt).getOrThrow()
        }
    }

    @Test
    fun `a pause this build cannot decode still expires and still answers`() = runBlocking<Unit> {
        restart { db -> seedSkewedRow(db) }

        // The whole reason `expires_at` and `correlation_id` are columns rather than payload fields.
        restart { db ->
            assertEquals(listOf(SKEWED_ID), db.store().expire(afterDeadline).getOrThrow())
        }

        restart { db ->
            val failure = db.store().get(SKEWED_ID, afterDeadline).exceptionOrNull()
            val typed = assertIs<UndecodablePauseException>(failure)
            assertEquals(SKEWED_ID, typed.correlationId)
            assertTrue(typed.reason.isNotBlank(), "a skip with no reason is not diagnosable")
        }

        // And a person can still answer one, even though nothing can read what it asked.
        restart { db -> seedSkewedRow(db, correlationId = "$SKEWED_ID-2") }
        restart { db ->
            val resolution = db.store()
                .resolve(AgentPauseResponse.Rejected("$SKEWED_ID-2"), beforeDeadline)
                .getOrThrow()

            assertTrue(resolution.settledByThisCall)
            assertIs<AgentPauseResponse.Rejected>(resolution.response)
        }
    }

    @Test
    fun `listOpen skips an undecodable row rather than failing the query`() = runBlocking<Unit> {
        restart { db ->
            seedSkewedRow(db)
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()

            val open = store.listOpen(beforeDeadline).getOrThrow()

            assertEquals(listOf(pause.correlationId), open.map { it.correlationId })
        }
    }

    @Test
    fun `a pause whose timeout is zero is already expired when it is raised`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            val instant = pause.copy(correlationId = "instant", timeoutMillis = 0)
            val raised = store.raise(instant, raisedAt).getOrThrow()

            assertEquals(raisedAt, raised.expiresAt)
            assertTrue(store.get("instant", raisedAt).getOrThrow()!!.isTimedOut)
        }
    }

    @Test
    fun `a pause is still open in the millisecond before its deadline`() = runBlocking<Unit> {
        restart { db ->
            val store = db.store()
            store.raise(pause, raisedAt).getOrThrow()

            assertTrue(store.get(pause.correlationId, raisedAt + timeout - 1.milliseconds).getOrThrow()!!.isOpen)
            assertTrue(store.get(pause.correlationId, raisedAt + timeout).getOrThrow()!!.isTimedOut)
        }
    }

    /**
     * Writes a row exactly as a newer build would have: valid `AgentPause` JSON with one
     * [EscalationChannel] variant this build cannot name. Adding a variant is a documented breaking
     * change for readers, so this is version skew and not corruption.
     *
     * The [assertNotEquals] is a tripwire — if `Voice`'s wire name ever moves, the seeded row stops
     * being skewed and every assertion against it would pass vacuously.
     */
    private fun seedSkewedRow(database: Database, correlationId: String = SKEWED_ID) {
        val storeJson = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val valid = storeJson.encodeToString(
            AgentPause.serializer(),
            pause.copy(correlationId = correlationId),
        )
        val skewed = valid.replace(
            "link.socket.ampere.pause.EscalationChannel.Voice",
            "link.socket.ampere.pause.EscalationChannel.Hologram",
        )
        assertNotEquals(valid, skewed, "the seeded row is no longer skewed")

        database.pauseStoreQueries.insertPause(
            correlation_id = correlationId,
            pause_json = skewed,
            raised_at = raisedAt.toEpochMilliseconds(),
            expires_at = (raisedAt + timeout).toEpochMilliseconds(),
        )
    }

    private companion object {
        const val SKEWED_ID = "raised-by-a-newer-build"
    }
}
