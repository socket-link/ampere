package link.socket.ampere.agents.events

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.time.MutableClock

/**
 * The envelope contract at the door (F2, F4, F29b): sequence, caused_by, recorded_at, run_id,
 * and a persist failure that reaches the caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEnvelopeTest {

    private val agentId = "agent-A"
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val clockStart = Instant.fromEpochMilliseconds(5_000_000)
    private val clock = MutableClock(clockStart)

    private lateinit var handle: InMemoryEventApi.Handle

    @BeforeTest
    fun setUp() {
        handle = InMemoryEventApi.open(agentId = agentId, clock = clock, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        handle.close()
    }

    private fun taskCreated(
        id: String,
        timestamp: Instant = Instant.fromEpochMilliseconds(1_000),
    ) = Event.TaskCreated(
        eventId = id,
        urgency = Urgency.LOW,
        timestamp = timestamp,
        eventSource = EventSource.Agent(agentId),
        taskId = "task-$id",
        description = "envelope test",
        assignedTo = null,
    )

    private fun providerCallCompleted(
        id: String,
        workflowId: String?,
    ) = ProviderCallCompletedEvent(
        eventId = id,
        timestamp = Instant.fromEpochMilliseconds(1_000),
        eventSource = EventSource.Agent(agentId),
        workflowId = workflowId,
        agentId = agentId,
        cognitivePhase = CognitivePhase.PLAN,
        providerId = "openai",
        modelId = "gpt-4.1",
        usage = TokenUsage(inputTokens = 1, outputTokens = 1),
        latencyMs = 1,
        success = true,
    )

    @Test
    fun `fifty publishes get sequences one through fifty strictly increasing and unique`() {
        runBlocking {
            val stored = (1..50).map { index ->
                handle.api.publish(taskCreated("evt-$index")).getOrThrow()
            }

            val sequences = stored.map { it.sequence }
            assertEquals((1L..50L).toList(), sequences)
            assertTrue(sequences.zipWithNext().all { (previous, next) -> previous < next })
            assertEquals(50, sequences.toSet().size)
        }
    }

    @Test
    fun `events with identical timestamps come back from getEventsSince in publish order`() {
        runBlocking {
            val sameInstant = Instant.fromEpochMilliseconds(42_000)
            handle.api.publish(taskCreated("evt-b", timestamp = sameInstant)).getOrThrow()
            handle.api.publish(taskCreated("evt-a", timestamp = sameInstant)).getOrThrow()
            handle.api.publish(taskCreated("evt-c", timestamp = sameInstant)).getOrThrow()

            val since = handle.repository.getEventsSince(sameInstant).getOrThrow()

            assertEquals(listOf("evt-b", "evt-a", "evt-c"), since.map { it.eventId })
        }
    }

    @Test
    fun `publishing with causedBy is readable through getEventsCausedBy`() {
        runBlocking {
            val parent = taskCreated("evt-parent")
            val child = taskCreated("evt-child")
            val unrelated = taskCreated("evt-unrelated")

            handle.api.publish(parent).getOrThrow()
            val storedChild = handle.api.publish(child, causedBy = parent.eventId).getOrThrow()
            handle.api.publish(unrelated).getOrThrow()

            assertEquals(parent.eventId, storedChild.causedBy)

            val causedBy = handle.repository.getEventsCausedBy(parent.eventId).getOrThrow()
            assertEquals(listOf(child.eventId), causedBy.map { it.event.eventId })
            assertEquals(parent.eventId, causedBy.single().causedBy)
        }
    }

    @Test
    fun `recorded_at is the door clock while the event timestamp is untouched`() {
        runBlocking {
            val eventTimestamp = Instant.fromEpochMilliseconds(1_000)
            val first = handle.api.publish(taskCreated("evt-1", timestamp = eventTimestamp)).getOrThrow()

            assertEquals(clockStart, first.recordedAt)
            assertEquals(eventTimestamp, first.event.timestamp)

            clock.advance(1.hours)
            val second = handle.api.publish(taskCreated("evt-2", timestamp = eventTimestamp)).getOrThrow()

            assertEquals(clockStart + 1.hours, second.recordedAt)
            assertEquals(eventTimestamp, second.event.timestamp)

            val readBack = handle.repository.getEventsSinceSequence(1L).getOrThrow()
            assertEquals(listOf(clockStart, clockStart + 1.hours), readBack.map { it.recordedAt })
            assertEquals(listOf(eventTimestamp, eventTimestamp), readBack.map { it.event.timestamp })
        }
    }

    @Test
    fun `run_id is exactly the runId the publisher passed`() {
        runBlocking {
            // The ProviderCall publisher passes its workflowId as the run id (F4).
            val stored = handle.api
                .publish(providerCallCompleted("evt-explicit", workflowId = "wf-1"), runId = "wf-1")
                .getOrThrow()

            assertEquals("wf-1", stored.runId)
            val row = handle.database.eventStoreQueries.getEventById("evt-explicit").executeAsOne()
            assertEquals("wf-1", row.run_id)
        }
    }

    @Test
    fun `without an explicit runId run_id is NULL even for a kind the old fallback covered`() {
        runBlocking {
            // Before AMPR-340 the repository would have inferred "wf-fallback" from the event's
            // own workflowId. The publisher is now the only source of run_id.
            val stored = handle.api
                .publish(providerCallCompleted("evt-fallback", workflowId = "wf-fallback"))
                .getOrThrow()

            assertNull(stored.runId)
            val row = handle.database.eventStoreQueries.getEventById("evt-fallback").executeAsOne()
            assertNull(row.run_id)

            val plain = handle.api.publish(taskCreated("evt-plain")).getOrThrow()
            assertNull(plain.runId)
        }
    }

    @Test
    fun `a persist failure is returned to the caller and reaches no bus handler`() {
        runBlocking {
            var handlerInvoked = false
            handle.api.onTaskCreated { _, _ -> handlerInvoked = true }

            handle.driver.execute(null, "DROP TABLE EventStore", 0)

            val result = handle.api.publish(taskCreated("evt-doomed"))

            assertTrue(result.isFailure)
            delay(100)
            assertFalse(handlerInvoked)
        }
    }
}
