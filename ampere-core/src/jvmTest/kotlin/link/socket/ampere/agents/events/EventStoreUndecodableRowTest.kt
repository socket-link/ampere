package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventStoreFailure
import link.socket.ampere.agents.domain.event.PersistedStore
import link.socket.ampere.agents.domain.event.StoreRowUndecodableEvent
import link.socket.ampere.agents.events.utils.EventSerializationException
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

/**
 * Version skew at the `EventStore` boundary (AMPR-364): a row whose payload names an [Event]
 * subtype this build does not have.
 *
 * Before this ticket, `decode` threw `EventSerializationException` from inside a `Result.map`
 * block — and `Result.map` does not catch — so the exception escaped the `Result` boundary and
 * one bad row failed the whole query. Now it is one skipped row, counted on the result,
 * announced on `signals`, and published on the bus by the door that read it.
 *
 * Bodies use `runBlocking`: the store writes on a real IO dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventStoreUndecodableRowTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val eventSource = EventSource.Agent("agent-A")

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repo: EventRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        repo = EventRepository(DEFAULT_JSON, testScope, database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun task(id: String, seconds: Long = 2_000) = Event.TaskCreated(
        eventId = id,
        urgency = Urgency.LOW,
        timestamp = Instant.fromEpochSeconds(seconds),
        eventSource = eventSource,
        taskId = "task-$id",
        description = "a readable event",
        assignedTo = null,
    )

    /**
     * Writes the row a newer build would have written: a well-formed payload whose class
     * discriminator names an event this one has never heard of. Nothing about it is malformed —
     * this is exactly what an older binary reading a newer profile's database sees.
     */
    private fun seedRowFromANewerBuild(
        database: Database,
        eventId: String = UNKNOWN_ROW_ID,
        sequence: Long = 1,
    ) {
        database.eventStoreQueries.insertEvent(
            event_id = eventId,
            event_type = "EventFromANewerBuild",
            source_id = eventSource.getIdentifier(),
            timestamp = 1_000_000,
            payload = """{"type":"EventFromANewerBuild","eventId":"$eventId","urgency":"LOW"}""",
            run_id = null,
            sequence = sequence,
            caused_by = null,
            recorded_at = 1_000_000,
            truncated = 0L,
        )
    }

    /** Mirrors `EventStoreBudgetTest`: signals are hot with no replay, so subscribe first. */
    private fun CoroutineScope.collectSignals(
        repo: EventRepository,
    ): Pair<MutableList<EventStoreSignal>, Job> {
        val collected = mutableListOf<EventStoreSignal>()
        val job = launch(Dispatchers.Unconfined) { repo.signals.collect { collected += it } }
        return collected to job
    }

    @Test
    fun `a list query skips the row it cannot decode and returns the rest`() {
        runBlocking {
            seedRowFromANewerBuild(database)
            repo.saveEvent(task("evt-readable")).getOrThrow()

            val all = repo.getAllEvents().getOrThrow()

            assertEquals(listOf("evt-readable"), all.map { it.eventId })
            assertEquals(1, all.undecodableCount)
            assertEquals(UNKNOWN_ROW_ID, all.undecodable.single().rowId)
            assertTrue(
                all.undecodable.single().reason.isNotBlank(),
                "a skip with no reason is not diagnosable",
            )
        }
    }

    @Test
    fun `the skip reaches signals rather than passing unremarked`() {
        runBlocking {
            val (signals, job) = collectSignals(repo)
            seedRowFromANewerBuild(database)

            repo.getAllEvents().getOrThrow()
            job.cancel()

            val signal = assertIs<EventStoreSignal.RowUndecodable>(
                signals.single { it is EventStoreSignal.RowUndecodable },
            )
            assertEquals(UNKNOWN_ROW_ID, signal.eventId)
            assertEquals("EventFromANewerBuild", signal.eventType)
        }
    }

    @Test
    fun `an envelope query skips the row too and still reports the sequence of the rest`() {
        runBlocking {
            seedRowFromANewerBuild(database)
            repo.saveEvent(task("evt-readable")).getOrThrow()

            val stored = repo.getEventsSinceSequence(0).getOrThrow()

            assertEquals(listOf("evt-readable"), stored.map { it.event.eventId })
            assertEquals(1, stored.undecodableCount)
        }
    }

    @Test
    fun `a single read of an undecodable row returns the serialization classification`() {
        runBlocking {
            seedRowFromANewerBuild(database)

            val failure = assertIs<EventSerializationException>(
                repo.getEventById(UNKNOWN_ROW_ID).exceptionOrNull(),
                "the decode must come back through the Result rather than throwing past it",
            )

            assertEquals(EventStoreFailure.SERIALIZATION, EventStoreFailure.classify(failure))
            assertTrue(
                failure.message.orEmpty().contains(UNKNOWN_ROW_ID),
                "the failure has to name the row an operator would go look at",
            )
        }
    }

    @Test
    fun `a readable row next to the undecodable one still reads back on its own`() {
        runBlocking {
            seedRowFromANewerBuild(database)
            repo.saveEvent(task("evt-readable")).getOrThrow()

            val loaded = repo.getEventById("evt-readable").getOrThrow()

            assertEquals("task-evt-readable", assertIs<Event.TaskCreated>(loaded).taskId)
        }
    }

    @Test
    fun `a read through the door announces the skip on the bus exactly once`() {
        runBlocking {
            InMemoryEventApi.open(agentId = "reader", scope = testScope).use { door ->
                seedRowFromANewerBuild(door.database)
                door.api.publish(task("evt-readable")).getOrThrow()

                assertEquals(listOf("evt-readable"), door.api.getEventHistory().map { it.eventId })
                door.api.getEventHistory()
                door.api.getRecentEvents(since = null)

                val announced = door.repository
                    .getEventsByType(StoreRowUndecodableEvent.EVENT_TYPE)
                    .getOrThrow()
                val seen = assertIs<StoreRowUndecodableEvent>(announced.single())
                assertEquals(PersistedStore.EVENT_STORE, seen.store)
                assertEquals(UNKNOWN_ROW_ID, seen.rowId)
            }
        }
    }

    private companion object {
        const val UNKNOWN_ROW_ID = "evt-from-a-newer-build"
    }
}
