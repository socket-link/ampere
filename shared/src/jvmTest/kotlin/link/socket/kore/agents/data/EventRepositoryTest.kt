package link.socket.kore.agents.data

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
import link.socket.kore.agents.events.Database
import link.socket.kore.agents.events.Event
import link.socket.kore.agents.events.EventRepository
import link.socket.kore.agents.events.EventSource
import link.socket.kore.agents.events.Urgency
import link.socket.kore.data.DEFAULT_JSON

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

            val tasks = repo.getEventsByType(Event.TaskCreated.EVENT_CLASS_TYPE).getOrNull()
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
}
