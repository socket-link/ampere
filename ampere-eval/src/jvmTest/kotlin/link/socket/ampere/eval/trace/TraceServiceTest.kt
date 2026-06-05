package link.socket.ampere.eval.trace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.encodeToJsonElement
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.db.EvalDatabase

/** AMPR-183 task 1.3 validation. */
class TraceServiceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var service: TraceService

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        EvalDatabase.Schema.create(driver)
        service = TraceService(EvalDatabase(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun sampleTrace(id: String, arcId: String): Trace {
        val source = EventSource.Agent("agent-1")
        val events = listOf<Event>(
            Event.TaskCreated("e1", Urgency.LOW, Instant.fromEpochMilliseconds(10), source, "T-1", "do it", null),
            Event.QuestionRaised("e2", Urgency.HIGH, Instant.fromEpochMilliseconds(20), source, "why?", "ctx"),
        ).mapIndexed { index, event ->
            TraceEvent(
                index = index,
                timestamp = event.timestamp.toEpochMilliseconds(),
                type = event.eventType,
                payload = DEFAULT_JSON.encodeToJsonElement(Event.serializer(), event),
            )
        }
        return Trace(id = id, runId = "run-$id", arcId = arcId, createdAt = 100L, events = events)
    }

    @Test
    fun `save then load returns byte-identical events`() = runTest {
        val trace = sampleTrace("trace-1", "arc-1")

        service.save(trace).getOrThrow()
        val loaded = service.load("trace-1").getOrThrow()

        assertEquals(trace, loaded)
        assertEquals(trace.events, loaded.events)
        // Payloads decode back to the original events.
        val decoded = loaded.events.map { DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), it.payload) }
        assertEquals("e1", decoded[0].eventId)
        assertEquals("e2", decoded[1].eventId)
    }

    @Test
    fun `load fails when the trace is absent`() = runTest {
        val result = service.load("does-not-exist")
        assertTrue(result.isFailure)
    }

    @Test
    fun `list returns summaries and filters by arcId`() = runTest {
        service.save(sampleTrace("trace-1", "arc-a")).getOrThrow()
        service.save(sampleTrace("trace-2", "arc-a")).getOrThrow()
        service.save(sampleTrace("trace-3", "arc-b")).getOrThrow()

        val all = service.list().getOrThrow()
        assertEquals(3, all.size)
        assertEquals(2, all.first { it.id == "trace-1" }.eventCount)

        val arcA = service.list("arc-a").getOrThrow()
        assertEquals(2, arcA.size)
        assertTrue(arcA.all { it.arcId == "arc-a" })

        val arcB = service.list("arc-b").getOrThrow()
        assertEquals(1, arcB.size)
        assertEquals("trace-3", arcB.single().id)
    }
}
