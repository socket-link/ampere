package link.socket.ampere.eval.trace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.AssetAccessEvent
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

    // --- AMPR-267 trace-hygiene tests ---

    @Test
    fun `oversized event is truncated and flagged but still decodes`() = runTest {
        val recorder = TraceRecorder(bus, service, maxEventBytes = 200, maxStringFieldChars = 50)
        val handle = recorder.start(runId = "run-3", arcId = "arc-3")

        bus.publish(
            Event.QuestionRaised(
                eventId = "e1",
                urgency = Urgency.LOW,
                timestamp = Instant.fromEpochMilliseconds(1),
                eventSource = source,
                questionText = "why?",
                context = "x".repeat(1_000),
            ),
        )

        val trace = handle.stop().getOrThrow()

        assertEquals(1, trace.size)
        assertEquals(0, trace.droppedEventCount)
        val traceEvent = trace.events.single()
        assertTrue(traceEvent.truncated)

        // Truncation only shrinks string leaves; the shape is untouched, so this
        // still decodes via Event.serializer() (AMPR-267's replay constraint).
        val decoded = DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), traceEvent.payload) as Event.QuestionRaised
        assertEquals("e1", decoded.eventId)
        assertTrue(decoded.context.endsWith(TRUNCATION_MARKER))
        assertTrue(decoded.context.length <= 50 + TRUNCATION_MARKER.length)
    }

    @Test
    fun `trace exceeding the byte budget drops trailing events and records the count`() = runTest {
        val recorder = TraceRecorder(bus, service, maxTraceBytes = 300)
        val handle = recorder.start(runId = "run-4", arcId = "arc-4")
        val emitted = events(5)
        emitted.forEach { bus.publish(it) }

        val trace = handle.stop().getOrThrow()

        assertTrue(trace.size < 5, "expected some events dropped, kept ${trace.size}")
        assertEquals(5, trace.size + trace.droppedEventCount)
        assertEquals(
            emitted.take(trace.size).map { it.eventId },
            trace.events.map { it.payload.eventId() },
        )
    }

    @Test
    fun `asset access event carries a byte count, never the asset bytes, in a recorded trace`() = runTest {
        val handle = recorder.start(runId = "run-5", arcId = "arc-5")

        bus.publish(
            AssetAccessEvent(
                eventId = "e1",
                timestamp = Instant.fromEpochMilliseconds(1),
                eventSource = source,
                linkId = null,
                plugId = "plug-1",
                byteCount = 4_096,
            ),
        )

        val trace = handle.stop().getOrThrow()
        val payload: JsonObject = trace.events.single().payload.jsonObject

        // The metadata is present...
        assertEquals(4_096, payload.getValue("byteCount").jsonPrimitive.content.toInt())
        // ...but there is no field anywhere carrying the resolved bytes themselves —
        // AssetAccessEvent has no such field by construction, so nothing here can
        // leak them into a trace no matter what is truncated or dropped upstream.
        assertEquals(
            setOf("byteCount"),
            payload.keys.filter { it.contains("byte", ignoreCase = true) }.toSet(),
        )
    }

    private fun kotlinx.serialization.json.JsonElement.eventId(): String =
        DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), this).eventId
}
