package link.socket.ampere.agents.events.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.EmissionProvenance
import link.socket.ampere.agents.domain.emission.ProseFormat
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.events.bus.EventSerialBus

/**
 * The bus→Flow adapter the Arc bridge streams progress through (AMPR-243).
 *
 * What is under test is the three properties that make it safe for a second consumer:
 * per-run filtering, per-collector teardown, and an overflow policy that reports its losses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmissionStreamTest {

    private fun emission(runId: String?, text: String): Emission = Emission(
        id = "emission-$text",
        kind = EmissionKind.Prose,
        payload = EmissionPayload.Prose(text = text, format = ProseFormat.PLAIN),
        provenance = EmissionProvenance(runId = runId, inputDigest = "digest-$text"),
        producedAt = Clock.System.now(),
    )

    private fun produced(runId: String?, text: String): EmissionEvent.BaseProduced =
        EmissionEvent.BaseProduced(
            eventId = "evt-$text",
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("agent-A"),
            urgency = Urgency.MEDIUM,
            emission = emission(runId, text),
        )

    private fun proseOf(emission: Emission): String =
        (emission.payload as EmissionPayload.Prose).text

    @Test
    fun `emissions carry only the requested run`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val mine = mutableListOf<String>()

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.emissions(runId = "run-mine").collect { mine += proseOf(it) }
        }

        bus.publish(produced("run-mine", "first"))
        bus.publish(produced("run-theirs", "second"))
        bus.publish(produced(null, "third"))
        bus.publish(produced("run-mine", "fourth"))
        runCurrent()

        assertEquals(listOf("first", "fourth"), mine)
        collector.cancel()
    }

    @Test
    fun `a null run id streams every emission on the bus`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val all = mutableListOf<String>()

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.emissions(runId = null).collect { all += proseOf(it) }
        }

        bus.publish(produced("run-a", "first"))
        bus.publish(produced("run-b", "second"))
        runCurrent()

        assertEquals(listOf("first", "second"), all)
        collector.cancel()
    }

    @Test
    fun `cancelling one collector leaves the other collecting`() = runTest {
        // The regression that made the pre-existing relay unsafe to share: its teardown
        // unsubscribed by event type and took every other subscriber down with it.
        val bus = EventSerialBus(scope = backgroundScope)
        val leaving = mutableListOf<String>()
        val staying = mutableListOf<String>()

        val leavingCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.emissions(runId = "run-1").collect { leaving += proseOf(it) }
        }
        val stayingCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.emissions(runId = "run-1").collect { staying += proseOf(it) }
        }

        bus.publish(produced("run-1", "before"))
        runCurrent()

        leavingCollector.cancel()
        runCurrent()

        bus.publish(produced("run-1", "after"))
        runCurrent()

        assertEquals(listOf("before"), leaving)
        assertEquals(listOf("before", "after"), staying)
        stayingCollector.cancel()
    }

    @Test
    fun `a stalled collector drops the oldest emissions and is told how many`() = runTest {
        val bus = EventSerialBus(scope = backgroundScope)
        val collected = mutableListOf<String>()
        val droppedTotals = mutableListOf<Long>()

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.emissions(
                runId = "run-1",
                capacity = 2,
                onDropped = { droppedTotals += it },
            ).collect {
                collected += proseOf(it)
                // Stall past every publish below; virtual time only moves when we let it.
                delay(10_000)
            }
        }

        listOf("one", "two", "three", "four", "five", "six").forEach { text ->
            bus.publish(produced("run-1", text))
        }
        // runCurrent, not advanceUntilIdle: the collector must stay parked in its delay while
        // the bus dispatches, which is the whole point of the scenario.
        runCurrent()

        assertEquals(
            listOf(1L, 2L, 3L),
            droppedTotals,
            "Six emissions into a two-slot buffer behind a stalled collector loses three",
        )

        // Let the collector drain and confirm DROP_OLDEST kept the newest state.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("one", "five", "six"), collected)

        collector.cancel()
    }

    @Test
    fun `human input requests arrive on the emission stream`() = runTest {
        // InputRequested declares EmissionEvent.Produced as a parent type, so the bus dispatches
        // it to Produced subscribers. A progress surface should see it without special-casing.
        val bus = EventSerialBus(scope = backgroundScope)
        val seen = mutableListOf<String>()

        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.emissions(runId = "run-1").collect { seen += proseOf(it) }
        }

        bus.publish(
            HumanInteractionEvent.InputRequested(
                eventId = "evt-ask",
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent("agent-A"),
                urgency = Urgency.HIGH,
                emission = emission("run-1", "needs-a-human"),
                requestId = "req-1",
                agentId = "agent-A",
            ),
        )
        runCurrent()

        assertTrue("needs-a-human" in seen, "Saw $seen")
        collector.cancel()
    }
}
