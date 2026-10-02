package link.socket.ampere.eval.suite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.RoutingResolution
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.eval.trace.Trace

/**
 * The Rung 0 routing gate (AMPR-225): every scenario in [Rung0RoutingSuite], run through the real
 * relay and graded against its committed golden trace.
 *
 * Part of `:ampere-eval:evalReplay`, so CI fails the build on it. It needs no API key, no network,
 * and no writable directory: routing is a local decision, and the bench records it in memory.
 *
 * When it goes red, the failure message names the scenario, its meter, and for a conformance
 * failure the first event where the relay stopped matching its recording. If the change was
 * intended — a catalog price moved, a model was re-rated, the relay started announcing something
 * new — re-record with `./gradlew :ampere-eval:recordGoldenTraces` and commit the diff.
 */
class Rung0RoutingSuiteTest {

    @Test
    fun `every routing scenario is green against its golden trace`() = runBlocking {
        Rung0RoutingBench.open().use { bench ->
            val results = bench.run(Rung0RoutingSuite.cases(Rung0RoutingSuite.loadGoldenTraces()))

            assertEquals(Rung0RoutingSuite.scenarios.size, results.size)
            assertTrue(results.all { it.passed }, results.explain())
        }
    }

    /**
     * The ticket's routing assertions, read straight off the resolution and the recording rather
     * than through a meter: Rung 0 is selected when eligible, and the resolution reports it at
     * zero Watts under the on-device provider.
     */
    @Test
    fun `an eligible device resolves to the on-device model at zero Watts`() = runBlocking {
        Rung0RoutingBench.open().use { bench ->
            val result = bench.run(listOf(case("rung-0-on-device-available"))).single()

            val resolution = assertIs<RoutingResolution.Success>(result.resolution)
            assertEquals(AIProvider_OnDevice.id, resolution.configuration.provider.id)
            assertEquals("capability:${AIProvider_OnDevice.id}", resolution.reason)

            val resolved = result.trace.lastRoutingEvent<RoutingEvent.RouteResolved>()
            assertEquals(0.0, resolved.estimatedWattCost)
            assertEquals(AIProvider_OnDevice.name, resolved.decision.providerName)
        }
    }

    @Test
    fun `an ineligible device resolves to a metered model and says why`() = runBlocking {
        Rung0RoutingBench.open().use { bench ->
            val result = bench.run(listOf(case("rung-0-ineligible-hardware-falls-back"))).single()

            val resolution = assertIs<RoutingResolution.Success>(result.resolution)
            assertFalse(resolution.configuration.provider.id == AIProvider_OnDevice.id)

            val fallback = result.trace.decodedEvents().filterIsInstance<RoutingEvent.RouteFallback>().single()
            assertEquals(AIProvider_OnDevice.id, fallback.failedProvider)
            assertEquals(Rung0RoutingSuite.INELIGIBLE_HARDWARE, fallback.failureReason)

            val resolved = result.trace.lastRoutingEvent<RoutingEvent.RouteResolved>()
            assertTrue(resolved.estimatedWattCost > 0.0, "a metered route must cost more than 0 Watts")
        }
    }

    /**
     * A deliberately introduced regression turns the gate red, proven on every commit. The
     * tampered field is the Watt cost on the terminal `RouteResolved`: a recording that claims the
     * on-device route cost something is indistinguishable from a relay that started charging for
     * it, which is exactly the regression this bench exists to catch.
     */
    @Test
    fun `a golden trace that charges for the on-device route turns the gate red`() = runBlocking {
        val scenario = Rung0RoutingSuite.scenarios.first { it.id == "rung-0-on-device-available" }
        val golden = GoldenTraces.load(scenario.id)
        val tampered = golden.withTerminalField("estimatedWattCost", JsonPrimitive(0.007))

        Rung0RoutingBench.open().use { bench ->
            val result = bench.run(listOf(Rung0RoutingSuite.case(scenario, tampered))).single()

            assertFalse(result.passed, "A trace that disagrees with the relay was graded green")

            val conformance = result.readings.single { it.meterId == "matches-golden" }
            assertTrue(conformance.score < 1.0)
            assertEquals(
                (golden.events.size - 1).toString(),
                conformance.detail["first_divergence_index"],
                "The tampered field is on the terminal event, so that is where conformance must break",
            )
        }
    }

    @Test
    fun `every scenario ends in the RouteResolved that prices its decision`() = runBlocking {
        Rung0RoutingBench.open().use { bench ->
            val results = bench.run(Rung0RoutingSuite.cases(Rung0RoutingSuite.loadGoldenTraces()))

            results.forEach { result ->
                assertEquals(
                    RoutingEvent.RouteResolved.EVENT_TYPE,
                    result.trace.events.lastOrNull()?.type,
                    "Scenario '${result.caseId}' did not end in RouteResolved; " +
                        "trace was ${result.trace.events.map { it.type }}",
                )
            }
        }
    }

    @Test
    fun `every scenario has a unique id and says why it exists`() {
        assertEquals(
            Rung0RoutingSuite.scenarios.size,
            Rung0RoutingSuite.scenarios.map { it.id }.toSet().size,
            "Scenario ids must be unique — they name the golden trace files",
        )
        Rung0RoutingSuite.scenarios.forEach { scenario ->
            assertTrue(scenario.why.isNotBlank(), "Scenario '${scenario.id}' has no stated reason")
            assertTrue(
                scenario.context.requirements != null,
                "Scenario '${scenario.id}' declares no requirement, so no capability rule could match it",
            )
        }
    }

    // region — helpers

    private fun case(id: String): RoutingCase {
        val scenario = Rung0RoutingSuite.scenarios.first { it.id == id }
        return Rung0RoutingSuite.case(scenario, GoldenTraces.load(id))
    }

    private fun List<RoutingCaseResult>.explain(): String = buildString {
        appendLine("Rung 0 routing bench failed. Failing scenarios:")
        this@explain.filterNot { it.passed }.forEach { result ->
            appendLine("  ${result.caseId}:")
            result.readings.forEach { reading ->
                appendLine("    [${reading.meterId}] score=${reading.score} passed=${reading.passed} ${reading.detail}")
            }
        }
        appendLine("If the change was intended, re-record: ./gradlew :ampere-eval:recordGoldenTraces")
    }

    private fun Trace.decodedEvents(): List<Event> =
        events.map { DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), it.payload) }

    private inline fun <reified T : RoutingEvent> Trace.lastRoutingEvent(): T = assertIs<T>(decodedEvents().last())

    /** This trace with [field] overwritten on its terminal event's payload. */
    private fun Trace.withTerminalField(field: String, value: JsonPrimitive): Trace {
        val last = events.last()
        val payload = last.payload as JsonObject
        return copy(events = events.dropLast(1) + last.copy(payload = JsonObject(payload + (field to value))))
    }

    // endregion
}
