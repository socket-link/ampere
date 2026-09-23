package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventStoreEvent
import link.socket.ampere.agents.domain.event.EventStoreFailure
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.agents.events.utils.EventSerializationException
import link.socket.ampere.agents.events.utils.SilentEventLogger
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.util.TRUNCATION_MARKER

/**
 * The AMPR-301 size contract on `EventStore` writes: a per-event bound enforced by
 * truncate-and-flag, a whole-store bound enforced by oldest-first rotation with a durable
 * marker, and a persistence failure that is announced rather than swallowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventStoreBudgetTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val json = DEFAULT_JSON
    private val eventSource = EventSource.Agent("agent-budget")

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun task(
        id: String,
        description: String = "a bounded description",
        seconds: Long = 1_000,
    ) = Event.TaskCreated(
        eventId = id,
        urgency = Urgency.LOW,
        timestamp = Instant.fromEpochSeconds(seconds),
        eventSource = eventSource,
        taskId = "task-$id",
        description = description,
        assignedTo = null,
    )

    /**
     * Collects [EventRepository.signals] from before the first write. The flow is hot with no
     * replay — a signal emitted before anyone is collecting is gone, which is the point of it
     * being a signal rather than a record — so every test that asserts on one subscribes first.
     */
    private fun CoroutineScope.collectSignals(repo: EventRepository): Pair<MutableList<EventStoreSignal>, Job> {
        val collected = mutableListOf<EventStoreSignal>()
        val job = launch(Dispatchers.Unconfined) { repo.signals.collect { collected += it } }
        return collected to job
    }

    // ==================== Per-event budget ====================

    @Test
    fun `an event within the per-event budget is stored whole and unflagged`() {
        runBlocking {
            val repo = EventRepository(json, testScope, database)

            val stored = repo.saveEvent(task("evt-small")).getOrThrow()

            assertFalse(stored.truncated)
            val row = database.eventStoreQueries.getEventById("evt-small").executeAsOne()
            assertEquals(0L, row.truncated)
            val loaded = repo.getEventById("evt-small").getOrThrow()
            assertEquals("a bounded description", assertIs<Event.TaskCreated>(loaded).description)
        }
    }

    @Test
    fun `an oversized payload is cut at its string leaves, flagged, and still decodes`() {
        runBlocking {
            val repo = EventRepository(
                json = json,
                scope = testScope,
                database = database,
                maxEventBytes = 500,
                maxStringFieldChars = 50,
            )

            val stored = repo.saveEvent(task("evt-huge", description = "x".repeat(4_000))).getOrThrow()

            assertTrue(stored.truncated, "the row should be flagged as cut")
            val row = database.eventStoreQueries.getEventById("evt-huge").executeAsOne()
            assertEquals(1L, row.truncated)

            // The whole point of cutting leaves rather than the payload: it still decodes.
            val loaded = assertIs<Event.TaskCreated>(repo.getEventById("evt-huge").getOrThrow())
            assertTrue(loaded.description.endsWith(TRUNCATION_MARKER))
            assertEquals(50 + TRUNCATION_MARKER.length, loaded.description.length)
            assertEquals("task-evt-huge", loaded.taskId)
        }
    }

    @Test
    fun `cutting a payload announces what was cut`() {
        runBlocking {
            val repo = EventRepository(
                json = json,
                scope = testScope,
                database = database,
                maxEventBytes = 500,
                maxStringFieldChars = 50,
            )
            val (signals, job) = collectSignals(repo)

            repo.saveEvent(task("evt-huge", description = "x".repeat(4_000))).getOrThrow()
            delay(200)
            job.cancel()

            val truncation = signals.filterIsInstance<EventStoreSignal.PayloadTruncated>().single()
            assertEquals("evt-huge", truncation.eventId)
            assertEquals(Event.TaskCreated.EVENT_TYPE, truncation.eventType)
            assertTrue(
                truncation.storedBytes < truncation.originalBytes,
                "stored ${truncation.storedBytes} should be under original ${truncation.originalBytes}",
            )
        }
    }

    @Test
    fun `an oversized payload with no long string leaf is stored whole rather than corrupted`() {
        runBlocking {
            // The per-event bound is best-effort: there is nothing here to cut that would not
            // change the JSON's shape, and an undecodable row is worse than an oversized one.
            val repo = EventRepository(
                json = json,
                scope = testScope,
                database = database,
                maxEventBytes = 10,
                maxStringFieldChars = 100_000,
            )

            val stored = repo.saveEvent(task("evt-wide")).getOrThrow()

            assertFalse(stored.truncated)
            val loaded = assertIs<Event.TaskCreated>(repo.getEventById("evt-wide").getOrThrow())
            assertEquals("a bounded description", loaded.description)
        }
    }

    // ==================== Whole-store budget ====================

    @Test
    fun `passing the store budget rotates the oldest events out and keeps the newest`() {
        runBlocking {
            val repo = tinyStoreRepository()

            repeat(EVENT_COUNT) { index ->
                repo.saveEvent(task("evt-${index.toString().padStart(2, '0')}")).getOrThrow()
            }

            val remaining = repo.getAllEvents().getOrThrow()
            assertTrue(
                remaining.size < EVENT_COUNT,
                "the store should have rotated; still holds all $EVENT_COUNT events",
            )
            assertNotNull(
                repo.getEventById("evt-${(EVENT_COUNT - 1).toString().padStart(2, '0')}").getOrThrow(),
                "the newest event must always be admitted, even by the sweep its own write triggered",
            )
            assertNull(
                repo.getEventById("evt-00").getOrThrow(),
                "the oldest event should be the first to go",
            )
            assertTrue(
                database.eventStoreQueries.totalPayloadBytes().executeAsOne() <= MAX_STORE_BYTES,
                "the store should be back under budget",
            )
        }
    }

    @Test
    fun `a rotation leaves a durable marker that accounts for every dropped event`() {
        runBlocking {
            val repo = tinyStoreRepository()

            repeat(EVENT_COUNT) { index ->
                repo.saveEvent(task("evt-${index.toString().padStart(2, '0')}")).getOrThrow()
            }

            val records = repo.getPruningRecords().getOrThrow()
            assertTrue(records.isNotEmpty(), "dropping events without a marker is the silent truncation")

            val remaining = repo.getAllEvents().getOrThrow().size
            val dropped = database.eventStorePruningQueries.totalDroppedEvents().executeAsOne()
            assertEquals(EVENT_COUNT.toLong(), dropped + remaining)

            val newest = records.first()
            assertTrue(newest.first_sequence <= newest.last_sequence)
            assertTrue(newest.dropped_bytes > 0)
            assertEquals(newest.store_bytes_before - newest.dropped_bytes, newest.store_bytes_after)
            assertEquals(
                database.eventStorePruningQueries.lastPrunedSequence().executeAsOne(),
                records.maxOf { it.last_sequence },
            )
        }
    }

    @Test
    fun `a rotation announces itself`() {
        runBlocking {
            val repo = tinyStoreRepository()
            val (signals, job) = collectSignals(repo)

            repeat(EVENT_COUNT) { index ->
                repo.saveEvent(task("evt-${index.toString().padStart(2, '0')}")).getOrThrow()
            }
            delay(200)
            job.cancel()

            val pruned = signals.filterIsInstance<EventStoreSignal.EventsPruned>()
            assertTrue(pruned.isNotEmpty(), "rotation should be observable as it happens")
            assertEquals(
                repo.getPruningRecords().getOrThrow().sumOf { it.dropped_count },
                pruned.sumOf { it.droppedCount },
                "the live signal and the durable marker should agree",
            )
        }
    }

    @Test
    fun `a store inside its budget is never pruned`() {
        runBlocking {
            val repo = EventRepository(
                json = json,
                scope = testScope,
                database = database,
                maxStoreBytes = 10L * 1024 * 1024,
                pruneToBytes = 8L * 1024 * 1024,
                sweepIntervalBytes = 1,
            )

            repeat(20) { index -> repo.saveEvent(task("evt-$index")).getOrThrow() }

            assertEquals(20, repo.getAllEvents().getOrThrow().size)
            assertTrue(repo.getPruningRecords().getOrThrow().isEmpty())
        }
    }

    @Test
    fun `the marker table is itself bounded`() {
        // The one table a ticket about unbounded growth must not leave unbounded.
        val queries = database.eventStorePruningQueries
        repeat(5) { index ->
            queries.insertPruning(
                pruned_at = index.toLong(),
                dropped_count = 1,
                dropped_bytes = 1,
                first_sequence = index.toLong(),
                last_sequence = index.toLong(),
                store_bytes_before = 2,
                store_bytes_after = 1,
            )
        }

        queries.trimPruningRecords(keep = 2)

        val kept = queries.getPruningRecords().executeAsList()
        assertEquals(2, kept.size)
        assertEquals(listOf(4L, 3L), kept.map { it.pruned_at }, "the newest passes are the ones kept")
    }

    // ==================== Persistence failure ====================

    @Test
    fun `a refused write is announced on the signal flow`() {
        runBlocking {
            val repo = EventRepository(json, testScope, database)
            val (signals, job) = collectSignals(repo)

            repo.saveEvent(task("evt-dup")).getOrThrow()
            val second = repo.saveEvent(task("evt-dup"))
            delay(200)
            job.cancel()

            assertTrue(second.isFailure, "a duplicate event id violates the primary key")
            val failure = signals.filterIsInstance<EventStoreSignal.PersistenceFailed>().single()
            assertEquals("evt-dup", failure.eventId)
            assertEquals(Event.TaskCreated.EVENT_TYPE, failure.eventType)
        }
    }

    @Test
    fun `a refused write reaches the bus even though it cannot reach the store`() {
        runBlocking {
            val logger: EventLogger = SilentEventLogger()
            val repo = EventRepository(json, testScope, database)
            val bus = EventSerialBus(testScope, logger)

            val failures = mutableListOf<EventStoreEvent.PersistenceFailed>()
            bus.subscribe<EventStoreEvent.PersistenceFailed, EventSubscription.ByEventClassType>(
                agentId = "observer",
                eventType = EventStoreEvent.PersistenceFailed.EVENT_TYPE,
            ) { event, _ -> failures += event }

            var taskDeliveries = 0
            bus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "observer",
                eventType = Event.TaskCreated.EVENT_TYPE,
            ) { _, _ -> taskDeliveries++ }

            val api = AgentEventApiFactory(repo, bus, logger).create("agent-budget")
            api.publish(task("evt-bus-dup"))
            delay(100)
            api.publish(task("evt-bus-dup"))
            delay(200)

            assertEquals(1, taskDeliveries, "the door must not dispatch an event it could not persist")
            val failure = failures.single()
            assertEquals("evt-bus-dup", failure.failedEventId)
            assertEquals(Event.TaskCreated.EVENT_TYPE, failure.failedEventType)

            // Reporting a failed write by writing it would be circular; this one event is never stored.
            assertTrue(
                repo.getEventsByType(EventStoreEvent.PersistenceFailed.EVENT_TYPE).getOrThrow().isEmpty(),
            )
        }
    }

    @Test
    fun `a full disk is classified apart from every other write failure`() {
        // The case the AMPR-291 fate table singled out: not transient, and every later write
        // fails the same way, so an operator has to be told something different.
        assertEquals(
            EventStoreFailure.STORAGE_FULL,
            EventStoreFailure.classify(
                RuntimeException("[SQLITE_FULL] Insertion failed because database is full"),
            ),
        )
        assertEquals(
            EventStoreFailure.STORAGE_FULL,
            EventStoreFailure.classify(
                IllegalStateException("wrapped", RuntimeException("database or disk is full")),
            ),
            "the marker is usually on the driver's exception, under whatever wrapped it",
        )
        assertEquals(
            EventStoreFailure.SERIALIZATION,
            EventStoreFailure.classify(EventSerializationException("Failed to serialize event evt-1")),
        )
        assertEquals(
            EventStoreFailure.OTHER,
            EventStoreFailure.classify(RuntimeException("UNIQUE constraint failed: EventStore.event_id")),
        )
    }

    @Test
    fun `a failure with a cycle in its cause chain still classifies`() {
        // Classification walks the cause chain, and cyclic chains exist in the wild; a
        // classifier that spins on one turns a failed write into a hung one.
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        first.initCause(second)
        second.initCause(first)

        assertEquals(EventStoreFailure.OTHER, EventStoreFailure.classify(first))
    }

    @Test
    fun `the failure event summarises what was lost`() {
        val summary = EventStoreEvent.PersistenceFailed(
            eventId = "evt-signal",
            timestamp = Instant.fromEpochSeconds(1),
            eventSource = eventSource,
            failedEventId = "evt-lost",
            failedEventType = Event.TaskCreated.EVENT_TYPE,
            failure = EventStoreFailure.STORAGE_FULL,
            reason = "disk is full",
        ).getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { it.getIdentifier() },
        )

        assertContains(summary, "evt-lost")
        assertContains(summary, "storage is full")
    }

    private fun tinyStoreRepository(): EventRepository =
        EventRepository(
            json = json,
            scope = testScope,
            database = database,
            maxStoreBytes = MAX_STORE_BYTES,
            pruneToBytes = PRUNE_TO_BYTES,
            // Sweep on every write, so the budget is exercised without writing megabytes.
            sweepIntervalBytes = 1,
        )

    private companion object {
        const val MAX_STORE_BYTES = 2_000L
        const val PRUNE_TO_BYTES = 1_000L
        const val EVENT_COUNT = 30
    }
}
