package link.socket.ampere.agents.events.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.emission.Surface
import link.socket.ampere.agents.domain.emission.SurfacePolicy
import link.socket.ampere.agents.domain.emission.SurfaceResolution
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.util.randomUUID

@OptIn(ExperimentalCoroutinesApi::class)
class AgentMessageApiTest {

    private val stubAgentId = "agent-A"
    private val stubAgentId2 = "agent-B"
    private val humanReply = "Ship it on Friday"

    /** Keeps `ConsoleSurfaceIO` out of the test output; the surface choice is not under test. */
    private val quietSurfacePolicy = object : SurfacePolicy {
        override fun resolve(emission: Emission, urgency: Urgency): SurfaceResolution =
            SurfaceResolution(Surface.Foreground)
    }
    private val json = DEFAULT_JSON
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var agentMessageApiFactory: AgentMessageApiFactory

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)

        eventRepository = EventRepository(json, scope, database)
        messageRepository = MessageRepository(json, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
        agentMessageApiFactory = AgentMessageApiFactory(
            messageRepository = messageRepository,
            eventApiFactory = AgentEventApiFactory(eventRepository, eventSerialBus),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `create thread, escalate status, then resolve`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)
            val received = mutableListOf<MessageEvent>()

            val threadCreatedSubscription = api.onThreadCreated { event, _ ->
                received += event
            }

            val escalationRequestedSubscription = api.onEscalationRequested { event, _ ->
                received += event
            }

            val threadStatusChangedSubscription = api.onThreadStatusChanged { event, _ ->
                received += event
            }

            // Subscribe to channel message posts to capture initial and subsequent messages
            val channelMessagePostedSubscription = api.onChannelMessagePosted(
                channel = MessageChannel.Public.Engineering,
            ) { event, _ ->
                received += event
            }

            // Create
            val thread = api.createThread(
                participants = setOf(stubAgentId2),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Kickoff",
            )

            val fetchedThread1 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread1)
            assertEquals(EventStatus.Open, fetchedThread1.status)
            assertEquals(2, fetchedThread1.participants.size) // sender + agent-B
            assertEquals(1, fetchedThread1.messages.size)

            // Post
            val message = api.postMessage(
                threadId = thread.id,
                content = "Update",
            )
            assertEquals("Update", message.content)

            val fetchedThread2 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread2)
            assertEquals(2, fetchedThread2.messages.size)

            // Escalate -> WAITING_FOR_HUMAN
            val escalationJob = launch {
                api.escalateToHuman(
                    threadId = thread.id,
                    reason = "Need approval",
                )
            }

            try {
                delay(200)
                val fetchedThread3 = api.getThread(thread.id).getOrNull()
                assertNotNull(fetchedThread3)
                assertEquals(EventStatus.WaitingForHuman, fetchedThread3.status)

                // Posting now should fail, since the thread is waiting for human
                var threw = false
                try {
                    api.postMessage(
                        threadId = thread.id,
                        content = "Should fail",
                    )
                } catch (e: IllegalArgumentException) {
                    threw = true
                } catch (e: IllegalStateException) {
                    threw = true
                }
                assertTrue(threw)

                // Resolve
                api.resolveThread(thread.id)
                val fetched4 = api.getThread(thread.id).getOrNull()
                assertNotNull(fetched4)
                assertEquals(EventStatus.Resolved, fetched4.status)

                // allow async event handlers to run
                delay(200)

                // Events were published (at least 1 create, 2 posts, 1 escalation, 2 status changes)
                assertTrue(received.any { it is MessageEvent.ThreadCreated })
                assertTrue(received.count { it is MessageEvent.MessagePosted } >= 2)
                assertTrue(received.any { it is MessageEvent.EscalationRequested })
                assertTrue(received.count { it is MessageEvent.ThreadStatusChanged } >= 2)
            } finally {
                escalationJob.cancelAndJoin()
            }

            // ** TODO: Test subscriptions can be unsubscribed from. */
        }
    }

    @Test
    fun `reopen thread allows posting after escalation`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)
            val received = mutableListOf<MessageEvent>()

            api.onThreadStatusChanged { event, _ ->
                received += event
            }

            // Create thread
            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Initial message",
            )

            // Verify initial status is OPEN
            val fetchedThread1 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread1)
            assertEquals(EventStatus.Open, fetchedThread1.status)

            // Escalate to WAITING_FOR_HUMAN
            val escalationJob = launch {
                api.escalateToHuman(
                    threadId = thread.id,
                    reason = "Need human input",
                )
            }

            try {
                delay(200)

                val fetchedThread2 = api.getThread(thread.id).getOrNull()
                assertNotNull(fetchedThread2)
                assertEquals(EventStatus.WaitingForHuman, fetchedThread2.status)

                // Verify posting is blocked
                var blocked = false
                try {
                    api.postMessage(thread.id, "Should fail")
                } catch (e: IllegalArgumentException) {
                    blocked = true
                }
                assertTrue(blocked, "Posting should be blocked when waiting for human")

                // Reopen the thread
                api.reopenThread(thread.id)

                val fetchedThread3 = api.getThread(thread.id).getOrNull()
                assertNotNull(fetchedThread3)
                assertEquals(EventStatus.Open, fetchedThread3.status)

                // Now posting should succeed
                val newMessage = api.postMessage(thread.id, "Human intervention complete")
                assertEquals("Human intervention complete", newMessage.content)

                // Verify thread has the new message
                val fetchedThread4 = api.getThread(thread.id).getOrNull()
                assertNotNull(fetchedThread4)
                assertEquals(2, fetchedThread4.messages.size)

                // Allow async handlers to run
                delay(200)

                // Verify status change events
                val statusChanges = received.filterIsInstance<MessageEvent.ThreadStatusChanged>()
                assertTrue(statusChanges.size >= 2)
                assertTrue(statusChanges.any { it.newStatus == EventStatus.WaitingForHuman })
                assertTrue(statusChanges.any { it.newStatus == EventStatus.Open })
            } finally {
                escalationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `discretionary escalateToHuman still publishes EscalationRequested`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)
            val received = mutableListOf<MessageEvent.EscalationRequested>()

            api.onEscalationRequested { event, _ ->
                received += event
            }

            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Need a decision",
            )

            val escalationJob = launch {
                api.escalateToHuman(
                    threadId = thread.id,
                    reason = "Need human input on release timing",
                    context = mapOf("release" to "v1"),
                )
            }

            try {
                delay(200)

                assertEquals(1, received.size)
                val event = received.single()
                assertEquals(thread.id, event.threadId)
                assertEquals("Need human input on release timing", event.reason)
                assertEquals(mapOf("release" to "v1"), event.context)
            } finally {
                escalationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `thread events are persisted through the door with causedBy links`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)

            val thread = api.createThread(
                participants = setOf(stubAgentId2),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Kickoff",
            )
            api.postMessage(threadId = thread.id, content = "Update")

            // F1: ThreadCreated and both MessagePosted rows are in the EventStore
            val storedThreadCreated = eventRepository
                .getEventsByType(MessageEvent.ThreadCreated.EVENT_TYPE)
                .getOrThrow()
            assertEquals(1, storedThreadCreated.size)
            val threadCreated = assertIs<MessageEvent.ThreadCreated>(storedThreadCreated.single())
            assertEquals(thread.id, threadCreated.thread.id)

            val storedPosted = eventRepository
                .getEventsByType(MessageEvent.MessagePosted.EVENT_TYPE)
                .getOrThrow()
                .map { assertIs<MessageEvent.MessagePosted>(it) }
            assertEquals(listOf("Kickoff", "Update"), storedPosted.map { it.message.content }.sorted())

            // F2: the initial message is caused by the thread creation
            val causedByCreation = eventRepository.getEventsCausedBy(threadCreated.eventId).getOrThrow()
            assertEquals(1, causedByCreation.size)
            val initialPosted = assertIs<MessageEvent.MessagePosted>(causedByCreation.single().event)
            assertEquals("Kickoff", initialPosted.message.content)

            // Escalation (fire-and-forget) persists EscalationRequested, and the status change it causes
            val escalation = api.escalateToHuman(
                threadId = thread.id,
                reason = "Need approval",
                awaitReply = false,
            )
            assertTrue(escalation.isSuccess)

            val escalationRequested = assertIs<MessageEvent.EscalationRequested>(
                eventRepository.getEventsByType(MessageEvent.EscalationRequested.EVENT_TYPE).getOrThrow().single(),
            )
            assertEquals(thread.id, escalationRequested.threadId)
            val causedByEscalation = eventRepository.getEventsCausedBy(escalationRequested.eventId).getOrThrow()
            val statusChanged = assertIs<MessageEvent.ThreadStatusChanged>(causedByEscalation.single().event)
            assertEquals(EventStatus.WaitingForHuman, statusChanged.newStatus)

            // Resolving persists a second status change, caused by whatever the caller names
            val resolved = api.resolveThread(thread.id, causedBy = escalationRequested.eventId)
            assertTrue(resolved.isSuccess)
            val storedStatusChanges = eventRepository
                .getEventsByType(MessageEvent.ThreadStatusChanged.EVENT_TYPE)
                .getOrThrow()
                .map { assertIs<MessageEvent.ThreadStatusChanged>(it) }
            assertEquals(2, storedStatusChanges.size)
            assertTrue(storedStatusChanges.any { it.newStatus == EventStatus.Resolved })
        }
    }

    /**
     * AMPR-351: an escalation raised inside an Arc run produces an Emission attributed to
     * that run, so the escalation and the human reply it collects can be traced back to it.
     */
    @Test
    fun `escalation emission carries the run id the caller named`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)
            val produced = mutableListOf<HumanInteractionEvent.InputRequested>()

            eventSerialBus.subscribe(
                agentId = "run-id-subscriber",
                eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
                handler = EventHandler { event, _ ->
                    produced += event as HumanInteractionEvent.InputRequested
                },
            )

            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Kickoff",
            )

            // escalateToHuman suspends until the human replies; we only need the Emission it
            // produces on the way in, so the call is launched and cancelled rather than awaited.
            val escalationJob = launch {
                api.escalateToHuman(
                    threadId = thread.id,
                    reason = "Need approval",
                    runId = "arc-run-2",
                )
            }

            try {
                delay(200)
                assertEquals("arc-run-2", produced.single().emission.provenance.runId)
            } finally {
                escalationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `escalation emission has no run id when raised outside a run`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)
            val produced = mutableListOf<HumanInteractionEvent.InputRequested>()

            eventSerialBus.subscribe(
                agentId = "no-run-id-subscriber",
                eventType = HumanInteractionEvent.InputRequested.EVENT_TYPE,
                handler = EventHandler { event, _ ->
                    produced += event as HumanInteractionEvent.InputRequested
                },
            )

            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Kickoff",
            )

            val escalationJob = launch {
                api.escalateToHuman(
                    threadId = thread.id,
                    reason = "Need approval",
                )
            }

            try {
                delay(200)
                assertNull(produced.single().emission.provenance.runId)
            } finally {
                escalationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun `reopen thread fails when not in WAITING_FOR_HUMAN state`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)

            // Create thread (starts in OPEN state)
            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Test message",
            )

            // Attempting to reopen an OPEN thread should fail
            var threw = false
            var errorMessage: String? = null
            try {
                api.reopenThread(thread.id)
            } catch (e: Exception) {
                threw = true
                errorMessage = e.message
            }
            assertTrue(threw, "Should throw when reopening a non-WAITING_FOR_HUMAN thread")
            assertTrue(
                errorMessage?.contains("waiting for human") == true,
                "Error message should mention waiting for human, was: $errorMessage",
            )
        }
    }

    @Test
    fun `escalation reply is stored as a Human message and not as the escalating agent`() {
        runBlocking {
            // Own registry rather than the process-wide default, so only this escalation is pending.
            val replyRegistry = EmissionReplyRegistry()
            val api = AgentMessageApi(
                agentId = stubAgentId,
                messageRepository = messageRepository,
                eventApi = AgentEventApiFactory(eventRepository, eventSerialBus).create(stubAgentId),
                emissionReplyRegistry = replyRegistry,
                surfacePolicy = quietSurfacePolicy,
            )

            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Ready to ship?",
            )

            val escalation = async {
                api.escalateToHuman(
                    threadId = thread.id,
                    reason = "Need approval before release",
                )
            }

            // Wait for askHuman to register its waiter, then answer as the human.
            val emissionId = withTimeout(5.seconds) {
                var pending = replyRegistry.getPendingEmissionIds()
                while (pending.isEmpty()) {
                    delay(20)
                    pending = replyRegistry.getPendingEmissionIds()
                }
                pending.single()
            }

            val delivered = replyRegistry.deliver(
                EmissionEvent.BaseResolved(
                    eventId = randomUUID(),
                    timestamp = Clock.System.now(),
                    eventSource = EventSource.Human,
                    urgency = Urgency.HIGH,
                    emissionId = emissionId,
                    affordanceId = "free-text",
                    replyContext = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("free-text"),
                            "text" to JsonPrimitive(humanReply),
                        ),
                    ),
                ),
            )
            assertTrue(delivered, "Reply should have found the suspended escalation")
            assertTrue(escalation.await().isSuccess)

            val fetched = api.getThread(thread.id).getOrNull()
            assertNotNull(fetched)
            assertEquals(EventStatus.Open, fetched.status)

            // AMPR-344: the re-posted reply carries human attribution ...
            val replyMessage = fetched.messages.single { it.content == humanReply }
            assertEquals(MessageSender.Human, replyMessage.sender)

            // ... while the escalating agent's own message is unchanged.
            val initialMessage = fetched.messages.single { it.content == "Ready to ship?" }
            assertEquals(MessageSender.Agent(stubAgentId), initialMessage.sender)

            val posted = eventRepository
                .getEventsByType(MessageEvent.MessagePosted.EVENT_TYPE)
                .getOrThrow()
                .map { assertIs<MessageEvent.MessagePosted>(it) }
            val postedReply = posted.single { it.message.content == humanReply }
            assertEquals(MessageSender.Human, postedReply.message.sender)
            assertEquals(EventSource.Human, postedReply.eventSource)
        }
    }
}
