package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource

class EmissionReplyRegistryTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun baseResolved(emissionId: String) = EmissionEvent.BaseResolved(
        eventId = "evt-1",
        timestamp = now,
        eventSource = EventSource.Human,
        urgency = Urgency.HIGH,
        emissionId = emissionId,
        affordanceId = "aff-yes",
        replyContext = null,
    )

    @Test
    fun `awaitReply suspends until deliver is called`() = runTest {
        val registry = EmissionReplyRegistry()

        val deferred = async {
            registry.awaitReply("em-1", timeout = 5.seconds)
        }

        delay(50.milliseconds)
        assertFalse(deferred.isCompleted)

        registry.deliver(baseResolved("em-1"))

        val reply = deferred.await()
        assertNotNull(reply)
        assertEquals("em-1", reply.emissionId)
    }

    @Test
    fun `awaitReply returns null on timeout`() = runTest {
        val registry = EmissionReplyRegistry()
        val reply = registry.awaitReply("em-timeout", timeout = 100.milliseconds)
        assertNull(reply)
    }

    @Test
    fun `deliver returns false when no pending waiter`() {
        val registry = EmissionReplyRegistry()
        val delivered = registry.deliver(baseResolved("em-unknown"))
        assertFalse(delivered)
    }

    @Test
    fun `deliver returns true when waiter found`() = runTest {
        val registry = EmissionReplyRegistry()
        val deferred = async { registry.awaitReply("em-1", timeout = 5.seconds) }
        delay(50.milliseconds)

        val delivered = registry.deliver(baseResolved("em-1"))
        assertTrue(delivered)
        deferred.await()
    }

    @Test
    fun `multiple concurrent waiters are handled independently`() = runTest {
        val registry = EmissionReplyRegistry()

        val d1 = async { registry.awaitReply("em-1", timeout = 5.seconds) }
        val d2 = async { registry.awaitReply("em-2", timeout = 5.seconds) }

        delay(50.milliseconds)
        registry.deliver(baseResolved("em-2"))
        registry.deliver(baseResolved("em-1"))

        val r1 = d1.await()
        val r2 = d2.await()

        assertEquals("em-1", r1?.emissionId)
        assertEquals("em-2", r2?.emissionId)
    }

    @Test
    fun `getPendingEmissionIds tracks active waiters`() = runTest {
        val registry = EmissionReplyRegistry()
        assertTrue(registry.getPendingEmissionIds().isEmpty())

        val d1 = async { registry.awaitReply("em-1", timeout = 5.seconds) }
        val d2 = async { registry.awaitReply("em-2", timeout = 5.seconds) }
        delay(50.milliseconds)

        val pending = registry.getPendingEmissionIds()
        assertTrue("em-1" in pending)
        assertTrue("em-2" in pending)

        registry.deliver(baseResolved("em-1"))
        d1.await()
        delay(50.milliseconds)

        assertEquals(1, registry.getPendingEmissionIds().size)

        registry.deliver(baseResolved("em-2"))
        d2.await()
    }

    @Test
    fun `HumanInteractionEvent InputProvided is delivered correctly`() = runTest {
        val registry = EmissionReplyRegistry()

        val deferred = async { registry.awaitReply("em-human", timeout = 5.seconds) }
        delay(50.milliseconds)

        val inputProvided = link.socket.ampere.agents.domain.event.HumanInteractionEvent.InputProvided(
            eventId = "evt-2",
            timestamp = now,
            eventSource = EventSource.Human,
            urgency = Urgency.HIGH,
            emissionId = "em-human",
            affordanceId = "free-text-resp",
            replyContext = null,
            requestId = "req-1",
            agentId = "agent-1",
            respondedBy = "miley",
        )

        val delivered = registry.deliver(inputProvided)
        assertTrue(delivered)

        val reply = deferred.await()
        assertNotNull(reply)
        assertEquals("em-human", reply.emissionId)
        val humanReply = reply as link.socket.ampere.agents.domain.event.HumanInteractionEvent.InputProvided
        assertEquals("miley", humanReply.respondedBy)
    }
}
