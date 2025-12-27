package link.socket.ampere.agents.events.relay

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class EventStreamServiceTest {

    private val stubSourceA = EventSource.Agent("agent-A")
    private val stubSourceB = EventSource.Agent("agent-B")

    private val json = DEFAULT_JSON
    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var service: EventRelayServiceImpl

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)

        eventRepository = EventRepository(json, scope, database)
        eventSerialBus = EventSerialBus(scope)
        service = EventRelayServiceImpl(eventSerialBus, eventRepository)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun taskEvent(
        eventId: String = "evt-1",
        source: EventSource = stubSourceA,
        urgency: Urgency = Urgency.HIGH,
        timestamp: Instant = Clock.System.now(),
    ): Event.TaskCreated = Event.TaskCreated(
        eventId = eventId,
        urgency = urgency,
        timestamp = timestamp,
        eventSource = source,
        taskId = "task-123",
        description = "Do something important",
        assignedTo = "agent-B",
    )

    private fun questionEvent(
        eventId: String = "evt-2",
        source: EventSource = stubSourceB,
        urgency: Urgency = Urgency.MEDIUM,
        timestamp: Instant = Clock.System.now(),
    ): Event.QuestionRaised = Event.QuestionRaised(
        eventId = eventId,
        timestamp = timestamp,
        eventSource = source,
        questionText = "Why?",
        context = "Testing context",
        urgency = urgency,
    )

    @Test
    fun `subscribeToLiveEvents emits events published to EventBus`() {
        runBlocking {
            val events = mutableListOf<Event>()

            // Start collecting events
            val job = launch {
                service.subscribeToLiveEvents()
                    .take(2)
                    .collect { events.add(it) }
            }

            // Give subscription time to set up
            delay(100)

            // Publish events
            eventSerialBus.publish(taskEvent(eventId = "evt-1"))
            eventSerialBus.publish(questionEvent(eventId = "evt-2"))

            // Wait for collection to complete
            job.join()

            assertEquals(2, events.size)
            assertEquals("evt-1", events[0].eventId)
            assertEquals("evt-2", events[1].eventId)
        }
    }

    @Test
    fun `subscribeToLiveEvents with event type filter only emits matching events`() {
        runBlocking {
            val events = mutableListOf<Event>()
            val filter = EventRelayFilters(
                eventTypes = setOf(Event.TaskCreated.EVENT_TYPE),
            )

            val job = launch {
                service.subscribeToLiveEvents(filter)
                    .take(1)
                    .collect { events.add(it) }
            }

            delay(100)

            // Publish both types, but only TaskCreated should be collected
            eventSerialBus.publish(questionEvent(eventId = "evt-question"))
            eventSerialBus.publish(taskEvent(eventId = "evt-task"))

            job.join()

            assertEquals(1, events.size)
            assertEquals("evt-task", events[0].eventId)
            assertTrue(events[0] is Event.TaskCreated)
        }
    }

    @Test
    fun `subscribeToLiveEvents with source filter only emits matching events`() {
        runBlocking {
            val events = mutableListOf<Event>()
            val filter = EventRelayFilters(eventSources = setOf(stubSourceA))

            val job = launch {
                service.subscribeToLiveEvents(filter)
                    .take(1)
                    .collect { events.add(it) }
            }

            delay(100)

            eventSerialBus.publish(taskEvent(source = stubSourceB))
            eventSerialBus.publish(taskEvent(source = stubSourceA))

            job.join()

            assertEquals(1, events.size)
            val source = events[0].eventSource as EventSource.Agent
            assertEquals("agent-A", source.agentId)
        }
    }

    @Test
    fun `subscribeToLiveEvents with urgency filter only emits matching events`() {
        runBlocking {
            val events = mutableListOf<Event>()
            val filter = EventRelayFilters(urgencies = setOf(Urgency.HIGH))

            val job = launch {
                service.subscribeToLiveEvents(filter)
                    .take(1)
                    .collect { events.add(it) }
            }

            delay(100)

            eventSerialBus.publish(taskEvent(urgency = Urgency.LOW))
            eventSerialBus.publish(taskEvent(urgency = Urgency.HIGH))

            job.join()

            assertEquals(1, events.size)
            assertEquals(Urgency.HIGH, events[0].urgency)
        }
    }

    @Test
    fun `replayEvents returns events within time range`() {
        runBlocking {
            val now = Clock.System.now()
            val past = now - 1000.milliseconds
            val future = now + 1000.milliseconds

            // Save events at different times
            val event1 = taskEvent(eventId = "evt-1", timestamp = past)
            val event2 = questionEvent(eventId = "evt-2", timestamp = now)
            val event3 = taskEvent(eventId = "evt-3", timestamp = future)

            eventRepository.saveEvent(event1).getOrThrow()
            eventRepository.saveEvent(event2).getOrThrow()
            eventRepository.saveEvent(event3).getOrThrow()

            // Replay events from past to now (should get 2 events)
            val result = service.replayEvents(
                fromTime = past - 100.milliseconds,
                toTime = now + 100.milliseconds,
            ).getOrThrow()

            val events = result.toList()

            assertEquals(2, events.size)
            assertEquals("evt-1", events[0].eventId)
            assertEquals("evt-2", events[1].eventId)
        }
    }

    @Test
    fun `replayEvents returns empty flow when no events in range`() {
        runBlocking {
            val now = Clock.System.now()
            val past = now - 2000.milliseconds
            val wayPast = now - 3000.milliseconds

            // Save event at now
            eventRepository.saveEvent(taskEvent(timestamp = now)).getOrThrow()

            // Replay events from way past to past (should get nothing)
            val result = service.replayEvents(
                fromTime = wayPast,
                toTime = past,
            ).getOrThrow()

            val events = result.toList()

            assertEquals(0, events.size)
        }
    }

    @Test
    fun `replayEvents with filter only returns matching events`() {
        runBlocking {
            val now = Clock.System.now()

            // Save events of different types
            val task = taskEvent(eventId = "evt-task", timestamp = now)
            val question = questionEvent(eventId = "evt-question", timestamp = now)

            eventRepository.saveEvent(task).getOrThrow()
            eventRepository.saveEvent(question).getOrThrow()

            // Replay with filter for TaskCreated only
            val filter = EventRelayFilters(
                eventTypes = setOf(Event.TaskCreated.EVENT_TYPE),
            )
            val result = service.replayEvents(
                fromTime = now - 100.milliseconds,
                toTime = now + 100.milliseconds,
                filters = filter,
            ).getOrThrow()

            val events = result.toList()

            assertEquals(1, events.size)
            assertEquals("evt-task", events[0].eventId)
            assertTrue(events[0] is Event.TaskCreated)
        }
    }

    @Test
    fun `replayEvents returns events in chronological order`() {
        runBlocking {
            val now = Clock.System.now()
            val t1 = now - 500.milliseconds
            val t2 = now
            val t3 = now + 500.milliseconds

            // Save events out of order
            val event2 = taskEvent(eventId = "evt-2", timestamp = t2)
            val event1 = taskEvent(eventId = "evt-1", timestamp = t1)
            val event3 = taskEvent(eventId = "evt-3", timestamp = t3)

            eventRepository.saveEvent(event2).getOrThrow()
            eventRepository.saveEvent(event1).getOrThrow()
            eventRepository.saveEvent(event3).getOrThrow()

            // Replay should return in chronological order
            val result = service.replayEvents(
                fromTime = t1 - 100.milliseconds,
                toTime = t3 + 100.milliseconds,
            ).getOrThrow()

            val events = result.toList()

            assertEquals(3, events.size)
            assertEquals("evt-1", events[0].eventId)
            assertEquals("evt-2", events[1].eventId)
            assertEquals("evt-3", events[2].eventId)
        }
    }

    @Test
    fun `replayEvents with combined filters uses AND logic`() {
        runBlocking {
            val now = Clock.System.now()

            // Save various events
            val event1 = taskEvent(
                eventId = "evt-1",
                source = stubSourceA,
                urgency = Urgency.HIGH,
                timestamp = now,
            )
            val event2 = taskEvent(
                eventId = "evt-2",
                source = stubSourceB,
                urgency = Urgency.HIGH,
                timestamp = now,
            )
            val event3 = taskEvent(
                eventId = "evt-3",
                source = stubSourceA,
                urgency = Urgency.LOW,
                timestamp = now,
            )

            eventRepository.saveEvent(event1).getOrThrow()
            eventRepository.saveEvent(event2).getOrThrow()
            eventRepository.saveEvent(event3).getOrThrow()

            // Filter for agent-A AND HIGH urgency
            val filter = EventRelayFilters(
                eventSources = setOf(stubSourceA),
                urgencies = setOf(Urgency.HIGH),
            )
            val result = service.replayEvents(
                fromTime = now - 100.milliseconds,
                toTime = now + 100.milliseconds,
                filters = filter,
            ).getOrThrow()

            val events = result.toList()

            assertEquals(1, events.size)
            assertEquals("evt-1", events[0].eventId)
        }
    }
}
