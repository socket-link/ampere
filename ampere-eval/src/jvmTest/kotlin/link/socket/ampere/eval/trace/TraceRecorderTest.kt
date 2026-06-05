package link.socket.ampere.eval.trace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.db.EvalDatabase

/** AMPR-183 task 1.4 validation + record -> persist -> load -> replay round-trip. */
@OptIn(ExperimentalCoroutinesApi::class)
class TraceRecorderTest {

    // UnconfinedTestDispatcher makes the bus dispatch handlers inline at publish
    // time, so captured order is deterministic (same pattern as ArcTraceProjectionTest).
    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var bus: EventSerialBus
    private lateinit var service: TraceService
    private lateinit var recorder: TraceRecorder

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        EvalDatabase.Schema.create(driver)
        bus = EventSerialBus(scope)
        service = TraceService(EvalDatabase(driver))
        recorder = TraceRecorder(bus, service)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private val source = EventSource.Agent("agent-1")

    private fun events(n: Int): List<Event> = (1..n).map { i ->
        Event.TaskCreated(
            eventId = "e$i",
            urgency = Urgency.LOW,
            timestamp = Instant.fromEpochMilliseconds(i.toLong()),
            eventSource = source,
            taskId = "T-$i",
            description = "task $i",
            assignedTo = null,
        )
    }

    @Test
    fun `records exactly N events in emission order`() = runTest {
        val handle = recorder.start(runId = "run-1", arcId = "arc-1")
        val emitted = events(5)
        emitted.forEach { bus.publish(it) }

        val trace = handle.stop().getOrThrow()

        assertEquals(5, trace.size)
        assertEquals(emitted.map { it.eventId }, trace.events.map { it.payload.eventId() })
        assertEquals(listOf(0, 1, 2, 3, 4), trace.events.map { it.index })
    }

    @Test
    fun `round-trip record persist load replay yields identical ordered sequence`() = runTest {
        val handle = recorder.start(runId = "run-2", arcId = "arc-2")
        val emitted = events(4)
        emitted.forEach { bus.publish(it) }

        val recorded = handle.stop().getOrThrow()

        // Persisted by stop(); load it back.
        val loaded = service.load(recorded.id).getOrThrow()
        assertEquals(recorded, loaded)

        // Replay to the end yields the identical ordered sequence.
        val replayed = TraceCursor(loaded).replayTo(loaded.size - 1)
        assertEquals(recorded.events, replayed)
        assertEquals(emitted.map { it.eventId }, replayed.map { it.payload.eventId() })
    }

    private fun kotlinx.serialization.json.JsonElement.eventId(): String =
        DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), this).eventId
}
