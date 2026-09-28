package link.socket.ampere.pause

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * [InMemoryPauseStore] against the same contract [PauseStore] states, so a consumer's tests mean
 * the same thing here as against SQL (AMPR-370).
 *
 * Durability is the one property this cannot show — that lives in `PauseStoreTest`, which restarts
 * a real database file. What it pins is everything a caller can reason about without a driver:
 * settle-once, expire-on-read, and the refusals.
 */
class InMemoryPauseStoreTest {

    private val raisedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val timeout = 60.seconds
    private val beforeDeadline = raisedAt + 30.seconds
    private val afterDeadline = raisedAt + 61.seconds

    private val pause = AgentPause(
        correlationId = "file-the-issue",
        reason = "Filing an issue is an unattended write",
        urgency = PauseUrgency.Important,
        suggestedChannels = listOf(
            EscalationChannel.Push(
                notificationCategory = "write-gate",
                title = "Approve filing an issue?",
                body = "The run stopped rather than writing unattended.",
            ),
        ),
        timeoutMillis = timeout.inWholeMilliseconds,
    )

    @Test
    fun `a raised pause is open and addressed by its correlation id`() = runTest {
        val store = InMemoryPauseStore()
        val raised = store.raise(pause, raisedAt).getOrThrow()

        assertEquals(raisedAt + timeout, raised.expiresAt)
        assertTrue(raised.isOpen)
        assertEquals(pause, store.get(pause.correlationId, beforeDeadline).getOrThrow()?.pause)
    }

    @Test
    fun `an unanswered pause settles to TimedOut on the first read past its deadline`() = runTest {
        val store = InMemoryPauseStore()
        store.raise(pause, raisedAt).getOrThrow()

        assertTrue(store.get(pause.correlationId, afterDeadline).getOrThrow()!!.isTimedOut)
        // Settled once and for all: an earlier clock does not reopen it.
        val earlier = store.get(pause.correlationId, beforeDeadline).getOrThrow()!!
        assertTrue(earlier.isTimedOut)
        assertEquals(afterDeadline, earlier.settledAt)
    }

    @Test
    fun `the sweep settles what is due and leaves what is not`() = runTest {
        val store = InMemoryPauseStore()
        val soon = pause.copy(correlationId = "soon", timeoutMillis = 10.seconds.inWholeMilliseconds)
        store.raise(soon, raisedAt).getOrThrow()
        store.raise(pause, raisedAt).getOrThrow()

        assertEquals(listOf("soon"), store.expire(raisedAt + 20.seconds).getOrThrow())
        assertTrue(store.get(pause.correlationId, raisedAt + 20.seconds).getOrThrow()!!.isOpen)
    }

    @Test
    fun `the first response wins and a later one reports that it did not`() = runTest {
        val store = InMemoryPauseStore()
        store.raise(pause, raisedAt).getOrThrow()

        val first = store.resolve(
            AgentPauseResponse.Approved(pause.correlationId, payload = "go"),
            beforeDeadline,
        ).getOrThrow()
        assertTrue(first.settledByThisCall)

        val second = store.resolve(
            AgentPauseResponse.Rejected(pause.correlationId),
            beforeDeadline + 1.seconds,
        ).getOrThrow()

        assertFalse(second.settledByThisCall)
        assertEquals("go", assertIs<AgentPauseResponse.Approved>(second.response).payload)
    }

    @Test
    fun `resolving an unknown correlation id fails and answering nothing returns null`() = runTest {
        val store = InMemoryPauseStore()

        val failure = store.resolve(AgentPauseResponse.Approved("never-raised"), beforeDeadline)
        assertIs<UnknownPauseException>(failure.exceptionOrNull())
        assertNull(store.get("never-raised", beforeDeadline).getOrThrow())
    }

    @Test
    fun `re-raising an identical open pause is the same raise and anything else is refused`() = runTest {
        val store = InMemoryPauseStore()
        val first = store.raise(pause, raisedAt).getOrThrow()

        assertEquals(first, store.raise(pause, raisedAt + 5.seconds).getOrThrow())

        val reused = store.raise(pause.copy(reason = "different"), raisedAt).exceptionOrNull()
        assertFalse(assertIs<PauseCorrelationIdInUseException>(reused).settled)

        store.resolve(AgentPauseResponse.Approved(pause.correlationId), beforeDeadline).getOrThrow()
        val overSettled = store.raise(pause, afterDeadline).exceptionOrNull()
        assertTrue(assertIs<PauseCorrelationIdInUseException>(overSettled).settled)
    }

    @Test
    fun `listOpen orders by deadline and excludes what has settled`() = runTest {
        val store = InMemoryPauseStore()
        val soon = pause.copy(correlationId = "soon", timeoutMillis = 10.seconds.inWholeMilliseconds)
        val later = pause.copy(correlationId = "later", timeoutMillis = 40.seconds.inWholeMilliseconds)
        val answered = pause.copy(correlationId = "answered")

        store.raise(later, raisedAt).getOrThrow()
        store.raise(soon, raisedAt).getOrThrow()
        store.raise(answered, raisedAt).getOrThrow()
        store.resolve(AgentPauseResponse.Approved("answered"), raisedAt + 1.seconds).getOrThrow()

        assertEquals(
            listOf("soon", "later"),
            store.listOpen(raisedAt + 5.seconds).getOrThrow().map { it.correlationId },
        )
    }

    @Test
    fun `delete forgets the row and frees the correlation id`() = runTest {
        val store = InMemoryPauseStore()
        store.raise(pause, raisedAt).getOrThrow()
        store.delete(pause.correlationId).getOrThrow()

        assertNull(store.get(pause.correlationId, beforeDeadline).getOrThrow())
        store.raise(pause, raisedAt).getOrThrow()
    }

    @Test
    fun `a store seeded with records reads them back`() = runTest {
        val settled = PauseRecord(
            pause = pause,
            raisedAt = raisedAt,
            expiresAt = raisedAt + timeout,
            response = AgentPauseResponse.Rejected(pause.correlationId, reason = "wrong repo"),
            settledAt = beforeDeadline,
        )
        val store = InMemoryPauseStore(listOf(settled))

        assertEquals(settled, store.get(pause.correlationId, afterDeadline).getOrThrow())
        assertEquals(emptyList<PauseRecord>(), store.listOpen(afterDeadline).getOrThrow())
    }
}
