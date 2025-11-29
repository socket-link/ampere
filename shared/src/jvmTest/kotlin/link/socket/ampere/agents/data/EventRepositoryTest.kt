package link.socket.ampere.agents.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.Urgency
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class EventRepositoryTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val stubJson = DEFAULT_JSON
    private val stubEventSourceA = EventSource.Agent("agent-A")
    private val stubEventSourceB = EventSource.Agent("agent-B")

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: EventRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)
        repo = EventRepository(stubJson, testScope, database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun sampleTask(
        id: String = "evt-task-1",
        ts: Instant = Clock.System.now(),
    ) = Event.TaskCreated(
        eventId = id,
        urgency = Urgency.LOW,
        timestamp = ts,
        eventSource = stubEventSourceA,
        taskId = "task-123",
        description = "Test Task",
        assignedTo = stubEventSourceB.agentId,
    )

    private fun sampleQuestion(
        id: String = "evt-q-1",
        ts: Instant = Clock.System.now(),
    ) = Event.QuestionRaised(
        eventId = id,
        timestamp = ts,
        eventSource = stubEventSourceA,
        questionText = "Why?",
        context = "UnitTest",
        urgency = Urgency.MEDIUM,
    )

    @Test
    fun `save and retrieve by id`() {
        runBlocking {
            val event = sampleTask(id = "evt-100", ts = Instant.fromEpochSeconds(1000))
            repo.saveEvent(event)

            val loaded = repo.getEventById("evt-100").getOrNull()
            assertNotNull(loaded)
            assertIs<Event.TaskCreated>(loaded)
            assertEquals("task-123", loaded.taskId)
        }
    }

    @Test
    fun `query by type returns only matching`() {
        runBlocking {
            val t1 = sampleTask(id = "evt-1", ts = Instant.fromEpochSeconds(1_000))
            val q1 = sampleQuestion(id = "evt-2", ts = Instant.fromEpochSeconds(2_000))
            repo.saveEvent(t1)
            repo.saveEvent(q1)

            val tasks = repo.getEventsByType(Event.TaskCreated.EVENT_TYPE).getOrNull()
            assertNotNull(tasks)
            assertEquals(1, tasks.size)
            assertIs<Event.TaskCreated>(tasks.first())
        }
    }

    @Test
    fun querySinceReturnsAscending() {
        runBlocking {
            val t1 = sampleTask(id = "evt-1", ts = Instant.fromEpochSeconds(1_000))
            val t2 = sampleTask(id = "evt-2", ts = Instant.fromEpochSeconds(3_000))
            val q1 = sampleQuestion(id = "evt-3", ts = Instant.fromEpochSeconds(2_000))
            repo.saveEvent(t1)
            repo.saveEvent(t2)
            repo.saveEvent(q1)

            val since = repo.getEventsSince(Instant.fromEpochSeconds(1_500)).getOrNull()
            assertNotNull(since)
            assertEquals(listOf("evt-3", "evt-2"), since.map { it.eventId })
        }
    }

    @Test
    fun `events persist across restart`() {
        runBlocking {
            val dir = createTempDirectory(prefix = "events-db")
            val dbFile = dir / "events.sqlite"

            // First open
            val driver1 = JdbcSqliteDriver("jdbc:sqlite:$dbFile")
            Database.Schema.create(driver1)

            val repo1 = EventRepository(stubJson, testScope, Database.Companion(driver1))
            val e = sampleTask(id = "evt-persist", ts = Instant.fromEpochSeconds(42_000))
            repo1.saveEvent(e)
            driver1.close()

            // Re-open same file
            val driver2 = JdbcSqliteDriver("jdbc:sqlite:$dbFile")
            val repo2 = EventRepository(stubJson, testScope, Database.Companion(driver2))
            val loaded = repo2.getEventById("evt-persist").getOrNull()

            assertNotNull(loaded)
            assertIs<Event.TaskCreated>(loaded)
            driver2.close()
        }
    }

    @Test
    fun `getEventsWithFilters returns events within time range`() {
        runBlocking {
            val t1 = Instant.fromEpochSeconds(1_000)
            val t2 = Instant.fromEpochSeconds(2_000)
            val t3 = Instant.fromEpochSeconds(3_000)
            val t4 = Instant.fromEpochSeconds(4_000)

            // Save events at different times
            repo.saveEvent(sampleTask(id = "evt-1", ts = t1))
            repo.saveEvent(sampleQuestion(id = "evt-2", ts = t2))
            repo.saveEvent(sampleTask(id = "evt-3", ts = t3))
            repo.saveEvent(sampleQuestion(id = "evt-4", ts = t4))

            // Query for events between t2 and t3 (inclusive)
            val result = repo.getEventsWithFilters(
                fromTime = t2,
                toTime = t3
            ).getOrNull()

            assertNotNull(result)
            assertEquals(2, result.size)
            assertEquals("evt-2", result[0].eventId)
            assertEquals("evt-3", result[1].eventId)
        }
    }

    @Test
    fun `getEventsWithFilters with event type filter returns only matching events`() {
        runBlocking {
            val now = Instant.fromEpochSeconds(5_000)

            // Save events of different types at the same time
            repo.saveEvent(sampleTask(id = "evt-task-1", ts = now))
            repo.saveEvent(sampleQuestion(id = "evt-question-1", ts = now))
            repo.saveEvent(sampleTask(id = "evt-task-2", ts = now))

            // Query with event type filter for TaskCreated only
            val result = repo.getEventsWithFilters(
                fromTime = now.minus(kotlin.time.Duration.parse("1s")),
                toTime = now.plus(kotlin.time.Duration.parse("1s")),
                eventTypes = setOf("TaskCreated")
            ).getOrNull()

            assertNotNull(result)
            assertEquals(2, result.size)
            assertEquals("evt-task-1", result[0].eventId)
            assertEquals("evt-task-2", result[1].eventId)
            result.forEach { event ->
                assertIs<Event.TaskCreated>(event)
            }
        }
    }

    @Test
    fun `getEventsWithFilters with source ID filter returns only matching events`() {
        runBlocking {
            val now = Instant.fromEpochSeconds(6_000)
            val sourceA = EventSource.Agent("agent-A")
            val sourceB = EventSource.Agent("agent-B")

            // Save events from different sources
            repo.saveEvent(sampleTask(id = "evt-1", ts = now).copy(eventSource = sourceA))
            repo.saveEvent(sampleTask(id = "evt-2", ts = now).copy(eventSource = sourceB))
            repo.saveEvent(sampleQuestion(id = "evt-3", ts = now).copy(eventSource = sourceA))

            // Query with source ID filter for agent-A only
            val result = repo.getEventsWithFilters(
                fromTime = now.minus(kotlin.time.Duration.parse("1s")),
                toTime = now.plus(kotlin.time.Duration.parse("1s")),
                sourceIds = setOf("agent-A")
            ).getOrNull()

            assertNotNull(result)
            assertEquals(2, result.size)
            assertEquals("evt-1", result[0].eventId)
            assertEquals("evt-3", result[1].eventId)
            result.forEach { event ->
                assertEquals("agent-A", (event.eventSource as EventSource.Agent).agentId)
            }
        }
    }

    @Test
    fun `getEventsWithFilters with both filters applies AND logic`() {
        runBlocking {
            val now = Instant.fromEpochSeconds(7_000)
            val sourceA = EventSource.Agent("agent-A")
            val sourceB = EventSource.Agent("agent-B")

            // Save various combinations
            repo.saveEvent(sampleTask(id = "evt-1", ts = now).copy(eventSource = sourceA)) // Match
            repo.saveEvent(sampleTask(id = "evt-2", ts = now).copy(eventSource = sourceB)) // No match (wrong source)
            repo.saveEvent(sampleQuestion(id = "evt-3", ts = now).copy(eventSource = sourceA)) // No match (wrong type)
            repo.saveEvent(sampleQuestion(id = "evt-4", ts = now).copy(eventSource = sourceB)) // No match (both wrong)

            // Query with both filters: TaskCreated AND agent-A
            val result = repo.getEventsWithFilters(
                fromTime = now.minus(kotlin.time.Duration.parse("1s")),
                toTime = now.plus(kotlin.time.Duration.parse("1s")),
                eventTypes = setOf("TaskCreated"),
                sourceIds = setOf("agent-A")
            ).getOrNull()

            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("evt-1", result[0].eventId)
            assertIs<Event.TaskCreated>(result[0])
            assertEquals("agent-A", (result[0].eventSource as EventSource.Agent).agentId)
        }
    }

    @Test
    fun `getEventsWithFilters returns empty list when no events in range`() {
        runBlocking {
            val now = Instant.fromEpochSeconds(10_000)
            val past = Instant.fromEpochSeconds(5_000)
            val wayPast = Instant.fromEpochSeconds(1_000)

            // Save event at 'now'
            repo.saveEvent(sampleTask(id = "evt-1", ts = now))

            // Query for time range before the event
            val result = repo.getEventsWithFilters(
                fromTime = wayPast,
                toTime = past
            ).getOrNull()

            assertNotNull(result)
            assertEquals(0, result.size)
        }
    }

    @Test
    fun `getEventsWithFilters with equal fromTime and toTime returns events at that exact time`() {
        runBlocking {
            val t1 = Instant.fromEpochSeconds(8_000)
            val t2 = Instant.fromEpochSeconds(8_001)
            val t3 = Instant.fromEpochSeconds(8_002)

            // Save events at different millisecond timestamps
            repo.saveEvent(sampleTask(id = "evt-1", ts = t1))
            repo.saveEvent(sampleTask(id = "evt-2", ts = t2))
            repo.saveEvent(sampleTask(id = "evt-3", ts = t3))

            // Query with fromTime == toTime
            val result = repo.getEventsWithFilters(
                fromTime = t2,
                toTime = t2
            ).getOrNull()

            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("evt-2", result[0].eventId)
        }
    }

    @Test
    fun `getEventsWithFilters returns events in chronological order`() {
        runBlocking {
            val t1 = Instant.fromEpochSeconds(100)
            val t2 = Instant.fromEpochSeconds(200)
            val t3 = Instant.fromEpochSeconds(300)

            // Save events out of chronological order
            repo.saveEvent(sampleTask(id = "evt-middle", ts = t2))
            repo.saveEvent(sampleTask(id = "evt-last", ts = t3))
            repo.saveEvent(sampleTask(id = "evt-first", ts = t1))

            // Query should return in chronological order (ascending)
            val result = repo.getEventsWithFilters(
                fromTime = t1,
                toTime = t3
            ).getOrNull()

            assertNotNull(result)
            assertEquals(3, result.size)
            assertEquals("evt-first", result[0].eventId)
            assertEquals("evt-middle", result[1].eventId)
            assertEquals("evt-last", result[2].eventId)
        }
    }
}
