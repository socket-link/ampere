package link.socket.ampere.pause

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.PersistedStore
import link.socket.ampere.agents.domain.event.StoreRowUndecodableEvent
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.db.Database

/**
 * Version skew at the `PauseStore` boundary (AMPR-370, applying AMPR-364's rule): a stored
 * `AgentPause` naming an [EscalationChannel] variant this build does not have.
 *
 * Adding a variant is a documented breaking change for readers, so an older binary reading a
 * database a newer one wrote hits this without anyone extending anything. A skipped row must be
 * *said*, not swallowed: one pause raised by a newer build must not quietly shrink the list of
 * pending decisions a person is shown.
 *
 * Bodies use `runBlocking`: the door persists on a real IO dispatcher, and `runTest`'s virtual clock
 * would run ahead of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PauseStoreUndecodableRowTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())

    /** The same configuration [SqlDelightPauseStore] encodes with, so a seeded row is realistic. */
    private val storeJson = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val raisedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val timeout = 60.seconds
    private val beforeDeadline = raisedAt + 30.seconds

    private val pause = AgentPause(
        correlationId = "readable",
        reason = "Approve production deploy",
        urgency = PauseUrgency.Critical,
        suggestedChannels = listOf(EscalationChannel.Voice(prompt = "Approve the deploy?")),
        timeoutMillis = timeout.inWholeMilliseconds,
    )

    private fun door(): InMemoryEventApi.Handle =
        InMemoryEventApi.open(agentId = "pause-store", scope = doorScope)

    @Test
    fun `listOpen returns what it could read and announces the row it skipped`() = runBlocking {
        door().use { handle ->
            val store = SqlDelightPauseStore(handle.database, handle.api)
            store.raise(pause, raisedAt).getOrThrow()
            seedSkewedRow(handle.database, "skewed-pause")

            assertEquals(
                listOf("readable"),
                store.listOpen(beforeDeadline).getOrThrow().map { it.correlationId },
            )

            val announced = handle.repository
                .getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                .getOrThrow()
            val seen = assertIs<StoreRowUndecodableEvent>(announced.single())
            assertEquals(PersistedStore.PAUSE_STORE, seen.store)
            assertEquals("skewed-pause", seen.rowId)
            assertTrue(seen.reason.isNotBlank(), "a skip with no reason is not diagnosable")
        }
    }

    @Test
    fun `a row already announced is not announced again on the next read`() = runBlocking {
        door().use { handle ->
            val store = SqlDelightPauseStore(handle.database, handle.api)
            seedSkewedRow(handle.database, "skewed-pause")

            store.listOpen(beforeDeadline).getOrThrow()
            store.listOpen(beforeDeadline).getOrThrow()

            val announced = handle.repository
                .getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                .getOrThrow()
            assertEquals(1, announced.size, "the row is unreadable once — not once per read")
        }
    }

    /**
     * Writes a row exactly as a newer build would have: valid `AgentPause` JSON with one channel
     * variant this build cannot name. The [assertNotEquals] is a tripwire — if `Voice`'s wire name
     * ever moves, the seeded row stops being skewed and the assertions above would pass vacuously.
     */
    private fun seedSkewedRow(database: Database, correlationId: String) {
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
}
